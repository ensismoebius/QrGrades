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
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.model.PeriodSnapshot;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentReport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the raw rows of one student (enrollments, period snapshots, point history, attendance)
 * into a {@link StudentReport}. Pure data in, pure data out — no database or Android — so the
 * period-splitting rules can be unit tested on their own.
 *
 * <p>Closing a period never deletes anything: it snapshots each enrollment's total and resets it
 * to 0. So a record belongs to the earliest standing close of its enrollment that is at or after
 * its timestamp; anything later than the last close belongs to the period still open.
 */
public final class StudentReportBuilder {

    private StudentReportBuilder() {
    }

    /** Everything the report needs, already loaded. */
    public static final class Input {
        public Student student;
        public List<Enrollment> enrollments = new ArrayList<>();
        public Map<String, ClassGroup> classGroupsById = new HashMap<>();
        public Map<String, String> disciplineNamesById = new HashMap<>();
        public Map<String, List<Goal>> goalsByDiscipline = new HashMap<>();
        // Only the periods this student has snapshots in are needed.
        public Map<String, Period> periodsById = new HashMap<>();
        public List<PeriodSnapshot> snapshots = new ArrayList<>();
        public Map<String, List<PointsHistory>> historyByEnrollment = new HashMap<>();
        // All of this student's attendance rows, across every class group.
        public List<Attendance> attendance = new ArrayList<>();
        // The local day goal windows are judged against ("yyyy-MM-dd"); tests pin it.
        public String today = Attendance.today();
    }

    public static StudentReport build(Input in) {
        StudentReport report = new StudentReport();
        report.student = in.student;

        // An entry is "reverted" once some other entry points at it as the one it cancels.
        Set<String> revertedIds = new HashSet<>();
        for (List<PointsHistory> entries : in.historyByEnrollment.values()) {
            for (PointsHistory h : entries) if (h.revertsId != null) revertedIds.add(h.revertsId);
        }
        for (List<PointsHistory> entries : in.historyByEnrollment.values()) {
            for (PointsHistory h : entries) h.reverted = revertedIds.contains(h.id);
        }

        // Standing closes per enrollment, oldest first.
        Map<String, List<PeriodSnapshot>> closesByEnrollment = new LinkedHashMap<>();
        for (PeriodSnapshot s : in.snapshots) {
            Period period = in.periodsById.get(s.periodId);
            if (period == null || period.isReopened()) continue;
            List<PeriodSnapshot> list = closesByEnrollment.get(s.enrollmentId);
            if (list == null) {
                list = new ArrayList<>();
                closesByEnrollment.put(s.enrollmentId, list);
            }
            list.add(s);
        }
        final Map<String, Period> periods = in.periodsById;
        for (List<PeriodSnapshot> list : closesByEnrollment.values()) {
            Collections.sort(list, new Comparator<PeriodSnapshot>() {
                @Override
                public int compare(PeriodSnapshot a, PeriodSnapshot b) {
                    return Long.compare(periods.get(a.periodId).closedAt, periods.get(b.periodId).closedAt);
                }
            });
        }

        for (List<PeriodSnapshot> closes : closesByEnrollment.values()) {
            for (int k = 0; k < closes.size(); k++) {
                PeriodSnapshot s = closes.get(k);
                Period period = in.periodsById.get(s.periodId);
                long start = k == 0 ? Long.MIN_VALUE : in.periodsById.get(closes.get(k - 1).periodId).closedAt;

                StudentReport.Bucket bucket = new StudentReport.Bucket();
                bucket.closed = true;
                bucket.enrollmentId = s.enrollmentId;
                bucket.periodId = period.id;
                bucket.label = period.label == null ? "" : period.label;
                bucket.closedAt = period.closedAt;
                bucket.classClose = period.isClassClose();
                bucket.classGroupId = s.classGroupId;
                ClassGroup group = in.classGroupsById.get(s.classGroupId);
                bucket.classGroup = group == null || group.name == null ? "" : group.name;
                bucket.disciplineId = period.disciplineId == null ? "" : period.disciplineId;
                bucket.discipline = disciplineName(in, period.disciplineId, period.disciplineName);
                bucket.points = s.points;
                String previousCloseDay = k == 0 ? null : Attendance.dayOf(in.periodsById.get(closes.get(k - 1).periodId).closedAt);
                fillGoals(bucket, in.goalsByDiscipline.get(period.disciplineId), in.historyByEnrollment.get(s.enrollmentId),
                        s.points, in.today, previousCloseDay, Attendance.dayOf(period.closedAt), true);
                fillHistory(bucket, in.historyByEnrollment.get(s.enrollmentId), start, period.closedAt);
                fillAttendance(bucket, in.attendance, start, period.closedAt);
                report.closed.add(bucket);
            }
        }

        for (Enrollment e : in.enrollments) {
            List<PeriodSnapshot> closes = closesByEnrollment.get(e.id);
            long start = Long.MIN_VALUE;
            String lastCloseDay = null;
            if (closes != null && !closes.isEmpty()) {
                start = in.periodsById.get(closes.get(closes.size() - 1).periodId).closedAt;
                lastCloseDay = Attendance.dayOf(start);
            }

            ClassGroup group = in.classGroupsById.get(e.classGroupId);
            String disciplineId = group == null ? null : group.disciplineId;

            StudentReport.Bucket bucket = new StudentReport.Bucket();
            bucket.enrollmentId = e.id;
            bucket.classGroupId = e.classGroupId;
            bucket.classGroup = group == null || group.name == null ? "" : group.name;
            bucket.classGroupClosed = group != null && group.isClosed();
            bucket.disciplineId = disciplineId == null ? "" : disciplineId;
            bucket.discipline = disciplineName(in, disciplineId, null);
            bucket.points = e.grades;
            fillGoals(bucket, disciplineId == null ? null : in.goalsByDiscipline.get(disciplineId),
                    in.historyByEnrollment.get(e.id), e.grades, in.today, lastCloseDay, null, false);
            fillHistory(bucket, in.historyByEnrollment.get(e.id), start, Long.MAX_VALUE);
            fillAttendance(bucket, in.attendance, start, Long.MAX_VALUE);
            report.current.add(bucket);
        }

        // Closed buckets of different enrollments interleave; present them chronologically.
        Collections.sort(report.closed, new Comparator<StudentReport.Bucket>() {
            @Override
            public int compare(StudentReport.Bucket a, StudentReport.Bucket b) {
                int byTime = Long.compare(a.closedAt, b.closedAt);
                return byTime != 0 ? byTime : a.discipline.compareToIgnoreCase(b.discipline);
            }
        });
        return report;
    }

    private static String disciplineName(Input in, String disciplineId, String fallback) {
        String name = disciplineId == null ? null : in.disciplineNamesById.get(disciplineId);
        if (name == null) name = fallback;
        return name == null ? "" : name;
    }

    /**
     * The goals shown in one period's block. A goal with a window appears once, in the period it
     * ends in — the one whose close day is the first at or after the goal's last day, or the open
     * period if none is — and counts every point of its window, even those awarded before an
     * earlier close. A goal without dates appears in every period and judges that period's total,
     * as it always did.
     *
     * @param previousCloseDay the day the period before this one was closed, null if there is none
     * @param closeDay         the day this period was closed, null while it is still open
     */
    private static void fillGoals(StudentReport.Bucket bucket, List<Goal> goals, List<PointsHistory> history,
                                  int points, String today, String previousCloseDay, String closeDay, boolean closed) {
        if (goals == null) return;
        for (Goal g : goals) {
            if (g.hasDates()) {
                if (previousCloseDay != null && g.endDate.compareTo(previousCloseDay) <= 0) continue;
                if (closeDay != null && g.endDate.compareTo(closeDay) > 0) continue;
            }
            bucket.goals.add(GoalTracker.progress(g, history, points, today, closed));
        }
        GoalTracker.sortForDisplay(bucket.goals);
    }

    /** Entries with {@code start < createdAt <= end}, newest first. */
    private static void fillHistory(StudentReport.Bucket bucket, List<PointsHistory> entries, long start, long end) {
        if (entries == null) return;
        for (PointsHistory h : entries) {
            if (h.createdAt > start && h.createdAt <= end) bucket.history.add(h);
        }
        Collections.sort(bucket.history, new Comparator<PointsHistory>() {
            @Override
            public int compare(PointsHistory a, PointsHistory b) {
                return Long.compare(b.createdAt, a.createdAt);
            }
        });
    }

    /** This bucket's class group's records recorded within {@code (start, end]}, newest first. */
    private static void fillAttendance(StudentReport.Bucket bucket, List<Attendance> rows, long start, long end) {
        for (Attendance a : rows) {
            if (a.classGroupId == null || !a.classGroupId.equals(bucket.classGroupId)) continue;
            if (a.createdAt > start && a.createdAt <= end) {
                bucket.attendance.add(a);
                bucket.attendanceDays.add(a);
            }
        }
        Collections.sort(bucket.attendanceDays, new Comparator<Attendance>() {
            @Override
            public int compare(Attendance a, Attendance b) {
                return b.day.compareTo(a.day);
            }
        });
    }
}
