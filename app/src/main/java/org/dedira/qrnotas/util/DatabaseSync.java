package org.dedira.qrnotas.util;

import android.content.Context;
import android.os.Looper;

import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.CsvImportPlan;
import org.dedira.qrnotas.model.CsvStudentRow;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.ResolvedCsvRow;
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
 */
public class DatabaseSync {

    private static final long TIMEOUT_SECONDS = 5;

    public static final class Result<T> {
        public boolean success;
        public T value;
        public String error;
    }

    private DatabaseSync() {
    }

    private static void assertNotMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("DatabaseSync must not be called from the main thread");
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* -------------------------------- Students -------------------------------- */

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

    /* ------------------------------- Disciplines -------------------------------- */

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
