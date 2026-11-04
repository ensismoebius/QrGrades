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
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.model.PeriodSnapshot;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.model.StudentReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the rows that {@link StudentReportBuilder} and {@link PeriodComparison} need and runs
 * them. The browser's API and the phone's own history and comparison screens share this, so the
 * numbers they show are the same.
 *
 * <p>Everything here blocks on {@link DatabaseSync}, so call it from a background thread only
 * (see {@link Background}).
 */
public final class ReportLoader {

    private ReportLoader() {
    }

    /** Loads everything about one student and splits it into periods; null if there is no such student. */
    public static StudentReport studentReport(Database database, String studentId) {
        Student student = DatabaseSync.loadStudent(database, studentId);
        if (student == null) return null;

        StudentReportBuilder.Input in = new StudentReportBuilder.Input();
        in.student = student;

        ArrayList<Enrollment> enrollments = DatabaseSync.loadEnrollmentsForStudent(database, studentId);
        if (enrollments != null) in.enrollments.addAll(enrollments);

        ArrayList<ClassGroup> groups = DatabaseSync.loadAllClassGroups(database);
        if (groups != null) for (ClassGroup g : groups) in.classGroupsById.put(g.id, g);

        ArrayList<Discipline> disciplines = DatabaseSync.loadAllDisciplines(database);
        if (disciplines != null) {
            for (Discipline d : disciplines) {
                in.disciplineNamesById.put(d.id, d.name);
                ArrayList<Goal> goals = DatabaseSync.loadGoalsForDiscipline(database, d.id);
                if (goals != null) in.goalsByDiscipline.put(d.id, goals);
            }
        }

        ArrayList<PeriodSnapshot> snapshots = DatabaseSync.loadPeriodSnapshotsForStudent(database, studentId);
        Set<String> enrollmentIds = new HashSet<>();
        for (Enrollment e : in.enrollments) enrollmentIds.add(e.id);
        if (snapshots != null) {
            in.snapshots.addAll(snapshots);
            for (PeriodSnapshot s : snapshots) {
                enrollmentIds.add(s.enrollmentId);
                if (!in.periodsById.containsKey(s.periodId)) {
                    Period period = DatabaseSync.loadPeriod(database, s.periodId);
                    if (period != null) in.periodsById.put(s.periodId, period);
                }
            }
        }

        for (String enrollmentId : enrollmentIds) {
            ArrayList<PointsHistory> entries = DatabaseSync.loadHistoryForEnrollment(database, enrollmentId);
            in.historyByEnrollment.put(enrollmentId, entries == null ? new ArrayList<PointsHistory>() : entries);
        }

        ArrayList<Attendance> attendance = DatabaseSync.loadAttendanceForStudent(database, studentId);
        if (attendance != null) in.attendance.addAll(attendance);

        return StudentReportBuilder.build(in);
    }

    /** The comparison of a discipline's periods, with the class groups it can be narrowed to. */
    public static final class Comparison {
        public PeriodComparison table;
        /** The discipline's class groups that are still active (a closed one can't be picked as a filter). */
        public List<ClassGroup> openGroups = new ArrayList<>();
    }

    /** Students by closed period, with per-period class statistics. {@code classGroupId} may be null for the whole discipline. */
    public static Comparison comparison(Database database, String disciplineId, String classGroupId) {
        ArrayList<Period> periods = DatabaseSync.loadPeriodsForDiscipline(database, disciplineId);
        if (periods == null) periods = new ArrayList<>();
        Map<String, List<PeriodSnapshot>> snapshotsByPeriod = new HashMap<>();
        for (Period p : periods) {
            if (p.isReopened()) continue;
            ArrayList<PeriodSnapshot> snapshots = DatabaseSync.loadPeriodSnapshots(database, p.id);
            if (snapshots != null) snapshotsByPeriod.put(p.id, snapshots);
        }

        // Enrollments in an archived class are reset to 0 by the close; showing that as "current" would read as a collapse.
        List<StudentExportData> current = new ArrayList<>();
        List<StudentExportData> all = DatabaseSync.loadExportData(database, null);
        if (all != null) {
            for (StudentExportData d : all) {
                if (disciplineId.equals(d.disciplineId) && !d.classGroupClosed) current.add(d);
            }
        }

        Comparison out = new Comparison();
        Map<String, String> classGroupNames = new HashMap<>();
        ArrayList<ClassGroup> groups = DatabaseSync.loadClassGroupsForDiscipline(database, disciplineId);
        if (groups != null) {
            for (ClassGroup g : groups) {
                classGroupNames.put(g.id, g.name);
                if (!g.isClosed()) out.openGroups.add(g);
            }
        }

        out.table = PeriodComparison.build(periods, snapshotsByPeriod, current, classGroupNames,
                (classGroupId != null && classGroupId.isEmpty()) ? null : classGroupId);
        return out;
    }
}
