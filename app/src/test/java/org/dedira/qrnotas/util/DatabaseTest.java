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
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.CsvImportPlan;
import org.dedira.qrnotas.model.CsvRowError;
import org.dedira.qrnotas.model.CsvStudentRow;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.ResolvedCsvRow;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class DatabaseTest {

    /** {@link Database} always answers on a real background executor thread, then posts the
     * callback to the (Robolectric-paused) main {@link Looper} — so every call here is driven
     * through this holder, polling {@code idle()} on the main looper until the post lands. */
    private static class Result<T> {
        volatile boolean done;
        boolean success;
        T value;
    }

    private static <T> void idle(Result<T> r) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!r.done && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(2);
        }
        assertTrue("timed out waiting for Database callback", r.done);
    }

    private Database database;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        database = new Database(context);
    }

    /* -------------------------------- Students -------------------------------- */

    private Result<Student> saveStudent(Student s) throws InterruptedException {
        Result<Student> r = new Result<>();
        database.saveStudent(s, (success, value) -> {
            r.success = success;
            r.value = value;
            r.done = true;
        });
        idle(r);
        return r;
    }

    private Student newStudent(String name) throws InterruptedException {
        Student s = new Student();
        s.name = name;
        return saveStudent(s).value;
    }

    @Test
    public void saveStudent_assignsIdWhenMissing() throws InterruptedException {
        Result<Student> r = saveStudent(makeStudent(null, "André", null));
        assertTrue(r.success);
        assertTrue(r.value.id != null && !r.value.id.isEmpty());
    }

    @Test
    public void saveStudent_thenLoadStudent_roundTrips() throws InterruptedException {
        Student saved = newStudent("André");

        Result<Student> r = new Result<>();
        database.loadStudent(saved.id, (success, value) -> {
            r.success = success;
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertTrue(r.success);
        assertEquals("André", r.value.name);
    }

    @Test
    public void loadStudent_missingIdReturnsFailure() throws InterruptedException {
        Result<Student> r = new Result<>();
        database.loadStudent("does-not-exist", (success, value) -> {
            r.success = success;
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertFalse(r.success);
        assertNull(r.value);
    }

    @Test
    public void loadAllStudents_sortedByName() throws InterruptedException {
        newStudent("Zeca");
        newStudent("André");

        Result<ArrayList<Student>> r = new Result<>();
        database.loadAllStudents((success, value) -> {
            r.success = success;
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(2, r.value.size());
        assertEquals("André", r.value.get(0).name);
        assertEquals("Zeca", r.value.get(1).name);
    }

    @Test
    public void saveStudent_sameIdReplacesExistingRow() throws InterruptedException {
        Student saved = newStudent("André");
        saved.name = "André Furlan";
        saveStudent(saved);

        Result<ArrayList<Student>> r = new Result<>();
        database.loadAllStudents((success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(1, r.value.size());
        assertEquals("André Furlan", r.value.get(0).name);
    }

    @Test
    public void deleteStudent_removesStudentAndCascadesEnrollmentsAndHistory() throws InterruptedException {
        Student student = newStudent("André");
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Enrollment enrollment = saveEnrollment(student.id, group.id).value;
        savePointsHistory(enrollment.id, 5, "note");

        Result<Student> r = new Result<>();
        database.deleteStudent(student.id, (success, value) -> {
            r.success = success;
            r.done = true;
        });
        idle(r);
        assertTrue(r.success);

        Result<ArrayList<Enrollment>> enrollmentsAfter = new Result<>();
        database.loadEnrollmentsForStudent(student.id, (success, value) -> {
            enrollmentsAfter.value = value;
            enrollmentsAfter.done = true;
        });
        idle(enrollmentsAfter);
        assertTrue(enrollmentsAfter.value.isEmpty());

        Result<ArrayList<PointsHistory>> historyAfter = new Result<>();
        database.loadHistoryForEnrollment(enrollment.id, (success, value) -> {
            historyAfter.value = value;
            historyAfter.done = true;
        });
        idle(historyAfter);
        assertTrue(historyAfter.value.isEmpty());
    }

    @Test
    public void deleteStudent_missingIdReportsFailure() throws InterruptedException {
        Result<Student> r = new Result<>();
        database.deleteStudent("nope", (success, value) -> {
            r.success = success;
            r.done = true;
        });
        idle(r);
        assertFalse(r.success);
    }

    @Test
    public void loadStudentsForClassGroup_returnsOnlyEnrolledStudents() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Student inGroup = newStudent("André");
        newStudent("Not Enrolled");
        saveEnrollment(inGroup.id, group.id);

        Result<ArrayList<Student>> r = new Result<>();
        database.loadStudentsForClassGroup(group.id, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(1, r.value.size());
        assertEquals("André", r.value.get(0).name);
    }

    /* ----------------------------- Enrollments ------------------------------ */

    private Result<Enrollment> saveEnrollment(String studentId, String classGroupId) throws InterruptedException {
        Enrollment e = new Enrollment();
        e.studentId = studentId;
        e.classGroupId = classGroupId;
        Result<Enrollment> r = new Result<>();
        database.saveEnrollment(e, (success, value) -> {
            r.success = success;
            r.value = value;
            r.done = true;
        });
        idle(r);
        return r;
    }

    @Test
    public void saveEnrollment_assignsIdAndDefaultsGradesToZero() throws InterruptedException {
        Student student = newStudent("André");
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");

        Result<Enrollment> r = saveEnrollment(student.id, group.id);
        assertTrue(r.success);
        assertTrue(r.value.id != null && !r.value.id.isEmpty());
        assertEquals(0, r.value.grades);
    }

    @Test
    public void updateEnrollmentGrades_changesGradesAndReturnsUpdatedRow() throws InterruptedException {
        Student student = newStudent("André");
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Enrollment enrollment = saveEnrollment(student.id, group.id).value;

        Result<Enrollment> r = new Result<>();
        database.updateEnrollmentGrades(enrollment.id, 15, (success, value) -> {
            r.success = success;
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertTrue(r.success);
        assertEquals(15, r.value.grades);
    }

    @Test
    public void updateEnrollmentGrades_missingIdFails() throws InterruptedException {
        Result<Enrollment> r = new Result<>();
        database.updateEnrollmentGrades("nope", 15, (success, value) -> {
            r.success = success;
            r.done = true;
        });
        idle(r);
        assertFalse(r.success);
    }

    @Test
    public void deleteEnrollment_removesRowAndItsHistory() throws InterruptedException {
        Student student = newStudent("André");
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Enrollment enrollment = saveEnrollment(student.id, group.id).value;
        savePointsHistory(enrollment.id, 5, "note");

        Result<Enrollment> r = new Result<>();
        database.deleteEnrollment(enrollment.id, (success, value) -> {
            r.success = success;
            r.done = true;
        });
        idle(r);
        assertTrue(r.success);

        Result<ArrayList<PointsHistory>> historyAfter = new Result<>();
        database.loadHistoryForEnrollment(enrollment.id, (success, value) -> {
            historyAfter.value = value;
            historyAfter.done = true;
        });
        idle(historyAfter);
        assertTrue(historyAfter.value.isEmpty());
    }

    @Test
    public void loadEnrollment_findsByStudentAndClassGroup() throws InterruptedException {
        Student student = newStudent("André");
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        saveEnrollment(student.id, group.id);

        Result<Enrollment> r = new Result<>();
        database.loadEnrollment(student.id, group.id, (success, value) -> {
            r.success = success;
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertTrue(r.success);
        assertEquals(student.id, r.value.studentId);
    }

    @Test
    public void loadEnrollmentForStudentInDiscipline_joinsThroughClassGroup() throws InterruptedException {
        Student student = newStudent("André");
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Enrollment enrollment = saveEnrollment(student.id, group.id).value;

        Result<Enrollment> r = new Result<>();
        database.loadEnrollmentForStudentInDiscipline(student.id, discipline.id, (success, value) -> {
            r.success = success;
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertTrue(r.success);
        assertEquals(enrollment.id, r.value.id);
    }

    @Test
    public void loadEnrollmentForStudentInDiscipline_noMatchFails() throws InterruptedException {
        Student student = newStudent("André");
        Discipline discipline = saveDiscipline("BDI");

        Result<Enrollment> r = new Result<>();
        database.loadEnrollmentForStudentInDiscipline(student.id, discipline.id, (success, value) -> {
            r.success = success;
            r.done = true;
        });
        idle(r);

        assertFalse(r.success);
    }

    /* --------------------------- Points history ----------------------------- */

    private void savePointsHistory(String enrollmentId, int delta, String note) throws InterruptedException {
        PointsHistory h = new PointsHistory();
        h.enrollmentId = enrollmentId;
        h.pointsDelta = delta;
        h.note = note;
        h.createdAt = System.currentTimeMillis();
        Result<PointsHistory> r = new Result<>();
        database.savePointsHistory(h, (success, value) -> {
            r.success = success;
            r.done = true;
        });
        idle(r);
    }

    @Test
    public void loadHistoryForEnrollment_mostRecentFirst() throws InterruptedException {
        Student student = newStudent("André");
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Enrollment enrollment = saveEnrollment(student.id, group.id).value;

        PointsHistory h1 = new PointsHistory();
        h1.enrollmentId = enrollment.id;
        h1.pointsDelta = 5;
        h1.createdAt = 1000;
        Result<PointsHistory> r1 = new Result<>();
        database.savePointsHistory(h1, (success, value) -> {
            r1.done = true;
        });
        idle(r1);

        PointsHistory h2 = new PointsHistory();
        h2.enrollmentId = enrollment.id;
        h2.pointsDelta = 3;
        h2.createdAt = 2000;
        Result<PointsHistory> r2 = new Result<>();
        database.savePointsHistory(h2, (success, value) -> {
            r2.done = true;
        });
        idle(r2);

        Result<ArrayList<PointsHistory>> r = new Result<>();
        database.loadHistoryForEnrollment(enrollment.id, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(2, r.value.size());
        assertEquals(3, r.value.get(0).pointsDelta);
        assertEquals(5, r.value.get(1).pointsDelta);
    }

    @Test
    public void loadRecentNotes_distinctMostRecentFirstRespectsLimitAndBlanks() throws InterruptedException {
        Student student = newStudent("André");
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Enrollment enrollment = saveEnrollment(student.id, group.id).value;

        savePointsHistoryAt(enrollment.id, 1, "helped classmate", 1000);
        savePointsHistoryAt(enrollment.id, 1, "  ", 1500);
        savePointsHistoryAt(enrollment.id, 1, "attended class", 2000);
        savePointsHistoryAt(enrollment.id, 1, "helped classmate", 3000);

        Result<ArrayList<String>> r = new Result<>();
        database.loadRecentNotes(10, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(2, r.value.size());
        assertEquals("helped classmate", r.value.get(0));
        assertEquals("attended class", r.value.get(1));
    }

    private void savePointsHistoryAt(String enrollmentId, int delta, String note, long createdAt) throws InterruptedException {
        PointsHistory h = new PointsHistory();
        h.enrollmentId = enrollmentId;
        h.pointsDelta = delta;
        h.note = note;
        h.createdAt = createdAt;
        Result<PointsHistory> r = new Result<>();
        database.savePointsHistory(h, (success, value) -> r.done = true);
        idle(r);
    }

    /* ---------------------------- Disciplines ------------------------------ */

    private Discipline saveDiscipline(String name) throws InterruptedException {
        Discipline d = new Discipline();
        d.name = name;
        Result<Discipline> r = new Result<>();
        database.saveDiscipline(d, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);
        return r.value;
    }

    @Test
    public void loadAllDisciplines_sortedByName() throws InterruptedException {
        saveDiscipline("Mobile II");
        saveDiscipline("BDI");

        Result<ArrayList<Discipline>> r = new Result<>();
        database.loadAllDisciplines((success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(2, r.value.size());
        assertEquals("BDI", r.value.get(0).name);
    }

    @Test
    public void deleteDiscipline_succeedsWhenNoClassGroups() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");

        Result<Void> r = new Result<>();
        String[] error = {null};
        database.deleteDiscipline(discipline.id, (success, err) -> {
            r.success = success;
            error[0] = err;
            r.done = true;
        });
        idle(r);

        assertTrue(r.success);
        assertNull(error[0]);
    }

    @Test
    public void deleteDiscipline_blockedWhenClassGroupsExist() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        saveClassGroup(discipline.id, "A");

        Result<Void> r = new Result<>();
        String[] error = {null};
        database.deleteDiscipline(discipline.id, (success, err) -> {
            r.success = success;
            error[0] = err;
            r.done = true;
        });
        idle(r);

        assertFalse(r.success);
        assertEquals("HAS_GROUPS", error[0]);
    }

    /* --------------------------- Class groups ------------------------------ */

    private ClassGroup saveClassGroup(String disciplineId, String name) throws InterruptedException {
        ClassGroup g = new ClassGroup();
        g.disciplineId = disciplineId;
        g.name = name;
        Result<ClassGroup> r = new Result<>();
        database.saveClassGroup(g, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);
        return r.value;
    }

    @Test
    public void loadAllClassGroups_andLoadClassGroupsForDiscipline() throws InterruptedException {
        Discipline d1 = saveDiscipline("BDI");
        Discipline d2 = saveDiscipline("Mobile II");
        saveClassGroup(d1.id, "A");
        saveClassGroup(d2.id, "B");

        Result<ArrayList<ClassGroup>> all = new Result<>();
        database.loadAllClassGroups((success, value) -> {
            all.value = value;
            all.done = true;
        });
        idle(all);
        assertEquals(2, all.value.size());

        Result<ArrayList<ClassGroup>> scoped = new Result<>();
        database.loadClassGroupsForDiscipline(d1.id, (success, value) -> {
            scoped.value = value;
            scoped.done = true;
        });
        idle(scoped);
        assertEquals(1, scoped.value.size());
        assertEquals("A", scoped.value.get(0).name);
    }

    @Test
    public void deleteClassGroup_succeedsWhenEmpty() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");

        Result<Void> r = new Result<>();
        database.deleteClassGroup(group.id, (success, err) -> {
            r.success = success;
            r.done = true;
        });
        idle(r);
        assertTrue(r.success);
    }

    @Test
    public void deleteClassGroup_blockedWhenStudentsEnrolled() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Student student = newStudent("André");
        saveEnrollment(student.id, group.id);

        Result<Void> r = new Result<>();
        String[] error = {null};
        database.deleteClassGroup(group.id, (success, err) -> {
            r.success = success;
            error[0] = err;
            r.done = true;
        });
        idle(r);

        assertFalse(r.success);
        assertEquals("HAS_STUDENTS", error[0]);
    }

    /* ------------------------------- Goals ---------------------------------- */

    @Test
    public void saveGoal_andLoadGoalsForDiscipline_sortedByTarget() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        saveGoal(discipline.id, "MB", 30);
        saveGoal(discipline.id, "R", 10);

        Result<ArrayList<Goal>> r = new Result<>();
        database.loadGoalsForDiscipline(discipline.id, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(2, r.value.size());
        assertEquals("R", r.value.get(0).name);
        assertEquals("MB", r.value.get(1).name);
    }

    private Goal saveGoal(String disciplineId, String name, int targetPoints) throws InterruptedException {
        Goal g = new Goal();
        g.disciplineId = disciplineId;
        g.name = name;
        g.targetPoints = targetPoints;
        Result<Goal> r = new Result<>();
        database.saveGoal(g, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);
        return r.value;
    }

    @Test
    public void deleteGoal_removesIt() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        Goal goal = saveGoal(discipline.id, "R", 10);

        Result<Goal> r = new Result<>();
        database.deleteGoal(goal.id, (success, value) -> {
            r.success = success;
            r.done = true;
        });
        idle(r);
        assertTrue(r.success);

        Result<ArrayList<Goal>> after = new Result<>();
        database.loadGoalsForDiscipline(discipline.id, (success, value) -> {
            after.value = value;
            after.done = true;
        });
        idle(after);
        assertTrue(after.value.isEmpty());
    }

    /* ------------------------------ Export/import ---------------------------- */

    @Test
    public void loadExportData_buildsRowsWithGoalsAndHistory() throws InterruptedException {
        Student student = newStudent("André");
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Enrollment enrollment = saveEnrollment(student.id, group.id).value;
        database.updateEnrollmentGrades(enrollment.id, 22, (s, v) -> {
        });
        saveGoal(discipline.id, "R", 10);
        savePointsHistory(enrollment.id, 5, "note");

        Result<ArrayList<StudentExportData>> r = new Result<>();
        database.loadExportData(null, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(1, r.value.size());
        StudentExportData data = r.value.get(0);
        assertEquals("André", data.studentName);
        assertEquals("BDI", data.disciplineName);
        assertEquals("A", data.classGroupName);
        assertEquals(1, data.goals.size());
        assertEquals(1, data.history.size());
    }

    @Test
    public void loadExportData_filtersByStudentIds() throws InterruptedException {
        Student keep = newStudent("André");
        Student drop = newStudent("Beatriz");
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        saveEnrollment(keep.id, group.id);
        saveEnrollment(drop.id, group.id);

        Result<ArrayList<StudentExportData>> r = new Result<>();
        database.loadExportData(Collections.singletonList(keep.id), (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(1, r.value.size());
        assertEquals("André", r.value.get(0).studentName);
    }

    @Test
    public void loadExportData_emptyStudentIdsReturnsEmptyList() throws InterruptedException {
        newStudent("André");

        Result<ArrayList<StudentExportData>> r = new Result<>();
        database.loadExportData(new ArrayList<>(), (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertTrue(r.value.isEmpty());
    }

    @Test
    public void importExportData_isIdempotentWhenReimportingSameIds() throws InterruptedException {
        StudentExportData data = new StudentExportData();
        data.studentId = "s1";
        data.studentName = "André";
        data.disciplineId = "d1";
        data.disciplineName = "BDI";
        data.classGroupId = "c1";
        data.classGroupName = "A";
        data.points = 22;
        List<StudentExportData> list = Collections.singletonList(data);

        Result<Void> r1 = new Result<>();
        database.importExportData(list, (success, error) -> {
            r1.success = success;
            r1.done = true;
        });
        idle(r1);
        assertTrue(r1.success);

        Result<Void> r2 = new Result<>();
        database.importExportData(list, (success, error) -> {
            r2.success = success;
            r2.done = true;
        });
        idle(r2);
        assertTrue(r2.success);

        Result<ArrayList<Student>> students = new Result<>();
        database.loadAllStudents((success, value) -> {
            students.value = value;
            students.done = true;
        });
        idle(students);
        assertEquals(1, students.value.size());
    }

    /* ------------------------------ CSV import ------------------------------- */

    @Test
    public void resolveCsvRows_matchesExistingDisciplineAndClassGroup() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        saveClassGroup(discipline.id, "A");
        newStudent("André");

        List<CsvStudentRow> rows = Collections.singletonList(new CsvStudentRow(2, "André", "BDI", "A"));
        Result<CsvImportPlan> r = new Result<>();
        database.resolveCsvRows(rows, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(1, r.value.resolved.size());
        assertTrue(r.value.errors.isEmpty());
        assertFalse(r.value.resolved.get(0).isNewStudent);
    }

    @Test
    public void resolveCsvRows_newStudentNameGetsFreshId() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        saveClassGroup(discipline.id, "A");

        List<CsvStudentRow> rows = Collections.singletonList(new CsvStudentRow(2, "Beatriz", "BDI", "A"));
        Result<CsvImportPlan> r = new Result<>();
        database.resolveCsvRows(rows, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(1, r.value.resolved.size());
        assertTrue(r.value.resolved.get(0).isNewStudent);
    }

    @Test
    public void resolveCsvRows_unknownDisciplineBecomesError() throws InterruptedException {
        List<CsvStudentRow> rows = Collections.singletonList(new CsvStudentRow(2, "André", "Unknown", "A"));
        Result<CsvImportPlan> r = new Result<>();
        database.resolveCsvRows(rows, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertTrue(r.value.resolved.isEmpty());
        assertEquals(1, r.value.errors.size());
        CsvRowError error = r.value.errors.get(0);
        assertEquals(2, error.lineNumber);
        assertTrue(error.reason.contains("Unknown discipline"));
    }

    @Test
    public void resolveCsvRows_unknownClassGroupBecomesError() throws InterruptedException {
        saveDiscipline("BDI");
        List<CsvStudentRow> rows = Collections.singletonList(new CsvStudentRow(2, "André", "BDI", "Z"));
        Result<CsvImportPlan> r = new Result<>();
        database.resolveCsvRows(rows, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertTrue(r.value.resolved.isEmpty());
        assertEquals(1, r.value.errors.size());
        assertTrue(r.value.errors.get(0).reason.contains("Unknown class group"));
    }

    @Test
    public void importCsvRows_createsStudentsAndEnrollmentsWithoutResettingExistingGrades() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Student existing = newStudent("André");
        Enrollment enrollment = saveEnrollment(existing.id, group.id).value;
        database.updateEnrollmentGrades(enrollment.id, 42, (s, v) -> {
        });

        List<ResolvedCsvRow> rows = new ArrayList<>();
        rows.add(new ResolvedCsvRow(existing.id, "André", false, discipline.id, group.id));
        rows.add(new ResolvedCsvRow("new-student-id", "Beatriz", true, discipline.id, group.id));

        Result<Void> r = new Result<>();
        database.importCsvRows(rows, (success, error) -> {
            r.success = success;
            r.done = true;
        });
        idle(r);
        assertTrue(r.success);

        Result<ArrayList<Student>> students = new Result<>();
        database.loadAllStudents((success, value) -> {
            students.value = value;
            students.done = true;
        });
        idle(students);
        assertEquals(2, students.value.size());

        Result<Enrollment> reloaded = new Result<>();
        database.loadEnrollment(existing.id, group.id, (success, value) -> {
            reloaded.value = value;
            reloaded.done = true;
        });
        idle(reloaded);
        assertEquals(42, reloaded.value.grades);
    }

    /* ------------------------------ Backups ----------------------------------- */

    @Test
    public void createSnapshot_copiesDbFile() throws InterruptedException, java.io.IOException {
        newStudent("André");
        File dest = File.createTempFile("snapshot", ".db");
        dest.deleteOnExit();

        Result<Void> r = new Result<>();
        String[] error = {null};
        database.createSnapshot(dest, (success, err) -> {
            r.success = success;
            error[0] = err;
            r.done = true;
        });
        idle(r);

        assertTrue(r.success);
        assertNull(error[0]);
        assertTrue(dest.exists());
        assertTrue(dest.length() > 0);
    }

    private static Student makeStudent(String id, String name, String photoPath) {
        Student s = new Student();
        s.id = id;
        s.name = name;
        s.photoPath = photoPath;
        return s;
    }
}
