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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;

import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.IDatabaseOnLoad;
import org.dedira.qrnotas.model.PointsAward;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.RevertResult;
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

/**
 * Tests for the schema-version-9 features: regenerating a student's QR code, atomic point
 * awards, cancelling/correcting history entries, point categories, and attendance.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseFeaturesTest {

    private static class Result<T> {
        volatile boolean done;
        boolean success;
        T value;
        String error;
    }

    private interface Call<T> {
        void run(IDatabaseOnLoad<T> listener);
    }

    private Context context;
    private Database database;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        database = new Database(context);
    }

    /** {@link Database} answers on a background thread and posts to the (paused) main looper, so drive that looper until the callback lands. */
    private static void idle(Result<?> r) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!r.done && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(2);
        }
        assertTrue("timed out waiting for Database callback", r.done);
    }

    private <T> Result<T> await(Call<T> call) throws InterruptedException {
        Result<T> r = new Result<>();
        call.run((success, value) -> {
            r.success = success;
            r.value = value;
            r.done = true;
        });
        idle(r);
        return r;
    }

    /* ----------------------------------- fixtures ----------------------------------- */

    private Student student(String name) throws InterruptedException {
        Student s = new Student();
        s.name = name;
        return await((IDatabaseOnLoad<Student> l) -> database.saveStudent(s, (ok, v) -> l.onLoadComplete(ok, v))).value;
    }

    private Discipline discipline(String name) throws InterruptedException {
        Discipline d = new Discipline();
        d.name = name;
        Result<Discipline> r = new Result<>();
        database.saveDiscipline(d, (ok, v) -> {
            r.success = ok;
            r.value = v;
            r.done = true;
        });
        idle(r);
        return r.value;
    }

    private ClassGroup classGroup(String disciplineId, String name) throws InterruptedException {
        ClassGroup g = new ClassGroup();
        g.disciplineId = disciplineId;
        g.name = name;
        Result<ClassGroup> r = new Result<>();
        database.saveClassGroup(g, (ok, v) -> {
            r.success = ok;
            r.value = v;
            r.done = true;
        });
        idle(r);
        return r.value;
    }

    private Enrollment enroll(String studentId, String classGroupId) throws InterruptedException {
        Enrollment e = new Enrollment();
        e.studentId = studentId;
        e.classGroupId = classGroupId;
        Result<Enrollment> r = new Result<>();
        database.saveEnrollment(e, (ok, v) -> {
            r.success = ok;
            r.value = v;
            r.done = true;
        });
        idle(r);
        return r.value;
    }

    private Enrollment reload(String enrollmentId) throws InterruptedException {
        return await((IDatabaseOnLoad<Enrollment> l) -> database.loadEnrollmentById(enrollmentId, l)).value;
    }

    private List<PointsHistory> history(String enrollmentId) throws InterruptedException {
        return await((IDatabaseOnLoad<ArrayList<PointsHistory>> l) -> database.loadHistoryForEnrollment(enrollmentId, l)).value;
    }

    private Result<PointsAward> award(String enrollmentId, int delta, String note, String tag) throws InterruptedException {
        return await((IDatabaseOnLoad<PointsAward> l) -> database.awardPoints(enrollmentId, delta, note, tag, l));
    }

    private Result<RevertResult> revert(String historyId, PointsHistory replacement) throws InterruptedException {
        return await((IDatabaseOnLoad<RevertResult> l) -> database.revertPointsHistory(historyId, replacement, l));
    }

    private static PointsHistory replacement(int delta, String note, String tag) {
        PointsHistory h = new PointsHistory();
        h.pointsDelta = delta;
        h.note = note;
        h.tag = tag;
        return h;
    }

    /* ------------------------------------ schema ------------------------------------ */

    private boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) if (column.equals(c.getString(c.getColumnIndexOrThrow("name")))) return true;
        }
        return false;
    }

    @Test
    public void upgradingFromVersion8_keepsEveryRowAndAddsTheNewColumnsAndTable() {
        File dbFile = context.getDatabasePath(StudentDbHelper.DB_NAME);
        dbFile.getParentFile().mkdirs();
        dbFile.delete();
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        old.execSQL("CREATE TABLE disciplines (id TEXT PRIMARY KEY, name TEXT NOT NULL)");
        old.execSQL("CREATE TABLE class_groups (id TEXT PRIMARY KEY, discipline_id TEXT NOT NULL, name TEXT NOT NULL, closed_at INTEGER)");
        old.execSQL("CREATE TABLE goals (id TEXT PRIMARY KEY, discipline_id TEXT NOT NULL, name TEXT NOT NULL, target_points INTEGER NOT NULL DEFAULT 0)");
        old.execSQL("CREATE TABLE students (id TEXT PRIMARY KEY, name TEXT NOT NULL, photo_path TEXT)");
        old.execSQL("CREATE TABLE enrollments (id TEXT PRIMARY KEY, student_id TEXT NOT NULL, class_group_id TEXT NOT NULL, grades INTEGER NOT NULL DEFAULT 0, UNIQUE(student_id, class_group_id))");
        old.execSQL("CREATE TABLE points_history (id TEXT PRIMARY KEY, enrollment_id TEXT NOT NULL, points_delta INTEGER NOT NULL, note TEXT, created_at INTEGER NOT NULL)");
        old.execSQL("CREATE TABLE qr_scans (id TEXT PRIMARY KEY, student_id TEXT NOT NULL, scanned_at INTEGER NOT NULL)");
        old.execSQL("CREATE TABLE periods (id TEXT PRIMARY KEY, discipline_id TEXT NOT NULL, label TEXT, closed_at INTEGER NOT NULL, closed_class_group_id TEXT, reassigned_to_class_group_id TEXT, reopened_at INTEGER)");
        old.execSQL("CREATE TABLE period_snapshots (id TEXT PRIMARY KEY, period_id TEXT NOT NULL, enrollment_id TEXT NOT NULL, student_id TEXT NOT NULL, class_group_id TEXT NOT NULL, points INTEGER NOT NULL)");
        old.execSQL("INSERT INTO goals VALUES ('g1', 'd1', 'R', 10)");
        old.execSQL("INSERT INTO students VALUES ('s1', 'Ana', '/photo.png')");
        old.execSQL("INSERT INTO points_history VALUES ('h1', 'e1', 5, 'bom trabalho', 1000)");
        old.setVersion(8);
        old.close();

        SQLiteDatabase upgraded = new StudentDbHelper(context).getWritableDatabase();

        assertEquals(10, upgraded.getVersion());
        assertTrue(hasColumn(upgraded, "goals", "start_date"));
        assertTrue(hasColumn(upgraded, "goals", "end_date"));
        try (Cursor c = upgraded.rawQuery("SELECT name, target_points, start_date, end_date FROM goals WHERE id='g1'", null)) {
            assertTrue(c.moveToFirst());
            assertEquals("R", c.getString(0));
            assertEquals(10, c.getInt(1));
            assertTrue("an existing goal keeps no dates until the teacher picks them", c.isNull(2) && c.isNull(3));
        }
        assertTrue(hasColumn(upgraded, "students", "qr_token"));
        assertTrue(hasColumn(upgraded, "points_history", "tag"));
        assertTrue(hasColumn(upgraded, "points_history", "reverts_id"));
        assertTrue(hasColumn(upgraded, "attendance", "status"));
        try (Cursor c = upgraded.rawQuery("SELECT name, photo_path, qr_token FROM students WHERE id='s1'", null)) {
            assertTrue(c.moveToFirst());
            assertEquals("Ana", c.getString(0));
            assertEquals("/photo.png", c.getString(1));
            assertTrue("an existing student starts with no token", c.isNull(2));
        }
        try (Cursor c = upgraded.rawQuery("SELECT note, tag, reverts_id FROM points_history WHERE id='h1'", null)) {
            assertTrue(c.moveToFirst());
            assertEquals("bom trabalho", c.getString(0));
            assertTrue(c.isNull(1));
            assertTrue(c.isNull(2));
        }
    }

    /* ---------------------------------- QR codes ---------------------------------- */

    @Test
    public void aStudentWhoseCodeWasNeverRegeneratedIsFoundByTheirId() throws InterruptedException {
        Student ana = student("Ana");

        Result<Student> found = await((IDatabaseOnLoad<Student> l) -> database.loadStudentByQrCode(ana.id, l));

        assertTrue(found.success);
        assertEquals(ana.id, found.value.id);
        assertNull(found.value.qrToken);
        assertEquals(ana.id, found.value.qrCode());
    }

    @Test
    public void regeneratingRetiresTheOldCodeAndTheNewTokenTakesOver() throws InterruptedException {
        Student ana = student("Ana");
        Student other = student("Bia");

        Result<Student> regenerated = await((IDatabaseOnLoad<Student> l) -> database.regenerateQrToken(ana.id, l));

        assertTrue(regenerated.success);
        String token = regenerated.value.qrToken;
        assertNotNull(token);
        assertNotEquals(ana.id, token);
        assertEquals(token, regenerated.value.qrCode());
        // The old printed code (her id) no longer identifies anyone — not her, not someone else.
        assertFalse(await((IDatabaseOnLoad<Student> l) -> database.loadStudentByQrCode(ana.id, l)).success);
        Result<Student> viaToken = await((IDatabaseOnLoad<Student> l) -> database.loadStudentByQrCode(token, l));
        assertTrue(viaToken.success);
        assertEquals(ana.id, viaToken.value.id);
        // Other students are untouched.
        assertTrue(await((IDatabaseOnLoad<Student> l) -> database.loadStudentByQrCode(other.id, l)).success);

        // Regenerating again retires the previous token too.
        String second = await((IDatabaseOnLoad<Student> l) -> database.regenerateQrToken(ana.id, l)).value.qrToken;
        assertNotEquals(token, second);
        assertFalse(await((IDatabaseOnLoad<Student> l) -> database.loadStudentByQrCode(token, l)).success);
        assertTrue(await((IDatabaseOnLoad<Student> l) -> database.loadStudentByQrCode(second, l)).success);
    }

    @Test
    public void unknownBlankAndNullCodesMatchNobody() throws InterruptedException {
        student("Ana");
        assertFalse(await((IDatabaseOnLoad<Student> l) -> database.loadStudentByQrCode("nope", l)).success);
        assertFalse(await((IDatabaseOnLoad<Student> l) -> database.loadStudentByQrCode("", l)).success);
        assertFalse(await((IDatabaseOnLoad<Student> l) -> database.loadStudentByQrCode(null, l)).success);
    }

    @Test
    public void regeneratingForAMissingStudentFails() throws InterruptedException {
        assertFalse(await((IDatabaseOnLoad<Student> l) -> database.regenerateQrToken("ghost", l)).success);
    }

    @Test
    public void renamingAStudentFromAScratchObjectKeepsTheirRegeneratedToken() throws InterruptedException {
        Student ana = student("Ana");
        String token = await((IDatabaseOnLoad<Student> l) -> database.regenerateQrToken(ana.id, l)).value.qrToken;

        // A caller that rebuilds the Student without knowing about the token.
        Student scratch = new Student();
        scratch.id = ana.id;
        scratch.name = "Ana Maria";
        student(scratch);

        Student reloaded = await((IDatabaseOnLoad<Student> l) -> database.loadStudent(ana.id, l)).value;
        assertEquals("Ana Maria", reloaded.name);
        assertEquals(token, reloaded.qrToken);
    }

    private void student(Student s) throws InterruptedException {
        await((IDatabaseOnLoad<Student> l) -> database.saveStudent(s, (ok, v) -> l.onLoadComplete(ok, v)));
    }

    /* --------------------------------- awarding points --------------------------------- */

    private Enrollment setUpEnrollment() throws InterruptedException {
        Discipline d = discipline("BDI");
        ClassGroup g = classGroup(d.id, "A");
        return enroll(student("Ana").id, g.id);
    }

    @Test
    public void awardPoints_movesTheTotalAndWritesTheHistoryEntryTogether() throws InterruptedException {
        Enrollment e = setUpEnrollment();

        Result<PointsAward> r = award(e.id, 4, "  participou  ", "  Participação ");

        assertTrue(r.success);
        assertEquals(4, r.value.enrollment.grades);
        assertEquals(4, reload(e.id).grades);
        List<PointsHistory> h = history(e.id);
        assertEquals(1, h.size());
        assertEquals(r.value.history.id, h.get(0).id);
        assertEquals(4, h.get(0).pointsDelta);
        // The tag is normalised; the note is kept as typed (the callers trim it).
        assertEquals("Participação", h.get(0).tag);
        assertNull(h.get(0).revertsId);
    }

    @Test
    public void awardPoints_isRelativeSoSuccessiveAwardsAccumulate() throws InterruptedException {
        Enrollment e = setUpEnrollment();
        award(e.id, 3, "a", null);
        award(e.id, -1, "b", null);
        award(e.id, 5, "c", "Prova");

        assertEquals(7, reload(e.id).grades);
        assertEquals(3, history(e.id).size());
    }

    @Test
    public void awardPoints_rejectsZeroDeltaAndMissingEnrollmentWithoutTouchingAnything() throws InterruptedException {
        Enrollment e = setUpEnrollment();

        assertFalse(award(e.id, 0, "x", null).success);
        assertFalse(award("ghost", 3, "x", null).success);

        assertEquals(0, reload(e.id).grades);
        assertTrue(history(e.id).isEmpty());
    }

    @Test
    public void awardPoints_blankTagMeansNoCategory() throws InterruptedException {
        Enrollment e = setUpEnrollment();
        award(e.id, 1, "x", "   ");
        assertNull(history(e.id).get(0).tag);
    }

    @Test
    public void cleanTag_trimsSqueezesAndCaps() {
        assertEquals("Atividade em grupo", Database.cleanTag("  Atividade   em  grupo "));
        assertNull(Database.cleanTag("  "));
        assertNull(Database.cleanTag(null));
        char[] long50 = new char[50];
        Arrays.fill(long50, 'x');
        assertEquals(40, Database.cleanTag(new String(long50)).length());
    }

    @Test
    public void awardPointsBatch_appliesToEveryoneAndRemovesDuplicates() throws InterruptedException {
        Discipline d = discipline("BDI");
        ClassGroup g = classGroup(d.id, "A");
        Enrollment a = enroll(student("Ana").id, g.id);
        Enrollment b = enroll(student("Bia").id, g.id);

        Result<ArrayList<PointsAward>> r = await((IDatabaseOnLoad<ArrayList<PointsAward>> l) ->
                database.awardPointsBatch(Arrays.asList(a.id, b.id, a.id), 2, "entregou", "Atividade", l));

        assertTrue(r.success);
        assertEquals(2, r.value.size());
        assertEquals(2, reload(a.id).grades);
        assertEquals(2, reload(b.id).grades);
        assertEquals(1, history(a.id).size());
        assertEquals("Atividade", history(b.id).get(0).tag);
    }

    @Test
    public void awardPointsBatch_isAllOrNothing() throws InterruptedException {
        Discipline d = discipline("BDI");
        ClassGroup g = classGroup(d.id, "A");
        Enrollment a = enroll(student("Ana").id, g.id);

        Result<ArrayList<PointsAward>> r = await((IDatabaseOnLoad<ArrayList<PointsAward>> l) ->
                database.awardPointsBatch(Arrays.asList(a.id, "ghost"), 2, "x", null, l));

        assertFalse(r.success);
        assertEquals("Ana was rolled back along with the failed one", 0, reload(a.id).grades);
        assertTrue(history(a.id).isEmpty());
    }

    /* ----------------------------- cancelling and correcting ----------------------------- */

    @Test
    public void revert_appendsAnOppositeEntryInsteadOfRewritingHistory() throws InterruptedException {
        Enrollment e = setUpEnrollment();
        PointsHistory original = award(e.id, 5, "bom trabalho", "Atividade").value.history;

        Result<RevertResult> r = revert(original.id, null);

        assertTrue(r.success);
        assertNull(r.value.error);
        assertNotNull(r.value.reversal);
        assertNull(r.value.replacement);
        assertEquals(0, reload(e.id).grades);
        List<PointsHistory> h = history(e.id);
        assertEquals("the original is still there", 2, h.size());
        PointsHistory reversal = null;
        for (PointsHistory x : h) if (x.revertsId != null) reversal = x;
        assertNotNull(reversal);
        assertEquals(-5, reversal.pointsDelta);
        assertEquals(original.id, reversal.revertsId);
        assertEquals("Atividade", reversal.tag);
        assertTrue(reversal.note.contains("bom trabalho"));
    }

    @Test
    public void correct_cancelsAndReEntersInOneStep() throws InterruptedException {
        Enrollment e = setUpEnrollment();
        PointsHistory original = award(e.id, 5, "ativ", "Atividade").value.history;

        Result<RevertResult> r = revert(original.id, replacement(3, "ativ (corrigido)", "Prova"));

        assertTrue(r.success);
        assertNotNull(r.value.replacement);
        assertEquals(3, reload(e.id).grades);
        assertEquals(3, history(e.id).size());
        assertEquals(3, r.value.replacement.history.pointsDelta);
        assertEquals("Prova", r.value.replacement.history.tag);
    }

    @Test
    public void revert_cannotBeDoneTwiceNorOnACancellingEntry() throws InterruptedException {
        Enrollment e = setUpEnrollment();
        PointsHistory original = award(e.id, 5, "x", null).value.history;
        PointsHistory reversal = revert(original.id, null).value.reversal.history;

        Result<RevertResult> again = revert(original.id, null);
        assertFalse(again.success);
        assertEquals("ALREADY_REVERTED", again.value.error);

        Result<RevertResult> ofReversal = revert(reversal.id, null);
        assertFalse(ofReversal.success);
        assertEquals("IS_REVERSAL", ofReversal.value.error);

        assertEquals("failed attempts changed nothing", 0, reload(e.id).grades);
        assertEquals(2, history(e.id).size());
    }

    @Test
    public void revert_ofAnUnknownEntryReportsNotFound() throws InterruptedException {
        Result<RevertResult> r = revert("ghost", null);
        assertFalse(r.success);
        assertEquals("NOT_FOUND", r.value.error);
    }

    @Test
    public void revert_isLockedOnceTheEntrysPeriodWasClosed_andFreeAgainAfterReopening() throws InterruptedException {
        Discipline d = discipline("BDI");
        ClassGroup g = classGroup(d.id, "A");
        Enrollment e = enroll(student("Ana").id, g.id);
        PointsHistory inClosedPeriod = award(e.id, 5, "x", null).value.history;

        Result<org.dedira.qrnotas.model.Period> closed = await((IDatabaseOnLoad<org.dedira.qrnotas.model.Period> l) ->
                database.closeDisciplinePeriod(d.id, "Bimestre 1", (ok, v) -> l.onLoadComplete(ok, v)));
        assertTrue(closed.success);
        PointsHistory inOpenPeriod = award(e.id, 2, "y", null).value.history;

        Result<RevertResult> locked = revert(inClosedPeriod.id, null);
        assertFalse(locked.success);
        assertEquals("CLOSED_PERIOD", locked.value.error);
        assertEquals(2, reload(e.id).grades);
        // The open period's entry is still fine.
        assertTrue(revert(inOpenPeriod.id, null).success);
        assertEquals(0, reload(e.id).grades);

        Result<Void> reopened = new Result<>();
        database.reopenPeriod(closed.value.id, (ok, err) -> {
            reopened.success = ok;
            reopened.done = true;
        });
        idle(reopened);
        assertTrue(reopened.success);
        assertTrue("after reopening, the entry belongs to the open period again", revert(inClosedPeriod.id, null).success);
    }

    @Test
    public void deletePointsAward_takesBackAJustMadeAwardWithoutATrace() throws InterruptedException {
        Enrollment e = setUpEnrollment();
        award(e.id, 2, "keep", null);
        PointsHistory fresh = award(e.id, 4, "oops", null).value.history;

        Result<Void> r = new Result<>();
        database.deletePointsAward(fresh.id, (ok, err) -> {
            r.success = ok;
            r.error = err;
            r.done = true;
        });
        idle(r);

        assertTrue(r.success);
        assertEquals(2, reload(e.id).grades);
        assertEquals(1, history(e.id).size());
    }

    @Test
    public void deletePointsAward_refusesAnEntryThatWasAlreadyCancelled() throws InterruptedException {
        Enrollment e = setUpEnrollment();
        PointsHistory original = award(e.id, 4, "x", null).value.history;
        revert(original.id, null);

        Result<Void> r = new Result<>();
        database.deletePointsAward(original.id, (ok, err) -> {
            r.success = ok;
            r.error = err;
            r.done = true;
        });
        idle(r);

        assertFalse(r.success);
        assertEquals("ALREADY_REVERTED", r.error);
        assertEquals(2, history(e.id).size());
    }

    @Test
    public void recentTagsAreDistinctAndMostRecentFirst() throws InterruptedException {
        Enrollment e = setUpEnrollment();
        award(e.id, 1, "", "Prova");
        Thread.sleep(5);
        award(e.id, 1, "", "Atividade");
        Thread.sleep(5);
        award(e.id, 1, "", "Prova");
        award(e.id, 1, "", null);

        List<String> tags = await((IDatabaseOnLoad<ArrayList<String>> l) -> database.loadRecentTags(10, l)).value;

        assertEquals(Arrays.asList("Prova", "Atividade"), tags);
    }

    /* ---------------------------------- export / import ---------------------------------- */

    @Test
    public void exportCarriesTagAndRevertLinkAndToken_andImportRestoresThem() throws InterruptedException {
        Enrollment e = setUpEnrollment();
        PointsHistory original = award(e.id, 5, "x", "Prova").value.history;
        revert(original.id, null);
        String studentId = e.studentId;
        String token = await((IDatabaseOnLoad<Student> l) -> database.regenerateQrToken(studentId, l)).value.qrToken;

        List<StudentExportData> exported = await((IDatabaseOnLoad<ArrayList<StudentExportData>> l) ->
                database.loadExportData(null, l)).value;
        assertEquals(1, exported.size());
        assertEquals(token, exported.get(0).qrToken);
        assertFalse(exported.get(0).classGroupClosed);
        PointsHistory revertingEntry = null;
        for (PointsHistory h : exported.get(0).history) if (h.revertsId != null) revertingEntry = h;
        assertNotNull(revertingEntry);
        assertEquals("Prova", revertingEntry.tag);

        // Wipe the student and bring everything back from the export.
        await((IDatabaseOnLoad<Student> l) -> database.deleteStudent(studentId, (ok, v) -> l.onLoadComplete(ok, null)));
        Result<Void> imported = new Result<>();
        database.importExportData(exported, (ok, err) -> {
            imported.success = ok;
            imported.done = true;
        });
        idle(imported);
        assertTrue(imported.success);

        Student back = await((IDatabaseOnLoad<Student> l) -> database.loadStudent(studentId, l)).value;
        assertEquals("the regenerated code survives a restore", token, back.qrToken);
        List<PointsHistory> restored = history(e.id);
        assertEquals(2, restored.size());
        boolean foundLink = false;
        for (PointsHistory h : restored) if (original.id.equals(h.revertsId)) foundLink = true;
        assertTrue(foundLink);
    }

    @Test
    public void importingAnOlderFileWithoutATokenKeepsTheTokenAlreadyOnThisDevice() throws InterruptedException {
        Enrollment e = setUpEnrollment();
        String studentId = e.studentId;
        String token = await((IDatabaseOnLoad<Student> l) -> database.regenerateQrToken(studentId, l)).value.qrToken;

        List<StudentExportData> exported = await((IDatabaseOnLoad<ArrayList<StudentExportData>> l) ->
                database.loadExportData(null, l)).value;
        exported.get(0).qrToken = null;
        Result<Void> imported = new Result<>();
        database.importExportData(exported, (ok, err) -> {
            imported.success = ok;
            imported.done = true;
        });
        idle(imported);

        assertEquals(token, await((IDatabaseOnLoad<Student> l) -> database.loadStudent(studentId, l)).value.qrToken);
    }

    @Test
    public void exportMarksEnrollmentsOfArchivedClassGroups() throws InterruptedException {
        Discipline d = discipline("BDI");
        ClassGroup g = classGroup(d.id, "A");
        enroll(student("Ana").id, g.id);
        Result<org.dedira.qrnotas.model.Period> closed = await((IDatabaseOnLoad<org.dedira.qrnotas.model.Period> l) ->
                database.closeClassGroupPeriod(g.id, "fim", null, (ok, v) -> l.onLoadComplete(ok, v)));
        assertTrue(closed.success);

        List<StudentExportData> exported = await((IDatabaseOnLoad<ArrayList<StudentExportData>> l) ->
                database.loadExportData(null, l)).value;

        assertTrue(exported.get(0).classGroupClosed);
    }

    /* ------------------------------------ attendance ------------------------------------ */

    private ClassGroup classWithTwoStudents(Student[] out) throws InterruptedException {
        Discipline d = discipline("BDI");
        ClassGroup g = classGroup(d.id, "A");
        out[0] = student("Ana");
        out[1] = student("Bia");
        enroll(out[0].id, g.id);
        enroll(out[1].id, g.id);
        return g;
    }

    private static Attendance mark(String studentId, int status) {
        Attendance a = new Attendance();
        a.studentId = studentId;
        a.status = status;
        return a;
    }

    @Test
    public void startAttendance_opensARollCallWithEveryoneAbsentAndIsIdempotent() throws InterruptedException {
        Student[] s = new Student[2];
        ClassGroup g = classWithTwoStudents(s);

        Result<ArrayList<Attendance>> started = await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.startAttendance(g.id, "2026-10-21", l));
        assertTrue(started.success);
        assertEquals(2, started.value.size());
        for (Attendance a : started.value) assertEquals(Attendance.STATUS_ABSENT, a.status);

        await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.setAttendance(g.id, "2026-10-21", Collections.singletonList(mark(s[0].id, Attendance.STATUS_PRESENT)), l));
        // Starting again must not reset the student who was already marked present.
        Result<ArrayList<Attendance>> again = await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.startAttendance(g.id, "2026-10-21", l));

        assertEquals(2, again.value.size());
        int present = 0;
        for (Attendance a : again.value) if (a.status == Attendance.STATUS_PRESENT) present++;
        assertEquals(1, present);
    }

    @Test
    public void startAttendance_pullsInAStudentEnrolledAfterTheFirstStart() throws InterruptedException {
        Student[] s = new Student[2];
        ClassGroup g = classWithTwoStudents(s);
        await((IDatabaseOnLoad<ArrayList<Attendance>> l) -> database.startAttendance(g.id, "2026-10-21", l));
        Student late = student("Caio");
        enroll(late.id, g.id);

        Result<ArrayList<Attendance>> again = await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.startAttendance(g.id, "2026-10-21", l));

        assertEquals(3, again.value.size());
    }

    @Test
    public void setAttendance_updatesInPlaceAndKeepsTheOriginalCreationTime() throws InterruptedException {
        Student[] s = new Student[2];
        ClassGroup g = classWithTwoStudents(s);
        ArrayList<Attendance> started = await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.startAttendance(g.id, "2026-10-21", l)).value;
        long createdAt = started.get(0).createdAt;
        Thread.sleep(5);

        ArrayList<Attendance> after = await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.setAttendance(g.id, "2026-10-21", Arrays.asList(
                        mark(started.get(0).studentId, Attendance.STATUS_EXCUSED)), l)).value;

        assertEquals(2, after.size());
        Attendance changed = null;
        for (Attendance a : after) if (a.studentId.equals(started.get(0).studentId)) changed = a;
        assertEquals(Attendance.STATUS_EXCUSED, changed.status);
        assertEquals(createdAt, changed.createdAt);
        assertTrue(changed.updatedAt > createdAt);
    }

    @Test
    public void setAttendance_createsARecordForAStudentWithNone() throws InterruptedException {
        Student[] s = new Student[2];
        ClassGroup g = classWithTwoStudents(s);

        ArrayList<Attendance> rows = await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.setAttendance(g.id, "2026-10-22", Collections.singletonList(mark(s[1].id, Attendance.STATUS_PRESENT)), l)).value;

        assertEquals(1, rows.size());
        assertEquals(s[1].id, rows.get(0).studentId);
        assertEquals("2026-10-22", rows.get(0).day);
    }

    @Test
    public void setAttendance_anInvalidMarkRejectsTheWholeBatch() throws InterruptedException {
        Student[] s = new Student[2];
        ClassGroup g = classWithTwoStudents(s);

        Result<ArrayList<Attendance>> r = await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.setAttendance(g.id, "2026-10-21", Arrays.asList(
                        mark(s[0].id, Attendance.STATUS_PRESENT), mark(s[1].id, 9)), l));

        assertFalse(r.success);
        assertTrue("nothing was written", await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.loadAttendanceForDay(g.id, "2026-10-21", l)).value.isEmpty());
    }

    @Test
    public void deleteAttendanceDay_removesOnlyThatDay() throws InterruptedException {
        Student[] s = new Student[2];
        ClassGroup g = classWithTwoStudents(s);
        await((IDatabaseOnLoad<ArrayList<Attendance>> l) -> database.startAttendance(g.id, "2026-10-21", l));
        await((IDatabaseOnLoad<ArrayList<Attendance>> l) -> database.startAttendance(g.id, "2026-10-22", l));

        Result<Integer> removed = await((IDatabaseOnLoad<Integer> l) -> database.deleteAttendanceDay(g.id, "2026-10-21", l));

        assertEquals(Integer.valueOf(2), removed.value);
        ArrayList<Attendance> left = await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.loadAttendanceForClassGroup(g.id, l)).value;
        assertEquals(2, left.size());
        assertEquals("2026-10-22", left.get(0).day);
    }

    @Test
    public void attendanceIsLoadablePerStudent_andDeletedWithTheStudent() throws InterruptedException {
        Student[] s = new Student[2];
        ClassGroup g = classWithTwoStudents(s);
        await((IDatabaseOnLoad<ArrayList<Attendance>> l) -> database.startAttendance(g.id, "2026-10-21", l));
        await((IDatabaseOnLoad<ArrayList<Attendance>> l) -> database.startAttendance(g.id, "2026-10-22", l));

        assertEquals(2, await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.loadAttendanceForStudent(s[0].id, l)).value.size());

        await((IDatabaseOnLoad<Student> l) -> database.deleteStudent(s[0].id, (ok, v) -> l.onLoadComplete(ok, null)));

        assertTrue(await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.loadAttendanceForStudent(s[0].id, l)).value.isEmpty());
        assertEquals("the other student's records stay", 2, await((IDatabaseOnLoad<ArrayList<Attendance>> l) ->
                database.loadAttendanceForStudent(s[1].id, l)).value.size());
    }
}
