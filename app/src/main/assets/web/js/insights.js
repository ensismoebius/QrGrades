/*
 * Pure functions that turn the rows of /api/overview into "who needs attention" lists and a weekly
 * summary. No DOM and no network, so they can be checked from Node as well (see the export at the bottom).
 *
 * A row is one enrollment: { enrollmentId, studentId, name, disciplineId, classGroupId, classGroupClosed,
 * points, goals: [{ name, target, current, achieved, remaining, startDate, endDate, state, daysLeft }],
 * history: [{ id, points, note, tag, timestamp, revertsId }] }.
 *
 * A goal counts only the points given from its startDate to its endDate (both included), and `current` is
 * that count. `state` says where today falls: "active" (running), "upcoming" (not started), "ended" (over),
 * or "open" (a goal saved before goals had dates, which counts the row's whole total).
 *
 * Points are never edited or deleted. Cancelling an entry adds an opposite one that points back at it
 * (revertsId); both are left out of the statistics here, because together they change nothing.
 */
(function (root) {
    'use strict';

    var DAY_MS = 24 * 60 * 60 * 1000;

    function signed(n) {
        return (n > 0 ? '+' : '') + n;
    }

    // 83.333 -> "83,3%" (Brazilian decimal comma)
    function percent(value) {
        return (Math.round(value * 10) / 10).toString().replace('.', ',') + '%';
    }

    // 14.25 -> "14,3"; whole numbers stay whole.
    function decimal(value) {
        return (Math.round(value * 10) / 10).toString().replace('.', ',');
    }

    function daysBetween(then, now) {
        return Math.floor((now - then) / DAY_MS);
    }

    // Ids of the entries that a later reversal cancelled.
    function revertedIds(rows) {
        var ids = {};
        rows.forEach(function (row) {
            (row.history || []).forEach(function (h) {
                if (h.revertsId) ids[h.revertsId] = true;
            });
        });
        return ids;
    }

    // A real award or deduction: neither a reversal nor something that was later cancelled.
    function isEffective(entry, reverted) {
        return !entry.revertsId && !reverted[entry.id];
    }

    // The points that count for a goal: the ones inside its window (older servers sent no `current`: then the row's total).
    function goalCurrent(row, goal) {
        return goal.current != null ? goal.current : row.points;
    }

    // Only a goal that can still change is worth an alert: a finished one is final and a future one hasn't begun.
    function isCounting(goal) {
        return !goal.state || goal.state === 'open' || goal.state === 'active';
    }

    function sortedGoals(goals) {
        return (goals || []).filter(function (g) { return g.target > 0 && isCounting(g); })
            .slice().sort(function (a, b) { return a.target - b.target; });
    }

    // "termina hoje" / "termina amanhã" / "termina em 5 dias".
    function endsIn(daysLeft) {
        if (daysLeft <= 0) return 'termina hoje';
        if (daysLeft === 1) return 'termina amanhã';
        return 'termina em ' + daysLeft + ' dias';
    }

    // 2026-04-30 -> 30/04/2026
    function brDay(day) {
        var p = String(day).split('-');
        return p.length === 3 ? p[2] + '/' + p[1] + '/' + p[0] : day;
    }

    // "01/02/2026 – 30/04/2026 · termina em 5 dias"; empty for a goal without dates.
    function windowText(goal) {
        if (!goal.startDate || !goal.endDate) return '';
        var note = goal.state === 'upcoming' ? 'ainda não começou'
            : goal.state === 'ended' ? 'encerrada'
            : endsIn(goal.daysLeft || 0);
        return brDay(goal.startDate) + ' – ' + brDay(goal.endDate) + ' · ' + note;
    }

    // Students whose points are under their discipline's lowest goal still running (the passing mark), the furthest from it first.
    function belowMinimum(rows) {
        var out = [];
        rows.forEach(function (row) {
            if (row.classGroupClosed) return;
            var goals = sortedGoals(row.goals);
            if (goals.length === 0) return;
            var missing = goals[0].target - goalCurrent(row, goals[0]);
            if (missing > 0) out.push({ row: row, goal: goals[0], missing: missing });
        });
        out.sort(function (a, b) { return b.missing - a.missing; });
        return out;
    }

    // Students a few points short of the next goal they haven't reached ("a little push and they're there"), closest first.
    // "A few" is the larger of minPoints and a fraction of the goal's target.
    function nearNextGoal(rows, options) {
        options = options || {};
        var fraction = options.fraction == null ? 0.1 : options.fraction;
        var minPoints = options.minPoints == null ? 3 : options.minPoints;
        var out = [];
        rows.forEach(function (row) {
            if (row.classGroupClosed) return;
            var goals = sortedGoals(row.goals);
            for (var i = 0; i < goals.length; i++) {
                var missing = goals[i].target - goalCurrent(row, goals[i]);
                if (missing <= 0) continue; // reached; the next one up is the one that counts
                if (missing <= Math.max(minPoints, Math.ceil(goals[i].target * fraction))) {
                    out.push({ row: row, goal: goals[i], missing: missing });
                }
                break;
            }
        });
        out.sort(function (a, b) { return a.missing - b.missing; });
        return out;
    }

    // Students with no points in the last `days` days, although someone else in their class did get some:
    // if the whole class went quiet (holidays, exams) nobody is singled out. Longest quiet first; never-scored first of all.
    function inactive(rows, now, days) {
        var cutoff = now - days * DAY_MS;
        var reverted = revertedIds(rows);

        var infos = rows.filter(function (r) { return !r.classGroupClosed; }).map(function (row) {
            var last = null;
            (row.history || []).forEach(function (h) {
                if (isEffective(h, reverted) && (last == null || h.timestamp > last)) last = h.timestamp;
            });
            return { row: row, lastAt: last };
        });

        var classLast = {};
        infos.forEach(function (info) {
            var key = info.row.classGroupId;
            if (info.lastAt != null && (classLast[key] == null || info.lastAt > classLast[key])) classLast[key] = info.lastAt;
        });

        var out = infos.filter(function (info) {
            var groupLast = classLast[info.row.classGroupId];
            if (groupLast == null || groupLast < cutoff) return false;
            return info.lastAt == null || info.lastAt < cutoff;
        });
        out.sort(function (a, b) {
            if (a.lastAt == null && b.lastAt == null) return a.row.name.localeCompare(b.row.name);
            if (a.lastAt == null) return -1;
            if (b.lastAt == null) return 1;
            return a.lastAt - b.lastAt;
        });
        return out;
    }

    // The seven days up to `now` against the seven before them.
    function weeklySummary(rows, now) {
        var reverted = revertedIds(rows);
        var start = now - 7 * DAY_MS;
        var prevStart = now - 14 * DAY_MS;

        var summary = { given: 0, deducted: 0, entries: 0, net: 0, students: 0, prevNet: 0, prevEntries: 0, top: [], tags: [] };
        var perStudent = {}, perTag = {};

        rows.forEach(function (row) {
            (row.history || []).forEach(function (h) {
                if (!isEffective(h, reverted)) return;
                if (h.timestamp > start && h.timestamp <= now) {
                    summary.entries++;
                    summary.net += h.points;
                    if (h.points > 0) summary.given += h.points;
                    else summary.deducted += -h.points;

                    var s = perStudent[row.studentId];
                    if (!s) s = perStudent[row.studentId] = { studentId: row.studentId, name: row.name, points: 0 };
                    s.points += h.points;

                    if (h.tag) {
                        var t = perTag[h.tag];
                        if (!t) t = perTag[h.tag] = { tag: h.tag, points: 0, entries: 0 };
                        t.points += h.points;
                        t.entries++;
                    }
                } else if (h.timestamp > prevStart && h.timestamp <= start) {
                    summary.prevEntries++;
                    summary.prevNet += h.points;
                }
            });
        });

        var students = Object.keys(perStudent).map(function (k) { return perStudent[k]; });
        summary.students = students.length;
        summary.top = students.filter(function (s) { return s.points > 0; })
            .sort(function (a, b) { return b.points - a.points || a.name.localeCompare(b.name); }).slice(0, 3);
        summary.tags = Object.keys(perTag).map(function (k) { return perTag[k]; })
            .sort(function (a, b) { return b.entries - a.entries || b.points - a.points; }).slice(0, 3);
        return summary;
    }

    var api = {
        DAY_MS: DAY_MS,
        signed: signed,
        percent: percent,
        decimal: decimal,
        daysBetween: daysBetween,
        goalCurrent: goalCurrent,
        endsIn: endsIn,
        windowText: windowText,
        belowMinimum: belowMinimum,
        nearNextGoal: nearNextGoal,
        inactive: inactive,
        weeklySummary: weeklySummary
    };

    root.QGInsights = api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this);
