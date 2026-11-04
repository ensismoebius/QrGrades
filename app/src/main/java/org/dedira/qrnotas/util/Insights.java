/*
 * QrGrades — track student grades/points, scan QR codes to award points, and optionally
 * expose the same data to a browser on the local network.
 * Copyright (C) 2026 André Furlan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.dedira.qrnotas.util;

import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.StudentExportData;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Who needs attention" lists and the weekly summary, computed from the enrollments of
 * {@link Database#loadExportData}. This is the same logic the browser runs (web/js/insights.js), so
 * the phone and the web page always agree on who is below the passing mark, who is a few points
 * from the next goal and who has been left out of recent scoring.
 *
 * <p>A row is one enrollment. A goal counts only the points given from its start date to its end
 * date, and {@link GoalProgress#currentPoints} is that count. Points are never edited or deleted:
 * cancelling an entry adds an opposite one that points back at it ({@code revertsId}); both are
 * left out of the statistics, because together they change nothing.
 */
public final class Insights {

    public static final long DAY_MS = 24L * 60 * 60 * 1000;

    // "A few points" from a goal: the larger of this many points and this fraction of the target.
    public static final double NEAR_FRACTION = 0.1;
    public static final int NEAR_MIN_POINTS = 3;

    private Insights() {
    }

    /** A student's enrollment together with the goal that put it on a list, and how far from that goal it is. */
    public static final class GoalFlag {
        public final StudentExportData row;
        public final GoalProgress goal;
        public final int missing;

        GoalFlag(StudentExportData row, GoalProgress goal, int missing) {
            this.row = row;
            this.goal = goal;
            this.missing = missing;
        }
    }

    /** An enrollment with no recent points; {@code lastAt} is null when the student never scored at all. */
    public static final class Quiet {
        public final StudentExportData row;
        public final Long lastAt;

        Quiet(StudentExportData row, Long lastAt) {
            this.row = row;
            this.lastAt = lastAt;
        }
    }

    public static final class StudentPoints {
        public final String studentId;
        public final String name;
        public int points;

        StudentPoints(String studentId, String name) {
            this.studentId = studentId;
            this.name = name;
        }
    }

    public static final class TagCount {
        public final String tag;
        public int points;
        public int entries;

        TagCount(String tag) {
            this.tag = tag;
        }
    }

    /** The last seven days against the seven before them. */
    public static final class Week {
        public int given;
        public int deducted;
        public int entries;
        public int net;
        public int students;
        public int prevNet;
        public int prevEntries;
        public List<StudentPoints> top = new ArrayList<>();
        public List<TagCount> tags = new ArrayList<>();
    }

    /* ------------------------------ helpers ------------------------------ */

    /** Ids of the entries that a later reversal cancelled. */
    static Set<String> revertedIds(List<StudentExportData> rows) {
        Set<String> ids = new HashSet<>();
        for (StudentExportData row : rows) {
            if (row.history == null) continue;
            for (PointsHistory h : row.history) {
                if (h.revertsId != null) ids.add(h.revertsId);
            }
        }
        return ids;
    }

    /** A real award or deduction: neither a reversal nor something that was later cancelled. */
    static boolean isEffective(PointsHistory entry, Set<String> reverted) {
        return entry.revertsId == null && !reverted.contains(entry.id);
    }

    /** The goals worth an alert, lowest target first: a finished one is final and a future one hasn't begun. */
    static List<GoalProgress> sortedGoals(List<GoalProgress> goals) {
        List<GoalProgress> out = new ArrayList<>();
        if (goals != null) {
            for (GoalProgress g : goals) {
                if (g.targetPoints > 0 && g.isCounting()) out.add(g);
            }
        }
        Collections.sort(out, new Comparator<GoalProgress>() {
            @Override
            public int compare(GoalProgress a, GoalProgress b) {
                return Integer.compare(a.targetPoints, b.targetPoints);
            }
        });
        return out;
    }

    private static Collator collator() {
        return Collator.getInstance(new Locale("pt", "BR"));
    }

    private static int compareNames(String a, String b) {
        return collator().compare(a == null ? "" : a, b == null ? "" : b);
    }

    /** True when at least one row has a goal at all. */
    public static boolean hasGoals(List<StudentExportData> rows) {
        for (StudentExportData row : rows) {
            if (row.goals != null && !row.goals.isEmpty()) return true;
        }
        return false;
    }

    /** True when at least one goal is still running (or predates dates); the finished and future ones can't raise an alert. */
    public static boolean hasRunningGoals(List<StudentExportData> rows) {
        for (StudentExportData row : rows) {
            if (row.goals == null) continue;
            for (GoalProgress g : row.goals) {
                if (g.isCounting()) return true;
            }
        }
        return false;
    }

    /* ------------------------------ lists ------------------------------ */

    /**
     * Students whose points are under their discipline's lowest goal still running (the passing
     * mark), the furthest from it first. Archived class groups are skipped.
     */
    public static List<GoalFlag> belowMinimum(List<StudentExportData> rows) {
        List<GoalFlag> out = new ArrayList<>();
        for (StudentExportData row : rows) {
            if (row.classGroupClosed) continue;
            List<GoalProgress> goals = sortedGoals(row.goals);
            if (goals.isEmpty()) continue;
            GoalProgress lowest = goals.get(0);
            int missing = lowest.targetPoints - lowest.currentPoints;
            if (missing > 0) out.add(new GoalFlag(row, lowest, missing));
        }
        Collections.sort(out, new Comparator<GoalFlag>() {
            @Override
            public int compare(GoalFlag a, GoalFlag b) {
                return Integer.compare(b.missing, a.missing);
            }
        });
        return out;
    }

    /** {@link #nearNextGoal(List, double, int)} with the default definition of "a few points". */
    public static List<GoalFlag> nearNextGoal(List<StudentExportData> rows) {
        return nearNextGoal(rows, NEAR_FRACTION, NEAR_MIN_POINTS);
    }

    /**
     * Students a few points short of the next goal they haven't reached ("a little push and they're
     * there"), closest first. "A few" is the larger of {@code minPoints} and {@code fraction} of the
     * goal's target.
     */
    public static List<GoalFlag> nearNextGoal(List<StudentExportData> rows, double fraction, int minPoints) {
        List<GoalFlag> out = new ArrayList<>();
        for (StudentExportData row : rows) {
            if (row.classGroupClosed) continue;
            for (GoalProgress goal : sortedGoals(row.goals)) {
                int missing = goal.targetPoints - goal.currentPoints;
                if (missing <= 0) continue; // reached; the next one up is the one that counts
                if (missing <= Math.max(minPoints, (int) Math.ceil(goal.targetPoints * fraction))) {
                    out.add(new GoalFlag(row, goal, missing));
                }
                break;
            }
        }
        Collections.sort(out, new Comparator<GoalFlag>() {
            @Override
            public int compare(GoalFlag a, GoalFlag b) {
                return Integer.compare(a.missing, b.missing);
            }
        });
        return out;
    }

    /**
     * Students with no points in the last {@code days} days, although someone else in their class
     * did get some: if the whole class went quiet (holidays, exams) nobody is singled out. Longest
     * quiet first; never-scored first of all.
     */
    public static List<Quiet> inactive(List<StudentExportData> rows, long now, int days) {
        long cutoff = now - days * DAY_MS;
        Set<String> reverted = revertedIds(rows);

        List<Quiet> infos = new ArrayList<>();
        Map<String, Long> classLast = new HashMap<>();
        for (StudentExportData row : rows) {
            if (row.classGroupClosed) continue;
            Long last = null;
            if (row.history != null) {
                for (PointsHistory h : row.history) {
                    if (isEffective(h, reverted) && (last == null || h.createdAt > last)) last = h.createdAt;
                }
            }
            infos.add(new Quiet(row, last));
            if (last != null) {
                Long seen = classLast.get(row.classGroupId);
                if (seen == null || last > seen) classLast.put(row.classGroupId, last);
            }
        }

        List<Quiet> out = new ArrayList<>();
        for (Quiet info : infos) {
            Long groupLast = classLast.get(info.row.classGroupId);
            if (groupLast == null || groupLast < cutoff) continue;
            if (info.lastAt == null || info.lastAt < cutoff) out.add(info);
        }
        Collections.sort(out, new Comparator<Quiet>() {
            @Override
            public int compare(Quiet a, Quiet b) {
                if (a.lastAt == null && b.lastAt == null) return compareNames(a.row.studentName, b.row.studentName);
                if (a.lastAt == null) return -1;
                if (b.lastAt == null) return 1;
                return Long.compare(a.lastAt, b.lastAt);
            }
        });
        return out;
    }

    /** Whole days from {@code then} up to {@code now}. */
    public static int daysBetween(long then, long now) {
        return (int) Math.floor((now - then) / (double) DAY_MS);
    }

    /** The seven days up to {@code now} against the seven before them. */
    public static Week weeklySummary(List<StudentExportData> rows, long now) {
        Set<String> reverted = revertedIds(rows);
        long start = now - 7 * DAY_MS;
        long prevStart = now - 14 * DAY_MS;

        Week summary = new Week();
        Map<String, StudentPoints> perStudent = new HashMap<>();
        Map<String, TagCount> perTag = new HashMap<>();

        for (StudentExportData row : rows) {
            if (row.history == null) continue;
            for (PointsHistory h : row.history) {
                if (!isEffective(h, reverted)) continue;
                if (h.createdAt > start && h.createdAt <= now) {
                    summary.entries++;
                    summary.net += h.pointsDelta;
                    if (h.pointsDelta > 0) summary.given += h.pointsDelta;
                    else summary.deducted += -h.pointsDelta;

                    StudentPoints s = perStudent.get(row.studentId);
                    if (s == null) {
                        s = new StudentPoints(row.studentId, row.studentName);
                        perStudent.put(row.studentId, s);
                    }
                    s.points += h.pointsDelta;

                    if (h.tag != null && !h.tag.isEmpty()) {
                        TagCount t = perTag.get(h.tag);
                        if (t == null) {
                            t = new TagCount(h.tag);
                            perTag.put(h.tag, t);
                        }
                        t.points += h.pointsDelta;
                        t.entries++;
                    }
                } else if (h.createdAt > prevStart && h.createdAt <= start) {
                    summary.prevEntries++;
                    summary.prevNet += h.pointsDelta;
                }
            }
        }

        summary.students = perStudent.size();
        for (StudentPoints s : perStudent.values()) {
            if (s.points > 0) summary.top.add(s);
        }
        Collections.sort(summary.top, new Comparator<StudentPoints>() {
            @Override
            public int compare(StudentPoints a, StudentPoints b) {
                if (a.points != b.points) return Integer.compare(b.points, a.points);
                return compareNames(a.name, b.name);
            }
        });
        if (summary.top.size() > 3) summary.top = new ArrayList<>(summary.top.subList(0, 3));

        summary.tags.addAll(perTag.values());
        Collections.sort(summary.tags, new Comparator<TagCount>() {
            @Override
            public int compare(TagCount a, TagCount b) {
                if (a.entries != b.entries) return Integer.compare(b.entries, a.entries);
                return Integer.compare(b.points, a.points);
            }
        });
        if (summary.tags.size() > 3) summary.tags = new ArrayList<>(summary.tags.subList(0, 3));
        return summary;
    }
}
