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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@link DatabaseSync} asserts it is never called from the main thread (it would deadlock: the
 * {@link Database} callback it blocks on is only ever posted to the main thread). So every call
 * here runs on a real background thread while this test's thread — which Robolectric treats as
 * "the main thread" — concurrently pumps the paused main {@link Looper} until the worker's Future
 * completes.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseSyncTest {

    private Database database;
    private ExecutorService worker;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        database = new Database(context);
        worker = Executors.newSingleThreadExecutor();
    }

    private <T> T runOnWorker(Callable<T> call) throws Exception {
        Future<T> future = worker.submit(call);
        long deadline = System.currentTimeMillis() + 5000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(2);
        }
        return future.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void assertNotMainThread_throwsWhenCalledDirectlyFromTestThread() {
        assertThrows(IllegalStateException.class, () -> DatabaseSync.loadAllStudents(database));
    }

    @Test
    public void saveStudent_andLoadStudent_roundTrip() throws Exception {
        Student student = new Student();
        student.name = "André";

        DatabaseSync.Result<Student> saved = runOnWorker(() -> DatabaseSync.saveStudent(database, student));
        assertTrue(saved.success);
        assertTrue(saved.value.id != null);

        Student reloaded = runOnWorker(() -> DatabaseSync.loadStudent(database, saved.value.id));
        assertEquals("André", reloaded.name);
    }

    @Test
    public void loadStudent_missingReturnsNull() throws Exception {
        Student result = runOnWorker(() -> DatabaseSync.loadStudent(database, "nope"));
        assertNull(result);
    }

    @Test
    public void loadAllStudents_returnsSavedStudents() throws Exception {
        Student student = new Student();
        student.name = "André";
        runOnWorker(() -> DatabaseSync.saveStudent(database, student));

        ArrayList<Student> all = runOnWorker(() -> DatabaseSync.loadAllStudents(database));
        assertEquals(1, all.size());
    }

    @Test
    public void deleteStudent_removesRow() throws Exception {
        Student student = new Student();
        student.name = "André";
        DatabaseSync.Result<Student> saved = runOnWorker(() -> DatabaseSync.saveStudent(database, student));

        boolean deleted = runOnWorker(() -> DatabaseSync.deleteStudent(database, saved.value.id));
        assertTrue(deleted);

        ArrayList<Student> all = runOnWorker(() -> DatabaseSync.loadAllStudents(database));
        assertTrue(all.isEmpty());
    }

    @Test
    public void enrollmentAndPointsHistoryBridgeMethods() throws Exception {
        Student student = new Student();
        student.name = "André";
        DatabaseSync.Result<Student> savedStudent = runOnWorker(() -> DatabaseSync.saveStudent(database, student));

        Discipline discipline = new Discipline();
        discipline.name = "BDI";
        DatabaseSync.Result<Discipline> savedDiscipline = runOnWorker(() -> DatabaseSync.saveDiscipline(database, discipline));

        ClassGroup group = new ClassGroup();
        group.disciplineId = savedDiscipline.value.id;
        group.name = "A";
        DatabaseSync.Result<ClassGroup> savedGroup = runOnWorker(() -> DatabaseSync.saveClassGroup(database, group));

        Enrollment enrollment = new Enrollment();
        enrollment.studentId = savedStudent.value.id;
        enrollment.classGroupId = savedGroup.value.id;
        DatabaseSync.Result<Enrollment> savedEnrollment = runOnWorker(() -> DatabaseSync.saveEnrollment(database, enrollment));
        assertTrue(savedEnrollment.success);

        DatabaseSync.Result<Enrollment> updated = runOnWorker(() ->
                DatabaseSync.updateEnrollmentGrades(database, savedEnrollment.value.id, 22));
        assertEquals(22, updated.value.grades);

        Enrollment loaded = runOnWorker(() -> DatabaseSync.loadEnrollmentById(database, savedEnrollment.value.id));
        assertEquals(22, loaded.grades);

        ArrayList<Enrollment> forStudent = runOnWorker(() ->
                DatabaseSync.loadEnrollmentsForStudent(database, savedStudent.value.id));
        assertEquals(1, forStudent.size());

        PointsHistory history = new PointsHistory();
        history.enrollmentId = savedEnrollment.value.id;
        history.pointsDelta = 5;
        history.createdAt = System.currentTimeMillis();
        DatabaseSync.Result<PointsHistory> savedHistory = runOnWorker(() -> DatabaseSync.savePointsHistory(database, history));
        assertTrue(savedHistory.success);

        ArrayList<PointsHistory> loadedHistory = runOnWorker(() ->
                DatabaseSync.loadHistoryForEnrollment(database, savedEnrollment.value.id));
        assertEquals(1, loadedHistory.size());

        boolean deleted = runOnWorker(() -> DatabaseSync.deleteEnrollment(database, savedEnrollment.value.id));
        assertTrue(deleted);
    }

    @Test
    public void disciplineAndClassGroupAndGoalBridgeMethods() throws Exception {
        Discipline discipline = new Discipline();
        discipline.name = "BDI";
        DatabaseSync.Result<Discipline> savedDiscipline = runOnWorker(() -> DatabaseSync.saveDiscipline(database, discipline));
        assertTrue(savedDiscipline.success);

        ArrayList<Discipline> allDisciplines = runOnWorker(() -> DatabaseSync.loadAllDisciplines(database));
        assertEquals(1, allDisciplines.size());

        ClassGroup group = new ClassGroup();
        group.disciplineId = savedDiscipline.value.id;
        group.name = "A";
        DatabaseSync.Result<ClassGroup> savedGroup = runOnWorker(() -> DatabaseSync.saveClassGroup(database, group));
        assertTrue(savedGroup.success);

        ArrayList<ClassGroup> allGroups = runOnWorker(() -> DatabaseSync.loadAllClassGroups(database));
        assertEquals(1, allGroups.size());

        ArrayList<ClassGroup> scopedGroups = runOnWorker(() ->
                DatabaseSync.loadClassGroupsForDiscipline(database, savedDiscipline.value.id));
        assertEquals(1, scopedGroups.size());

        Goal goal = new Goal();
        goal.disciplineId = savedDiscipline.value.id;
        goal.name = "R";
        goal.targetPoints = 10;
        DatabaseSync.Result<Goal> savedGoal = runOnWorker(() -> DatabaseSync.saveGoal(database, goal));
        assertTrue(savedGoal.success);

        ArrayList<Goal> goals = runOnWorker(() -> DatabaseSync.loadGoalsForDiscipline(database, savedDiscipline.value.id));
        assertEquals(1, goals.size());

        boolean goalDeleted = runOnWorker(() -> DatabaseSync.deleteGoal(database, savedGoal.value.id));
        assertTrue(goalDeleted);

        DatabaseSync.Result<Void> groupDeleteResult = runOnWorker(() -> DatabaseSync.deleteClassGroup(database, savedGroup.value.id));
        assertTrue(groupDeleteResult.success);

        DatabaseSync.Result<Void> disciplineDeleteResult = runOnWorker(() ->
                DatabaseSync.deleteDiscipline(database, savedDiscipline.value.id));
        assertTrue(disciplineDeleteResult.success);
    }

    @Test
    public void deleteDiscipline_blockedWhenClassGroupsExistReportsError() throws Exception {
        Discipline discipline = new Discipline();
        discipline.name = "BDI";
        DatabaseSync.Result<Discipline> savedDiscipline = runOnWorker(() -> DatabaseSync.saveDiscipline(database, discipline));

        ClassGroup group = new ClassGroup();
        group.disciplineId = savedDiscipline.value.id;
        group.name = "A";
        runOnWorker(() -> DatabaseSync.saveClassGroup(database, group));

        DatabaseSync.Result<Void> result = runOnWorker(() -> DatabaseSync.deleteDiscipline(database, savedDiscipline.value.id));
        assertFalse(result.success);
        assertEquals("HAS_GROUPS", result.error);
    }

    @Test
    public void exportAndImportBridgeMethods() throws Exception {
        StudentExportData data = new StudentExportData();
        data.studentId = "s1";
        data.studentName = "André";
        data.disciplineId = "d1";
        data.disciplineName = "BDI";
        data.classGroupId = "c1";
        data.classGroupName = "A";
        data.points = 22;
        List<StudentExportData> list = Collections.singletonList(data);

        boolean imported = runOnWorker(() -> DatabaseSync.importExportData(database, list));
        assertTrue(imported);

        ArrayList<StudentExportData> exported = runOnWorker(() -> DatabaseSync.loadExportData(database, null));
        assertEquals(1, exported.size());
        assertEquals("André", exported.get(0).studentName);
    }

    @Test
    public void csvBridgeMethods() throws Exception {
        Discipline discipline = new Discipline();
        discipline.name = "BDI";
        DatabaseSync.Result<Discipline> savedDiscipline = runOnWorker(() -> DatabaseSync.saveDiscipline(database, discipline));

        ClassGroup group = new ClassGroup();
        group.disciplineId = savedDiscipline.value.id;
        group.name = "A";
        DatabaseSync.Result<ClassGroup> savedGroup = runOnWorker(() -> DatabaseSync.saveClassGroup(database, group));

        List<org.dedira.qrnotas.model.CsvStudentRow> rows = Collections.singletonList(
                new org.dedira.qrnotas.model.CsvStudentRow(2, "André", "BDI", "A"));
        org.dedira.qrnotas.model.CsvImportPlan plan = runOnWorker(() -> DatabaseSync.resolveCsvRows(database, rows));
        assertEquals(1, plan.resolved.size());

        boolean imported = runOnWorker(() -> DatabaseSync.importCsvRows(database, plan.resolved));
        assertTrue(imported);

        ArrayList<Student> students = runOnWorker(() -> DatabaseSync.loadAllStudents(database));
        assertEquals(1, students.size());
    }

    @Test
    public void createFullSnapshot_bridgeMethod() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        boolean success = runOnWorker(() -> DatabaseSync.createFullSnapshot(context, database, true));
        assertTrue(success);
    }
}
