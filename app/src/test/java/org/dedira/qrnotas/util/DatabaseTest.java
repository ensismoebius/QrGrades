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
import static org.junit.Assert.assertNotNull;
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
import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.model.PeriodSnapshot;
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
import java.util.Arrays;
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
    public void resolveCsvRows_matchesExistingStudentDespiteAccentDifference() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        saveClassGroup(discipline.id, "A");
        newStudent("José");

        // Same real person, but this CSV spells the name without the accent — a common mismatch
        // between two exports of what a human considers the identical roster. Before normalize()
        // folded accents, this silently created a duplicate Student with a fresh, zeroed
        // enrollment instead of matching the existing one.
        List<CsvStudentRow> rows = Collections.singletonList(new CsvStudentRow(2, "Jose", "BDI", "A"));
        Result<CsvImportPlan> r = new Result<>();
        database.resolveCsvRows(rows, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(1, r.value.resolved.size());
        assertFalse(r.value.resolved.get(0).isNewStudent);
    }

    @Test
    public void resolveCsvRows_matchesExistingDisciplineAndClassGroupDespiteWhitespaceDifference() throws InterruptedException {
        Discipline discipline = saveDiscipline("Programação Web");
        saveClassGroup(discipline.id, "GRUPO A");
        newStudent("André");

        // A non-breaking space where a regular one should be, and a doubled internal space — both
        // common artifacts of pasting a roster from Excel/Sheets into a CSV. Before normalize()
        // collapsed all whitespace (not just ASCII), either would silently create a duplicate
        // discipline/class-group with a fresh, zeroed enrollment instead of matching the existing one.
        String disciplineWithNbsp = "Programação Web";
        List<CsvStudentRow> rows = Collections.singletonList(new CsvStudentRow(2, "André", disciplineWithNbsp, "GRUPO  A"));
        Result<CsvImportPlan> r = new Result<>();
        database.resolveCsvRows(rows, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(1, r.value.resolved.size());
        ResolvedCsvRow row = r.value.resolved.get(0);
        assertFalse(row.isNewDiscipline);
        assertEquals(discipline.id, row.disciplineId);
        assertFalse(row.isNewClassGroup);
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
    public void resolveCsvRows_unknownDisciplineGetsAutoCreated() throws InterruptedException {
        List<CsvStudentRow> rows = Collections.singletonList(new CsvStudentRow(2, "André", "New Discipline", "A"));
        Result<CsvImportPlan> r = new Result<>();
        database.resolveCsvRows(rows, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertTrue(r.value.errors.isEmpty());
        assertEquals(1, r.value.resolved.size());
        ResolvedCsvRow row = r.value.resolved.get(0);
        assertTrue(row.isNewDiscipline);
        assertTrue(row.isNewClassGroup);
        assertEquals("New Discipline", row.disciplineName);
        assertEquals("A", row.classGroupName);
        assertEquals(1, r.value.newDisciplineCount());
        assertEquals(1, r.value.newClassGroupCount());
    }

    @Test
    public void resolveCsvRows_unknownClassGroupGetsAutoCreatedUnderExistingDiscipline() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        List<CsvStudentRow> rows = Collections.singletonList(new CsvStudentRow(2, "André", "BDI", "Z"));
        Result<CsvImportPlan> r = new Result<>();
        database.resolveCsvRows(rows, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertTrue(r.value.errors.isEmpty());
        assertEquals(1, r.value.resolved.size());
        ResolvedCsvRow row = r.value.resolved.get(0);
        assertFalse(row.isNewDiscipline);
        assertEquals(discipline.id, row.disciplineId);
        assertTrue(row.isNewClassGroup);
        assertEquals("Z", row.classGroupName);
    }

    @Test
    public void resolveCsvRows_blankDisciplineOrClassGroupBecomesError() throws InterruptedException {
        List<CsvStudentRow> rows = Arrays.asList(
                new CsvStudentRow(2, "André", "", "A"),
                new CsvStudentRow(3, "Beatriz", "BDI", ""));
        Result<CsvImportPlan> r = new Result<>();
        database.resolveCsvRows(rows, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertTrue(r.value.resolved.isEmpty());
        assertEquals(2, r.value.errors.size());
        assertTrue(r.value.errors.get(0).reason.contains("Missing discipline name"));
        assertTrue(r.value.errors.get(1).reason.contains("Missing class group name"));
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
        rows.add(new ResolvedCsvRow(existing.id, "André", false, discipline.id, discipline.name, false, group.id, group.name, false));
        rows.add(new ResolvedCsvRow("new-student-id", "Beatriz", true, discipline.id, discipline.name, false, group.id, group.name, false));

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

    @Test
    public void importCsvRows_createsNewDisciplineAndClassGroupWhenFlagged() throws InterruptedException {
        String newDisciplineId = java.util.UUID.randomUUID().toString();
        String newClassGroupId = java.util.UUID.randomUUID().toString();

        List<ResolvedCsvRow> rows = new ArrayList<>();
        rows.add(new ResolvedCsvRow("student-1", "André", true,
                newDisciplineId, "DS", true, newClassGroupId, "GRUPO A", true));
        // A second student in the same brand-new discipline/class group — the discipline/class
        // group insert must only happen once, not once per row.
        rows.add(new ResolvedCsvRow("student-2", "Beatriz", true,
                newDisciplineId, "DS", true, newClassGroupId, "GRUPO A", true));

        Result<Void> r = new Result<>();
        database.importCsvRows(rows, (success, error) -> {
            r.success = success;
            r.done = true;
        });
        idle(r);
        assertTrue(r.success);

        Result<ArrayList<Discipline>> disciplines = new Result<>();
        database.loadAllDisciplines((success, value) -> {
            disciplines.value = value;
            disciplines.done = true;
        });
        idle(disciplines);
        assertEquals(1, disciplines.value.size());
        assertEquals("DS", disciplines.value.get(0).name);

        Result<ArrayList<ClassGroup>> groups = new Result<>();
        database.loadAllClassGroups((success, value) -> {
            groups.value = value;
            groups.done = true;
        });
        idle(groups);
        assertEquals(1, groups.value.size());
        assertEquals("GRUPO A", groups.value.get(0).name);

        Result<ArrayList<Student>> students = new Result<>();
        database.loadAllStudents((success, value) -> {
            students.value = value;
            students.done = true;
        });
        idle(students);
        assertEquals(2, students.value.size());
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

    /* -------------------------------- Periods --------------------------------- */

    private Result<Period> closeDisciplinePeriod(String disciplineId, String label) throws InterruptedException {
        Result<Period> r = new Result<>();
        database.closeDisciplinePeriod(disciplineId, label, (success, value) -> {
            r.success = success;
            r.value = value;
            r.done = true;
        });
        idle(r);
        return r;
    }

    private Result<Period> closeClassGroupPeriod(String classGroupId, String label, String newClassGroupName) throws InterruptedException {
        Result<Period> r = new Result<>();
        database.closeClassGroupPeriod(classGroupId, label, newClassGroupName, (success, value) -> {
            r.success = success;
            r.value = value;
            r.done = true;
        });
        idle(r);
        return r;
    }

    private Result<Void> reopenPeriod(String periodId) throws InterruptedException {
        Result<Void> r = new Result<>();
        String[] error = {null};
        database.reopenPeriod(periodId, (success, err) -> {
            r.success = success;
            error[0] = err;
            r.done = true;
        });
        idle(r);
        this.lastError = error[0];
        return r;
    }

    // Scratch field so reopenPeriod()/changePeriodReassignmentTarget() helpers above/below can
    // hand back the IDatabaseOnResult error string without needing a second Result subtype.
    private String lastError;

    private Result<Void> changePeriodReassignmentTarget(String periodId, String newClassGroupName) throws InterruptedException {
        Result<Void> r = new Result<>();
        String[] error = {null};
        database.changePeriodReassignmentTarget(periodId, newClassGroupName, (success, err) -> {
            r.success = success;
            error[0] = err;
            r.done = true;
        });
        idle(r);
        this.lastError = error[0];
        return r;
    }

    private int loadGrades(String studentId, String classGroupId) throws InterruptedException {
        Result<Enrollment> r = new Result<>();
        database.loadEnrollment(studentId, classGroupId, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);
        return r.value.grades;
    }

    private ClassGroup loadClassGroup(String disciplineId, String classGroupId) throws InterruptedException {
        Result<ArrayList<ClassGroup>> r = new Result<>();
        database.loadClassGroupsForDiscipline(disciplineId, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);
        for (ClassGroup g : r.value) if (g.id.equals(classGroupId)) return g;
        return null;
    }

    private ArrayList<PeriodSnapshot> loadPeriodSnapshots(String periodId) throws InterruptedException {
        Result<ArrayList<PeriodSnapshot>> r = new Result<>();
        database.loadPeriodSnapshots(periodId, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);
        return r.value;
    }

    @Test
    public void closeDisciplinePeriod_snapshotsAndResetsEnrollmentsAcrossAllClassGroups() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup groupA = saveClassGroup(discipline.id, "A");
        ClassGroup groupB = saveClassGroup(discipline.id, "B");
        Student andre = newStudent("André");
        Student beatriz = newStudent("Beatriz");
        Enrollment enrollmentA = saveEnrollment(andre.id, groupA.id).value;
        Enrollment enrollmentB = saveEnrollment(beatriz.id, groupB.id).value;
        database.updateEnrollmentGrades(enrollmentA.id, 10, (s, v) -> {
        });
        database.updateEnrollmentGrades(enrollmentB.id, 7, (s, v) -> {
        });

        Result<Period> r = closeDisciplinePeriod(discipline.id, "Bimestre 1");

        assertTrue(r.success);
        assertEquals(discipline.id, r.value.disciplineId);
        assertEquals("Bimestre 1", r.value.label);
        assertNull(r.value.closedClassGroupId);
        assertEquals(0, loadGrades(andre.id, groupA.id));
        assertEquals(0, loadGrades(beatriz.id, groupB.id));

        ArrayList<PeriodSnapshot> snapshots = loadPeriodSnapshots(r.value.id);
        assertEquals(2, snapshots.size());
        assertEquals(17, snapshots.get(0).points + snapshots.get(1).points);
        // The whole-discipline close never touches any class group's active/closed state.
        assertFalse(loadClassGroup(discipline.id, groupA.id).isClosed());
        assertFalse(loadClassGroup(discipline.id, groupB.id).isClosed());
    }

    @Test
    public void closeClassGroupPeriod_archiveOnly_marksClassGroupClosedAndResetsPoints() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Student andre = newStudent("André");
        Enrollment enrollment = saveEnrollment(andre.id, group.id).value;
        database.updateEnrollmentGrades(enrollment.id, 10, (s, v) -> {
        });

        Result<Period> r = closeClassGroupPeriod(group.id, null, null);

        assertTrue(r.success);
        assertEquals(group.id, r.value.closedClassGroupId);
        assertNull(r.value.reassignedToClassGroupId);
        assertEquals(0, loadGrades(andre.id, group.id));
        assertTrue(loadClassGroup(discipline.id, group.id).isClosed());
    }

    @Test
    public void closeClassGroupPeriod_withReassignment_movesEnrollmentsToNewClassGroup() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup oldGroup = saveClassGroup(discipline.id, "A - 2026");
        Student andre = newStudent("André");
        Enrollment enrollment = saveEnrollment(andre.id, oldGroup.id).value;
        database.updateEnrollmentGrades(enrollment.id, 10, (s, v) -> {
        });

        Result<Period> r = closeClassGroupPeriod(oldGroup.id, "Bimestre 1", "A - 2027");

        assertTrue(r.success);
        String newGroupId = r.value.reassignedToClassGroupId;
        assertNotNull(newGroupId);
        assertTrue(loadClassGroup(discipline.id, oldGroup.id).isClosed());
        assertFalse(loadClassGroup(discipline.id, newGroupId).isClosed());
        assertEquals("A - 2027", loadClassGroup(discipline.id, newGroupId).name);
        // The student's enrollment followed them into the new class group, reset to 0.
        assertEquals(0, loadGrades(andre.id, newGroupId));
    }

    @Test
    public void reopenPeriod_archiveOnly_restoresPointsAndUnarchivesClassGroup() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Student andre = newStudent("André");
        Enrollment enrollment = saveEnrollment(andre.id, group.id).value;
        database.updateEnrollmentGrades(enrollment.id, 10, (s, v) -> {
        });
        Period period = closeClassGroupPeriod(group.id, null, null).value;

        Result<Void> r = reopenPeriod(period.id);

        assertTrue(r.success);
        assertEquals(10, loadGrades(andre.id, group.id));
        assertFalse(loadClassGroup(discipline.id, group.id).isClosed());
    }

    @Test
    public void reopenPeriod_withReassignment_movesStudentsBackAndDeletesEmptyNewGroup() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup oldGroup = saveClassGroup(discipline.id, "A - 2026");
        Student andre = newStudent("André");
        Enrollment enrollment = saveEnrollment(andre.id, oldGroup.id).value;
        database.updateEnrollmentGrades(enrollment.id, 10, (s, v) -> {
        });
        Period period = closeClassGroupPeriod(oldGroup.id, null, "A - 2027").value;
        String newGroupId = period.reassignedToClassGroupId;

        Result<Void> r = reopenPeriod(period.id);

        assertTrue(r.success);
        assertEquals(10, loadGrades(andre.id, oldGroup.id));
        assertFalse(loadClassGroup(discipline.id, oldGroup.id).isClosed());
        // The new class group only ever existed for this reassignment, so undoing it removes it.
        assertNull(loadClassGroup(discipline.id, newGroupId));
    }

    @Test
    public void reopenPeriod_failsWhenAlreadyReopened() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        Student andre = newStudent("André");
        saveEnrollment(andre.id, group.id);
        Period period = closeClassGroupPeriod(group.id, null, null).value;
        reopenPeriod(period.id);

        Result<Void> r = reopenPeriod(period.id);

        assertFalse(r.success);
        assertEquals("ALREADY_REOPENED", this.lastError);
    }

    @Test
    public void changePeriodReassignmentTarget_movesToNewDestinationAndDeletesOldEmptyGroup() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup oldGroup = saveClassGroup(discipline.id, "A - 2026");
        Student andre = newStudent("André");
        Enrollment enrollment = saveEnrollment(andre.id, oldGroup.id).value;
        database.updateEnrollmentGrades(enrollment.id, 10, (s, v) -> {
        });
        Period period = closeClassGroupPeriod(oldGroup.id, null, "A - 2027 (errado)").value;
        String wrongGroupId = period.reassignedToClassGroupId;

        Result<Void> r = changePeriodReassignmentTarget(period.id, "A - 2027 (certo)");

        assertTrue(r.success);
        assertNull(loadClassGroup(discipline.id, wrongGroupId));

        Result<ArrayList<ClassGroup>> groups = new Result<>();
        database.loadClassGroupsForDiscipline(discipline.id, (success, value) -> {
            groups.value = value;
            groups.done = true;
        });
        idle(groups);
        ClassGroup correctedGroup = null;
        for (ClassGroup g : groups.value) if ("A - 2027 (certo)".equals(g.name)) correctedGroup = g;
        assertNotNull(correctedGroup);
        // Correcting the destination only moves where the (already-reset) fresh count
        // continues — it doesn't rehydrate the pre-close total, since the close itself wasn't undone.
        assertEquals(0, loadGrades(andre.id, correctedGroup.id));
    }

    @Test
    public void loadPeriodsForDiscipline_returnsClosedPeriodsNewestFirstWithJoinedNames() throws InterruptedException {
        Discipline discipline = saveDiscipline("BDI");
        ClassGroup group = saveClassGroup(discipline.id, "A");
        closeDisciplinePeriod(discipline.id, "Bimestre 1");
        closeClassGroupPeriod(group.id, "Encerramento da turma A", null);

        Result<ArrayList<Period>> r = new Result<>();
        database.loadPeriodsForDiscipline(discipline.id, (success, value) -> {
            r.value = value;
            r.done = true;
        });
        idle(r);

        assertEquals(2, r.value.size());
        // Newest first: the class-group close happened after the discipline-wide one.
        assertEquals("Encerramento da turma A", r.value.get(0).label);
        assertEquals("BDI", r.value.get(0).disciplineName);
        assertEquals("A", r.value.get(0).closedClassGroupName);
        assertEquals("Bimestre 1", r.value.get(1).label);
    }
}
