/*
 * "Frequência": take the roll for a class on a given day, see which days have a roll call, and
 * total up presences and absences per student for the current period or a closed one.
 *
 * A roll call is per class and day. Starting one lists every enrolled student; from then on each
 * student is present, absent or absent-with-an-excuse. (The phone can also mark students present
 * as it scans their QR codes.)
 */
(function () {
    'use strict';

    var QG = window.QG;
    var I = window.QGInsights;
    var esc = QG.escapeHtml;

    var PRESENT = 1, ABSENT = 0, EXCUSED = 2;
    // Usual minimum-attendance rule: more than a quarter of the classes missed is a problem. Matches the alerts screen's default.
    var ABSENCE_LIMIT = 25;

    var at = {
        classGroupId: '',
        day: '',
        dayData: null,
        period: 'current'
    };

    QG.registerTab('attendance', renderAttendance);

    function activeGroups() {
        return QG.state.classGroups.filter(function (g) { return !g.closed; });
    }

    // "Disciplina · Turma", so two classes with the same name in different disciplines can be told apart in the closed select.
    function groupOptions(groups) {
        var html = '';
        QG.state.disciplines.forEach(function (d) {
            groups.filter(function (g) { return g.disciplineId === d.id; }).forEach(function (g) {
                html += '<option value="' + esc(g.id) + '"' + (g.id === at.classGroupId ? ' selected' : '') + '>' +
                    esc(d.name) + ' · ' + esc(g.name) + '</option>';
            });
        });
        return html;
    }

    // "2026-10-21" -> "quarta-feira, 21/10/2026"
    function longDay(day) {
        var p = String(day).split('-');
        if (p.length !== 3) return day;
        var date = new Date(parseInt(p[0], 10), parseInt(p[1], 10) - 1, parseInt(p[2], 10));
        return date.toLocaleDateString('pt-BR', { weekday: 'long' }) + ', ' + QG.formatDayString(day);
    }

    function periodTitle(p) {
        return p.label ? p.label : 'Encerrado em ' + QG.formatDay(p.closedAt);
    }

    function plural(n, one, many) {
        return n + ' ' + (n === 1 ? one : many);
    }

    /* ------------------------------------ screen ------------------------------------ */

    function renderAttendance() {
        var el = document.getElementById('tab-attendance');
        var groups = activeGroups();
        if (groups.length === 0) {
            el.innerHTML = '<div class="card"><p>Cadastre uma turma e matricule alunos primeiro.</p></div>';
            return;
        }
        if (!groups.some(function (g) { return g.id === at.classGroupId; })) {
            at.classGroupId = groups[0].id;
            at.period = 'current';
        }
        if (!at.day) at.day = QG.todayString();

        el.innerHTML =
            '<div class="card">' +
            '<div class="row"><h2 style="flex:1">Chamada</h2>' +
            '<label>Turma:</label><select id="att-group">' + groupOptions(groups) + '</select>' +
            '<label>Data:</label><input type="date" id="att-day" value="' + esc(at.day) + '"></div>' +
            '<div id="att-roll"></div></div>' +
            '<div class="card"><h2>Dias com chamada</h2><div id="att-days"></div></div>' +
            '<div class="card"><div class="row"><h2 style="flex:1">Resumo por aluno</h2>' +
            '<label>Período:</label><select id="att-period"><option value="current">Período atual</option></select></div>' +
            '<div id="att-summary"></div></div>';

        document.getElementById('att-group').addEventListener('change', function (e) {
            at.classGroupId = e.target.value;
            at.period = 'current';
            at.dayData = null;
            loadAll();
        });
        document.getElementById('att-day').addEventListener('change', function (e) {
            if (!e.target.value) { // the date field was cleared; keep showing the day we had
                e.target.value = at.day;
                return;
            }
            at.day = e.target.value;
            loadDay();
            highlightDay();
        });
        document.getElementById('att-period').addEventListener('change', function (e) {
            at.period = e.target.value;
            loadSummary();
        });

        var roll = document.getElementById('att-roll');
        roll.addEventListener('change', onRollChange);
        roll.addEventListener('click', onRollClick);

        loadAll();
    }

    function loadAll() {
        loadDay();
        loadDays();
        loadSummary();
    }

    // The three cards change together whenever the roll does.
    function refreshAround() {
        loadDays();
        loadSummary();
    }

    function stale(group, day) {
        return group !== at.classGroupId || (day != null && day !== at.day);
    }

    function fail(err) {
        QG.toast(QG.errorMessage(err), { error: true });
        loadAll();
    }

    /* -------------------------------------- roll -------------------------------------- */

    function loadDay() {
        var group = at.classGroupId, day = at.day;
        var box = document.getElementById('att-roll');
        if (!box) return;
        box.innerHTML = '<p>Carregando…</p>';
        QG.api('/api/attendance?classGroupId=' + encodeURIComponent(group) + '&day=' + encodeURIComponent(day)).then(function (data) {
            if (stale(group, day)) return;
            at.dayData = data;
            renderRoll();
        }).catch(function (err) {
            if (stale(group, day)) return;
            box.innerHTML = '<div class="summary-banner error">' + esc(QG.errorMessage(err)) + '</div>';
        });
    }

    function countsHtml(c) {
        return 'Presentes <strong>' + c.present + '</strong> · Faltas <strong>' + c.absent + '</strong> · Justificadas <strong>' +
            c.excused + '</strong>' + (c.unmarked ? ' · Sem registro <strong>' + c.unmarked + '</strong>' : '');
    }

    function segmentHtml(s, locked) {
        function option(value, label, cls) {
            return '<label class="seg-opt ' + cls + '"><input type="radio" name="st-' + esc(s.studentId) + '" value="' + value + '"' +
                (s.status === value ? ' checked' : '') + (locked ? ' disabled' : '') + '><span>' + label + '</span></label>';
        }
        return '<div class="seg">' + option(PRESENT, 'Presente', 'present') + option(ABSENT, 'Falta', 'absent') +
            option(EXCUSED, 'Justificada', 'excused') + '</div>';
    }

    function renderRoll() {
        var box = document.getElementById('att-roll');
        var d = at.dayData;
        if (!box || !d) return;

        if (d.students.length === 0) {
            box.innerHTML = '<p>Esta turma não tem alunos matriculados.</p>';
            return;
        }

        if (!d.started) {
            box.innerHTML = '<p>Nenhuma chamada registrada em <strong>' + esc(longDay(d.day)) + '</strong>.</p>' +
                '<div class="row">' +
                '<button type="button" class="btn" data-action="start-present">Iniciar com todos presentes</button>' +
                '<button type="button" class="btn secondary" data-action="start-absent">Iniciar com todos ausentes</button></div>' +
                '<p class="muted">Depois é só ajustar quem faltou. No celular, o modo chamada também marca a presença ao ler o código QR.</p>';
            return;
        }

        var locked = d.closed;
        box.innerHTML =
            '<div class="row"><strong style="flex:1">' + esc(longDay(d.day)) + '</strong>' +
            '<span class="muted" id="att-counts">' + countsHtml(d.counts) + '</span></div>' +
            (locked ? '<div class="summary-banner">Esta turma foi encerrada; a chamada não pode mais ser alterada.</div>' : '') +
            (locked ? '' :
                '<div class="row">' +
                '<button type="button" class="btn secondary small" data-action="all-present">Todos presentes</button>' +
                '<button type="button" class="btn secondary small" data-action="all-absent">Todos ausentes</button>' +
                '<span style="flex:1"></span>' +
                '<button type="button" class="btn danger small" data-action="delete-day">Excluir esta chamada</button></div>') +
            '<div class="table-wrap"><table><tbody>' +
            d.students.map(function (s) {
                return '<tr data-student="' + esc(s.studentId) + '"><td class="roll-name"><div class="row" style="margin:0;flex-wrap:nowrap">' +
                    (s.hasPhoto ? '<img class="avatar small" src="/api/students/' + esc(s.studentId) + '/photo" onerror="this.remove()">' : '') +
                    '<span>' + esc(s.name) + '</span></div></td><td>' + segmentHtml(s, locked) + '</td></tr>';
            }).join('') + '</tbody></table></div>';
    }

    function onRollChange(e) {
        var radio = e.target.closest('input[type="radio"]');
        if (!radio) return;
        var row = radio.closest('tr[data-student]');
        if (!row) return;
        var status = parseInt(radio.value, 10);
        var studentId = row.getAttribute('data-student');
        var group = at.classGroupId, day = at.day;
        QG.api('/api/attendance', {
            method: 'PUT',
            body: { classGroupId: group, day: day, marks: [{ studentId: studentId, status: status }] }
        }).then(function (data) {
            if (stale(group, day)) return;
            at.dayData = data;
            var counts = document.getElementById('att-counts'); // update the tallies in place so keyboard focus stays on the radio
            if (counts) counts.innerHTML = countsHtml(data.counts);
            refreshAround();
        }).catch(fail);
    }

    function saveAll(status) {
        var d = at.dayData;
        if (!d) return;
        var group = at.classGroupId, day = at.day;
        return QG.api('/api/attendance', {
            method: 'PUT',
            body: {
                classGroupId: group,
                day: day,
                marks: d.students.map(function (s) { return { studentId: s.studentId, status: status }; })
            }
        }).then(function (data) {
            if (stale(group, day)) return;
            at.dayData = data;
            renderRoll();
            refreshAround();
        });
    }

    function onRollClick(e) {
        var btn = e.target.closest('button[data-action]');
        if (!btn) return;
        var action = btn.getAttribute('data-action');
        var group = at.classGroupId, day = at.day;

        if (action === 'start-present' || action === 'start-absent') {
            btn.disabled = true;
            QG.api('/api/attendance/start', { method: 'POST', body: { classGroupId: group, day: day } }).then(function (data) {
                if (stale(group, day)) return;
                at.dayData = data;
                if (action === 'start-present') return saveAll(PRESENT);
                renderRoll();
                refreshAround();
            }).catch(fail);
        } else if (action === 'all-present') {
            saveAll(PRESENT).catch(fail);
        } else if (action === 'all-absent') {
            saveAll(ABSENT).catch(fail);
        } else if (action === 'delete-day') {
            if (!confirm('Excluir a chamada de ' + QG.formatDayString(day) + '?\n\nAs presenças e faltas desse dia deixam de contar.')) return;
            QG.api('/api/attendance?classGroupId=' + encodeURIComponent(group) + '&day=' + encodeURIComponent(day), { method: 'DELETE' })
                .then(function () {
                    QG.toast('Chamada excluída.');
                    loadAll();
                }).catch(fail);
        }
    }

    /* ----------------------------------- recorded days ----------------------------------- */

    function loadDays() {
        var group = at.classGroupId;
        var box = document.getElementById('att-days');
        if (!box) return;
        QG.api('/api/attendance/days?classGroupId=' + encodeURIComponent(group)).then(function (data) {
            if (stale(group)) return;
            if (data.days.length === 0) {
                box.innerHTML = '<p class="muted">Ainda não há chamadas registradas para esta turma.</p>';
                return;
            }
            box.innerHTML = '<div class="table-wrap days-list"><table><thead><tr><th>Dia</th><th>Presentes</th><th>Faltas</th><th>Justificadas</th></tr></thead><tbody>' +
                data.days.map(function (d) {
                    return '<tr class="clickable" data-day="' + esc(d.day) + '"><td>' + esc(longDay(d.day)) + '</td><td>' + d.present +
                        '</td><td>' + d.absent + '</td><td>' + d.excused + '</td></tr>';
                }).join('') + '</tbody></table></div>';
            box.querySelectorAll('tr[data-day]').forEach(function (tr) {
                tr.addEventListener('click', function () {
                    at.day = tr.getAttribute('data-day');
                    document.getElementById('att-day').value = at.day;
                    loadDay();
                    highlightDay();
                    document.getElementById('att-roll').scrollIntoView({ behavior: 'smooth', block: 'start' });
                });
            });
            highlightDay();
        }).catch(function (err) {
            if (!stale(group)) box.innerHTML = '<div class="summary-banner error">' + esc(QG.errorMessage(err)) + '</div>';
        });
    }

    function highlightDay() {
        document.querySelectorAll('#att-days tr[data-day]').forEach(function (tr) {
            tr.classList.toggle('selected', tr.getAttribute('data-day') === at.day);
        });
    }

    /* ---------------------------------- per-student totals ---------------------------------- */

    function loadSummary() {
        var group = at.classGroupId, period = at.period;
        var box = document.getElementById('att-summary');
        if (!box) return;
        QG.api('/api/attendance/summary?classGroupId=' + encodeURIComponent(group) + '&period=' + encodeURIComponent(period)).then(function (data) {
            if (stale(group) || period !== at.period) return;
            fillPeriodSelect(data.periods);

            var students = data.students.slice().sort(function (a, b) {
                return b.absentPct - a.absentPct || a.name.localeCompare(b.name);
            });
            box.innerHTML = '<p class="muted">' + (data.days === 0 ? 'Nenhuma aula registrada neste período.'
                    : plural(data.days, 'aula registrada', 'aulas registradas') + ' neste período.') +
                ' A porcentagem de faltas inclui as justificadas; acima de ' + ABSENCE_LIMIT + '% fica em destaque.</p>' +
                '<div class="table-wrap"><table><thead><tr><th>Aluno</th><th>Presenças</th><th>Faltas</th><th>Justificadas</th><th>% de faltas</th></tr></thead><tbody>' +
                students.map(function (s) {
                    var over = s.total > 0 && s.absentPct > ABSENCE_LIMIT;
                    return '<tr class="clickable' + (over ? ' warn' : '') + '" data-student="' + esc(s.studentId) + '"><td>' + esc(s.name) + '</td>' +
                        '<td>' + s.present + '</td><td>' + s.absent + '</td><td>' + s.excused + '</td>' +
                        '<td>' + (s.total === 0 ? '—' : (over ? '⚠ ' : '') + I.percent(s.absentPct)) + '</td></tr>';
                }).join('') + '</tbody></table></div>';
            box.querySelectorAll('tr[data-student]').forEach(function (tr) {
                tr.addEventListener('click', function () { QG.openHistory(tr.getAttribute('data-student')); });
            });
        }).catch(function (err) {
            if (!stale(group)) box.innerHTML = '<div class="summary-banner error">' + esc(QG.errorMessage(err)) + '</div>';
        });
    }

    // The closed periods only become known once the summary arrives.
    function fillPeriodSelect(periods) {
        var select = document.getElementById('att-period');
        if (!select) return;
        var ordered = periods.slice().sort(function (a, b) { return b.closedAt - a.closedAt; });
        select.innerHTML = '<option value="current">Período atual</option>' + ordered.map(function (p) {
            return '<option value="' + esc(p.id) + '">' + esc(periodTitle(p)) + '</option>';
        }).join('');
        select.value = at.period;
    }
})();
