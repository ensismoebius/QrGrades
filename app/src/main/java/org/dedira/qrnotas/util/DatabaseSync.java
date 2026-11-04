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

import android.content.Context;
import android.os.Looper;

import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.CsvImportPlan;
import org.dedira.qrnotas.model.CsvStudentRow;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.model.PeriodSnapshot;
import org.dedira.qrnotas.model.PointsAward;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.IDatabaseOnLoad;
import org.dedira.qrnotas.model.ResolvedCsvRow;
import org.dedira.qrnotas.model.RevertResult;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Blocks the calling thread until one of {@link Database}'s (or {@link DbBackup}'s) async
 * callback methods completes. {@link Database} always posts its callback to the main-thread
 * {@code Handler}, so it never fires on the thread that's waiting on it here — safe to block
 * from any *non*-main thread. NanoHTTPD services each HTTP request on its own worker thread
 * (never the UI thread), which is the only place this class is meant to be used from.
 *
 * <p>Every method below follows the same shape: create a {@link CountDownLatch} of 1, call the
 * matching {@link Database} method with a callback that stashes its result and counts the latch
 * down, then {@link #awaitLatch} (bounded by {@link #TIMEOUT_SECONDS}, so a stuck request times
 * out instead of hanging the HTTP connection forever) and return the stashed result.
 */
public class DatabaseSync {

    private static final long TIMEOUT_SECONDS = 5;

    /** Simple holder for a callback's outcome, since a lambda can't return a value up the call stack — it has to write into something the outer method can read after the latch releases. */
    public static final class Result<T> {
        public boolean success;
        public T value;
        public String error;
    }

    private DatabaseSync() {
    }

    /** Guards against accidentally blocking the UI thread, which would deadlock: {@link Database}'s callback is itself posted to the main thread, so it could never run while the main thread is stuck here waiting for it. */
    private static void assertNotMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("DatabaseSync must not be called from the main thread");
        }
    }

    /** Waits for the async callback to fire, giving up after {@link #TIMEOUT_SECONDS} rather than blocking indefinitely if something goes wrong. */
    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* -------------------------------- Students -------------------------------- */

    /** Synchronous wrapper around {@link Database#loadAllStudents}. */
    public static ArrayList<Student> loadAllStudents(Database database) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<ArrayList<Student>> result = new Result<>();
        database.loadAllStudents((success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#loadStudent}. */
    public static Student loadStudent(Database database, String id) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Student> result = new Result<>();
        database.loadStudent(id, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.success ? result.value : null;
    }

    /** Synchronous wrapper around {@link Database#saveStudent}. */
    public static Result<Student> saveStudent(Database database, Student student) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Student> result = new Result<>();
        database.saveStudent(student, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result;
    }

    /** Synchronous wrapper around {@link Database#deleteStudent}. */
    public static boolean deleteStudent(Database database, String id) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Student> result = new Result<>();
        database.deleteStudent(id, (success, value) -> {
            result.success = success;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.success;
    }

    /* ------------------------------- Enrollments ------------------------------- */

    /** Synchronous wrapper around {@link Database#loadEnrollmentById}. */
    public static Enrollment loadEnrollmentById(Database database, String id) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Enrollment> result = new Result<>();
        database.loadEnrollmentById(id, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.success ? result.value : null;
    }

    /** Synchronous wrapper around {@link Database#loadEnrollmentsForStudent}. */
    public static ArrayList<Enrollment> loadEnrollmentsForStudent(Database database, String studentId) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<ArrayList<Enrollment>> result = new Result<>();
        database.loadEnrollmentsForStudent(studentId, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#saveEnrollment}. */
    public static Result<Enrollment> saveEnrollment(Database database, Enrollment enrollment) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Enrollment> result = new Result<>();
        database.saveEnrollment(enrollment, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result;
    }

    /** Synchronous wrapper around {@link Database#deleteEnrollment}. */
    public static boolean deleteEnrollment(Database database, String enrollmentId) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Enrollment> result = new Result<>();
        database.deleteEnrollment(enrollmentId, (success, value) -> {
            result.success = success;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.success;
    }

    /** Synchronous wrapper around {@link Database#updateEnrollmentGrades} — sets the raw point total; callers must also record a {@link PointsHistory} row (see {@link #savePointsHistory}) to keep the audit trail meaningful, this method alone doesn't. */
    public static Result<Enrollment> updateEnrollmentGrades(Database database, String enrollmentId, int newGrades) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Enrollment> result = new Result<>();
        database.updateEnrollmentGrades(enrollmentId, newGrades, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result;
    }

    /* ----------------------------- Points history ------------------------------ */

    /** Synchronous wrapper around {@link Database#savePointsHistory}. */
    public static Result<PointsHistory> savePointsHistory(Database database, PointsHistory history) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<PointsHistory> result = new Result<>();
        database.savePointsHistory(history, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result;
    }

    /** Synchronous wrapper around {@link Database#loadHistoryForEnrollment}. */
    public static ArrayList<PointsHistory> loadHistoryForEnrollment(Database database, String enrollmentId) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<ArrayList<PointsHistory>> result = new Result<>();
        database.loadHistoryForEnrollment(enrollmentId, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.value;
    }

    /** One {@link Database} load call, parameterised only by the listener — lets {@link #load} wrap any of them without repeating the latch plumbing each time. */
    private interface LoadCall<T> {
        void run(IDatabaseOnLoad<T> listener);
    }

    /** Runs {@code call} and blocks (bounded by {@link #TIMEOUT_SECONDS}) until its listener fires; the returned {@link Result} is unsuccessful with a null value if it never did. */
    private static <T> Result<T> load(LoadCall<T> call) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<T> result = new Result<>();
        call.run((success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result;
    }

    /* ------------------------------- QR codes ---------------------------------- */

    /** Synchronous wrapper around {@link Database#loadStudentByQrCode}; null if the code matches nobody (including a retired, regenerated one). */
    public static Student loadStudentByQrCode(Database database, String code) {
        Result<Student> result = load(l -> database.loadStudentByQrCode(code, l));
        return result.success ? result.value : null;
    }

    /** Synchronous wrapper around {@link Database#regenerateQrToken}; null if the student doesn't exist. */
    public static Student regenerateQrToken(Database database, String studentId) {
        Result<Student> result = load(l -> database.regenerateQrToken(studentId, l));
        return result.success ? result.value : null;
    }

    /* ----------------------- Atomic points changes ----------------------------- */

    /** Synchronous wrapper around {@link Database#awardPoints}; null if the enrollment doesn't exist or delta is zero. */
    public static PointsAward awardPoints(Database database, String enrollmentId, int delta, String note, String tag) {
        Result<PointsAward> result = load(l -> database.awardPoints(enrollmentId, delta, note, tag, l));
        return result.success ? result.value : null;
    }

    /** Synchronous wrapper around {@link Database#awardPointsBatch}; null if the batch was rejected (nothing was changed). */
    public static ArrayList<PointsAward> awardPointsBatch(Database database, List<String> enrollmentIds, int delta, String note, String tag) {
        Result<ArrayList<PointsAward>> result = load(l -> database.awardPointsBatch(enrollmentIds, delta, note, tag, l));
        return result.success ? result.value : null;
    }

    /** Synchronous wrapper around {@link Database#revertPointsHistory}; the result's {@code error} is null on success, otherwise a stable code (NOT_FOUND, IS_REVERSAL, ALREADY_REVERTED, CLOSED_PERIOD, FAILED). */
    public static RevertResult revertPointsHistory(Database database, String historyId, PointsHistory replacement) {
        Result<RevertResult> result = load(l -> database.revertPointsHistory(historyId, replacement, l));
        if (result.value != null) return result.value;
        RevertResult failed = new RevertResult();
        failed.error = "FAILED";
        return failed;
    }

    /** Synchronous wrapper around {@link Database#loadRecentNotes}. */
    public static ArrayList<String> loadRecentNotes(Database database, int limit) {
        Result<ArrayList<String>> result = load(l -> database.loadRecentNotes(limit, l));
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#loadRecentTags}. */
    public static ArrayList<String> loadRecentTags(Database database, int limit) {
        Result<ArrayList<String>> result = load(l -> database.loadRecentTags(limit, l));
        return result.value;
    }

    /* ------------------------------- Attendance -------------------------------- */

    /** Synchronous wrapper around {@link Database#startAttendance}; null on failure. */
    public static ArrayList<Attendance> startAttendance(Database database, String classGroupId, String day) {
        Result<ArrayList<Attendance>> result = load(l -> database.startAttendance(classGroupId, day, l));
        return result.success ? result.value : null;
    }

    /** Synchronous wrapper around {@link Database#setAttendance}; null if any mark was invalid (nothing was changed). */
    public static ArrayList<Attendance> setAttendance(Database database, String classGroupId, String day, List<Attendance> marks) {
        Result<ArrayList<Attendance>> result = load(l -> database.setAttendance(classGroupId, day, marks, l));
        return result.success ? result.value : null;
    }

    /** Synchronous wrapper around {@link Database#loadAttendanceForDay}. */
    public static ArrayList<Attendance> loadAttendanceForDay(Database database, String classGroupId, String day) {
        Result<ArrayList<Attendance>> result = load(l -> database.loadAttendanceForDay(classGroupId, day, l));
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#loadAttendanceForClassGroup}. */
    public static ArrayList<Attendance> loadAttendanceForClassGroup(Database database, String classGroupId) {
        Result<ArrayList<Attendance>> result = load(l -> database.loadAttendanceForClassGroup(classGroupId, l));
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#loadAttendanceForStudent}. */
    public static ArrayList<Attendance> loadAttendanceForStudent(Database database, String studentId) {
        Result<ArrayList<Attendance>> result = load(l -> database.loadAttendanceForStudent(studentId, l));
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#deleteAttendanceDay}; returns how many records were removed. */
    public static int deleteAttendanceDay(Database database, String classGroupId, String day) {
        Result<Integer> result = load(l -> database.deleteAttendanceDay(classGroupId, day, l));
        return result.value == null ? 0 : result.value;
    }

    /* --------------------------- Class groups & students ------------------------ */

    /** Synchronous wrapper around {@link Database#loadClassGroup}; null if it doesn't exist. */
    public static ClassGroup loadClassGroup(Database database, String id) {
        Result<ClassGroup> result = load(l -> database.loadClassGroup(id, l));
        return result.success ? result.value : null;
    }

    /** Synchronous wrapper around {@link Database#loadStudentsForClassGroup}. */
    public static ArrayList<Student> loadStudentsForClassGroup(Database database, String classGroupId) {
        Result<ArrayList<Student>> result = load(l -> database.loadStudentsForClassGroup(classGroupId, l));
        return result.value;
    }

    /* --------------------------------- Periods ---------------------------------- */

    /** Synchronous wrapper around {@link Database#loadPeriodsForDiscipline} — every period closed for the discipline, newest first, reopened ones included. */
    public static ArrayList<Period> loadPeriodsForDiscipline(Database database, String disciplineId) {
        Result<ArrayList<Period>> result = load(l -> database.loadPeriodsForDiscipline(disciplineId, l));
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#loadPeriodSnapshots}. */
    public static ArrayList<PeriodSnapshot> loadPeriodSnapshots(Database database, String periodId) {
        Result<ArrayList<PeriodSnapshot>> result = load(l -> database.loadPeriodSnapshots(periodId, l));
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#loadPeriod}. */
    public static Period loadPeriod(Database database, String periodId) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Period> result = new Result<>();
        database.loadPeriod(periodId, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.success ? result.value : null;
    }

    /** Synchronous wrapper around {@link Database#loadPeriodSnapshotsForStudent}. */
    public static ArrayList<PeriodSnapshot> loadPeriodSnapshotsForStudent(Database database, String studentId) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<ArrayList<PeriodSnapshot>> result = new Result<>();
        database.loadPeriodSnapshotsForStudent(studentId, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.value;
    }

    /* ------------------------------- Disciplines -------------------------------- */

    /** Synchronous wrapper around {@link Database#loadAllDisciplines}. */
    public static ArrayList<Discipline> loadAllDisciplines(Database database) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<ArrayList<Discipline>> result = new Result<>();
        database.loadAllDisciplines((success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#saveDiscipline}. */
    public static Result<Discipline> saveDiscipline(Database database, Discipline discipline) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Discipline> result = new Result<>();
        database.saveDiscipline(discipline, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result;
    }

    /** Synchronous wrapper around {@link Database#deleteDiscipline} — {@code result.error} carries a machine-readable reason (e.g. "HAS_GROUPS") when deletion is blocked, for the web API to surface as a 409 body. */
    public static Result<Void> deleteDiscipline(Database database, String id) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Void> result = new Result<>();
        database.deleteDiscipline(id, (success, error) -> {
            result.success = success;
            result.error = error;
            latch.countDown();
        });
        awaitLatch(latch);
        return result;
    }

    /* ------------------------------- Class groups -------------------------------- */

    /** Synchronous wrapper around {@link Database#loadAllClassGroups}. */
    public static ArrayList<ClassGroup> loadAllClassGroups(Database database) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<ArrayList<ClassGroup>> result = new Result<>();
        database.loadAllClassGroups((success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#loadClassGroupsForDiscipline}. */
    public static ArrayList<ClassGroup> loadClassGroupsForDiscipline(Database database, String disciplineId) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<ArrayList<ClassGroup>> result = new Result<>();
        database.loadClassGroupsForDiscipline(disciplineId, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#saveClassGroup}. */
    public static Result<ClassGroup> saveClassGroup(Database database, ClassGroup group) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<ClassGroup> result = new Result<>();
        database.saveClassGroup(group, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result;
    }

    /** Synchronous wrapper around {@link Database#deleteClassGroup} — see {@link #deleteDiscipline} for the {@code result.error} convention. */
    public static Result<Void> deleteClassGroup(Database database, String id) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Void> result = new Result<>();
        database.deleteClassGroup(id, (success, error) -> {
            result.success = success;
            result.error = error;
            latch.countDown();
        });
        awaitLatch(latch);
        return result;
    }

    /* ---------------------------------- Goals ------------------------------------ */

    /** Synchronous wrapper around {@link Database#loadGoalsForDiscipline}. */
    public static ArrayList<Goal> loadGoalsForDiscipline(Database database, String disciplineId) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<ArrayList<Goal>> result = new Result<>();
        database.loadGoalsForDiscipline(disciplineId, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#saveGoal}. */
    public static Result<Goal> saveGoal(Database database, Goal goal) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Goal> result = new Result<>();
        database.saveGoal(goal, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result;
    }

    /** Synchronous wrapper around {@link Database#deleteGoal}. */
    public static boolean deleteGoal(Database database, String id) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Goal> result = new Result<>();
        database.deleteGoal(id, (success, value) -> {
            result.success = success;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.success;
    }

    /* ---------------------------- Export / import --------------------------------- */

    /** Synchronous wrapper around {@link Database#loadExportData}. */
    public static ArrayList<StudentExportData> loadExportData(Database database, List<String> studentIds) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<ArrayList<StudentExportData>> result = new Result<>();
        database.loadExportData(studentIds, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#importExportData} — a bulk overwrite; callers (the web API's import endpoint) should snapshot first via {@link #createFullSnapshot}. */
    public static boolean importExportData(Database database, List<StudentExportData> data) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Void> result = new Result<>();
        database.importExportData(data, (success, error) -> {
            result.success = success;
            result.error = error;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.success;
    }

    /** Synchronous wrapper around {@link Database#resolveCsvRows} — matches raw CSV rows against existing students before anything is committed. */
    public static CsvImportPlan resolveCsvRows(Database database, List<CsvStudentRow> rows) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<CsvImportPlan> result = new Result<>();
        database.resolveCsvRows(rows, (success, value) -> {
            result.success = success;
            result.value = value;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.value;
    }

    /** Synchronous wrapper around {@link Database#importCsvRows} — the actual commit step after {@link #resolveCsvRows} has been reviewed/confirmed. */
    public static boolean importCsvRows(Database database, List<ResolvedCsvRow> rows) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Void> result = new Result<>();
        database.importCsvRows(rows, (success, error) -> {
            result.success = success;
            result.error = error;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.success;
    }

    /* ------------------------------------ Backup ----------------------------------- */

    /** Synchronous wrapper around {@link DbBackup#createFullSnapshot}, used as the safety net taken automatically right before a bulk import commits. */
    public static boolean createFullSnapshot(Context context, Database database, boolean manual) {
        assertNotMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        Result<Void> result = new Result<>();
        DbBackup.createFullSnapshot(context, database, manual, (success, error) -> {
            result.success = success;
            result.error = error;
            latch.countDown();
        });
        awaitLatch(latch);
        return result.success;
    }
}
