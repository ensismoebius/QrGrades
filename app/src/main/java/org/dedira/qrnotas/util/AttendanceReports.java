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

import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.AttendanceStats;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.model.Student;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The attendance figures that more than one screen needs: a class's days, each student's totals
 * over a period, and the "too many absences" list. The browser's API and the phone's own screens
 * both call this, so they cannot disagree about who is over the limit.
 *
 * <p>Every loader here blocks on {@link DatabaseSync}, so call them from a background thread only
 * (see {@link Background}).
 */
public final class AttendanceReports {

    // Brazilian schools generally require at least 75% attendance, i.e. at most 25% absences.
    public static final double DEFAULT_ABSENCE_LIMIT_PERCENT = 25.0;
    // A student with one absence in one class has 100% absences; don't raise alarms until there's something to measure.
    public static final int DEFAULT_MIN_DAYS = 3;

    private AttendanceReports() {
    }

    /** The slice of a class group's timeline a total is computed over: everything after {@code start} up to and including {@code end}. */
    public static final class Window {
        public long start = Long.MIN_VALUE;
        public long end = Long.MAX_VALUE;

        public boolean contains(Attendance a) {
            return a.createdAt > start && a.createdAt <= end;
        }
    }

    /**
     * The window of the period still open (when {@code period} is null, empty or "current"), or of
     * the closed period with that id; null if the id isn't one of the class's standing closes.
     */
    public static Window windowFor(List<Period> standing, String period) {
        Window window = new Window();
        if (period == null || period.isEmpty() || period.equals("current")) {
            if (!standing.isEmpty()) window.start = standing.get(standing.size() - 1).closedAt;
            return window;
        }
        for (int i = 0; i < standing.size(); i++) {
            if (standing.get(i).id.equals(period)) {
                window.start = i == 0 ? Long.MIN_VALUE : standing.get(i - 1).closedAt;
                window.end = standing.get(i).closedAt;
                return window;
            }
        }
        return null;
    }

    /* ------------------------------ class days ------------------------------ */

    /** One recorded class day with its tallies. */
    public static final class Day {
        public final String day;
        public final AttendanceStats stats;

        Day(String day, AttendanceStats stats) {
            this.day = day;
            this.stats = stats;
        }
    }

    /** Every recorded day of the class, newest first, with its tallies. */
    public static List<Day> days(Database database, String classGroupId) {
        Map<String, AttendanceStats> byDay = new TreeMap<>(Collections.<String>reverseOrder());
        ArrayList<Attendance> rows = DatabaseSync.loadAttendanceForClassGroup(database, classGroupId);
        if (rows != null) {
            for (Attendance a : rows) {
                AttendanceStats stats = byDay.get(a.day);
                if (stats == null) {
                    stats = new AttendanceStats();
                    byDay.put(a.day, stats);
                }
                stats.add(a);
            }
        }
        List<Day> out = new ArrayList<>();
        for (Map.Entry<String, AttendanceStats> e : byDay.entrySet()) out.add(new Day(e.getKey(), e.getValue()));
        return out;
    }

    /* --------------------------- per-student totals --------------------------- */

    /** One student's totals. */
    public static final class StudentTotals {
        public final Student student;
        public final AttendanceStats stats;

        StudentTotals(Student student, AttendanceStats stats) {
            this.student = student;
            this.stats = stats;
        }
    }

    /** A class group's totals for one period. */
    public static final class Summary {
        public ClassGroup group;
        public String period = "current";
        /** The closes that cut this class's timeline, oldest first (the choices for {@code period}). */
        public List<Period> periods = new ArrayList<>();
        /** Class days recorded inside the period. */
        public int days;
        public List<StudentTotals> students = new ArrayList<>();
    }

    /** Totals for every enrolled student of {@code group} over {@code periodParam} ("current" or a period id); null if that period isn't one of the class's. */
    public static Summary summary(Database database, ClassGroup group, String periodParam) {
        ArrayList<Period> disciplinePeriods = DatabaseSync.loadPeriodsForDiscipline(database, group.disciplineId);
        List<Period> standing = Periods.standingFor(disciplinePeriods, group.id);
        Window window = windowFor(standing, periodParam);
        if (window == null) return null;

        ArrayList<Attendance> rows = DatabaseSync.loadAttendanceForClassGroup(database, group.id);
        Map<String, AttendanceStats> byStudent = new HashMap<>();
        Set<String> days = new HashSet<>();
        if (rows != null) {
            for (Attendance a : rows) {
                if (!window.contains(a)) continue;
                days.add(a.day);
                AttendanceStats stats = byStudent.get(a.studentId);
                if (stats == null) {
                    stats = new AttendanceStats();
                    byStudent.put(a.studentId, stats);
                }
                stats.add(a);
            }
        }

        Summary out = new Summary();
        out.group = group;
        out.period = (periodParam == null || periodParam.isEmpty()) ? "current" : periodParam;
        out.periods = standing;
        out.days = days.size();
        ArrayList<Student> enrolled = DatabaseSync.loadStudentsForClassGroup(database, group.id);
        if (enrolled != null) {
            for (Student s : enrolled) {
                AttendanceStats stats = byStudent.get(s.id);
                out.students.add(new StudentTotals(s, stats == null ? new AttendanceStats() : stats));
            }
        }
        return out;
    }

    /* ----------------------------------- alerts ----------------------------------- */

    /** A student over the absence limit in one class. */
    public static final class Alert {
        public Student student;
        public ClassGroup group;
        public String disciplineName = "";
        public AttendanceStats stats;
    }

    public static final class Alerts {
        public double limit;
        public int minDays;
        public int classGroupsChecked;
        /** Worst first. */
        public List<Alert> alerts = new ArrayList<>();
    }

    /**
     * Students of every still-active class group whose absences in the current period exceed
     * {@code limit} percent, worst first. Excused absences count, as they do toward a
     * minimum-attendance rule. Classes with fewer than {@code minDays} recorded days are left alone.
     */
    public static Alerts alerts(Database database, double limit, int minDays) {
        Map<String, String> disciplineNames = new HashMap<>();
        ArrayList<Discipline> disciplines = DatabaseSync.loadAllDisciplines(database);
        if (disciplines != null) for (Discipline d : disciplines) disciplineNames.put(d.id, d.name);

        Alerts out = new Alerts();
        out.limit = limit;
        out.minDays = minDays;

        Map<String, ArrayList<Period>> periodsByDiscipline = new HashMap<>();
        ArrayList<ClassGroup> groups = DatabaseSync.loadAllClassGroups(database);
        if (groups != null) {
            for (ClassGroup group : groups) {
                if (group.isClosed()) continue;
                out.classGroupsChecked++;

                ArrayList<Period> periods = periodsByDiscipline.get(group.disciplineId);
                if (periods == null) {
                    periods = DatabaseSync.loadPeriodsForDiscipline(database, group.disciplineId);
                    if (periods == null) periods = new ArrayList<>();
                    periodsByDiscipline.put(group.disciplineId, periods);
                }
                Window window = windowFor(Periods.standingFor(periods, group.id), null);

                ArrayList<Attendance> rows = DatabaseSync.loadAttendanceForClassGroup(database, group.id);
                if (rows == null || rows.isEmpty()) continue;
                Map<String, AttendanceStats> byStudent = new HashMap<>();
                for (Attendance a : rows) {
                    if (!window.contains(a)) continue;
                    AttendanceStats stats = byStudent.get(a.studentId);
                    if (stats == null) {
                        stats = new AttendanceStats();
                        byStudent.put(a.studentId, stats);
                    }
                    stats.add(a);
                }

                ArrayList<Student> enrolled = DatabaseSync.loadStudentsForClassGroup(database, group.id);
                if (enrolled == null) continue;
                for (Student s : enrolled) {
                    AttendanceStats stats = byStudent.get(s.id);
                    if (stats == null || stats.total() < minDays || stats.absentPercent() <= limit) continue;
                    Alert alert = new Alert();
                    alert.student = s;
                    alert.group = group;
                    String name = disciplineNames.get(group.disciplineId);
                    alert.disciplineName = name == null ? "" : name;
                    alert.stats = stats;
                    out.alerts.add(alert);
                }
            }
        }

        Collections.sort(out.alerts, new Comparator<Alert>() {
            @Override
            public int compare(Alert a, Alert b) {
                return Double.compare(b.stats.absentPercent(), a.stats.absentPercent());
            }
        });
        return out;
    }
}
