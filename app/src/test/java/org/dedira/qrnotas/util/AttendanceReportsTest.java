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

import android.os.Looper;

import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.model.Student;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The attendance figures shared by the browser's API and the phone's screens. The loaders block on
 * {@link DatabaseSync}, so — as in {@link DatabaseSyncTest} — everything database-related runs on
 * a worker thread while this thread pumps the paused main looper that delivers the callbacks.
 */
@RunWith(RobolectricTestRunner.class)
public class AttendanceReportsTest {

    private static final int PRESENT = Attendance.STATUS_PRESENT;
    private static final int ABSENT = Attendance.STATUS_ABSENT;
    private static final int EXCUSED = Attendance.STATUS_EXCUSED;

    private Database database;
    private ExecutorService worker;

    @Before
    public void setUp() {
        database = new Database(RuntimeEnvironment.getApplication());
        worker = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() {
        worker.shutdownNow();
    }

    private <T> T onWorker(Callable<T> call) throws Exception {
        Future<T> future = worker.submit(call);
        long deadline = System.currentTimeMillis() + 10000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(2);
        }
        return future.get(1, TimeUnit.SECONDS);
    }

    /* ----------------------------------- fixtures ----------------------------------- */

    private Discipline discipline;
    private ClassGroup group;
    private Student ana, bia, caio, dani;

    /** One discipline with one class of four students. */
    private void seedClass() throws Exception {
        onWorker(() -> {
            Discipline d = new Discipline();
            d.name = "BDI";
            discipline = DatabaseSync.saveDiscipline(database, d).value;
            ClassGroup g = new ClassGroup();
            g.disciplineId = discipline.id;
            g.name = "Turma A";
            group = DatabaseSync.saveClassGroup(database, g).value;
            ana = enrolled("Ana");
            bia = enrolled("Bia");
            caio = enrolled("Caio");
            dani = enrolled("Dani");
            return null;
        });
    }

    private Student enrolled(String name) {
        Student s = new Student();
        s.name = name;
        Student saved = DatabaseSync.saveStudent(database, s).value;
        Enrollment e = new Enrollment();
        e.studentId = saved.id;
        e.classGroupId = group.id;
        DatabaseSync.saveEnrollment(database, e);
        return saved;
    }

    /** Records one class day; the statuses follow the order ana, bia, caio, dani. */
    private void roll(String day, int ana, int bia, int caio, int dani) throws Exception {
        List<Attendance> marks = new ArrayList<>();
        marks.add(mark(this.ana, ana));
        marks.add(mark(this.bia, bia));
        marks.add(mark(this.caio, caio));
        marks.add(mark(this.dani, dani));
        assertNotNull(onWorker(() -> DatabaseSync.setAttendance(database, group.id, day, marks)));
    }

    private static Attendance mark(Student student, int status) {
        Attendance a = new Attendance();
        a.studentId = student.id;
        a.status = status;
        return a;
    }

    /** Closes the discipline's period, then waits a moment so what follows is clearly after it. */
    private Period closePeriod(String label) throws Exception {
        Period closed = onWorker(() -> {
            CountDownLatch done = new CountDownLatch(1);
            Period[] out = new Period[1];
            database.closeDisciplinePeriod(discipline.id, label, (ok, p) -> {
                out[0] = ok ? p : null;
                done.countDown();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            return out[0];
        });
        assertNotNull(closed);
        Thread.sleep(15);
        return closed;
    }

    private static Period period(String id, long closedAt) {
        Period p = new Period();
        p.id = id;
        p.closedAt = closedAt;
        return p;
    }

    private AttendanceReports.StudentTotals totalsOf(AttendanceReports.Summary summary, Student student) {
        for (AttendanceReports.StudentTotals t : summary.students) if (t.student.id.equals(student.id)) return t;
        return null;
    }

    /* ------------------------------------- windows ------------------------------------- */

    @Test
    public void windowFor_currentPeriodWithNoClosesCoversEverything() {
        for (String param : new String[]{null, "", "current"}) {
            AttendanceReports.Window w = AttendanceReports.windowFor(new ArrayList<Period>(), param);
            assertEquals(Long.MIN_VALUE, w.start);
            assertEquals(Long.MAX_VALUE, w.end);
        }
    }

    @Test
    public void windowFor_currentPeriodStartsAtTheLastClose() {
        List<Period> standing = Arrays.asList(period("p1", 100), period("p2", 200));
        AttendanceReports.Window w = AttendanceReports.windowFor(standing, "current");
        assertEquals(200, w.start);
        assertEquals(Long.MAX_VALUE, w.end);
    }

    @Test
    public void windowFor_aClosedPeriodRunsFromThePreviousCloseToItsOwn() {
        List<Period> standing = Arrays.asList(period("p1", 100), period("p2", 200));
        AttendanceReports.Window first = AttendanceReports.windowFor(standing, "p1");
        assertEquals(Long.MIN_VALUE, first.start);
        assertEquals(100, first.end);
        AttendanceReports.Window second = AttendanceReports.windowFor(standing, "p2");
        assertEquals(100, second.start);
        assertEquals(200, second.end);
    }

    @Test
    public void windowFor_unknownPeriodIsNull() {
        assertNull(AttendanceReports.windowFor(Arrays.asList(period("p1", 100)), "nope"));
    }

    @Test
    public void window_containsIsExclusiveAtTheStartAndInclusiveAtTheEnd() {
        List<Period> standing = Arrays.asList(period("p1", 100), period("p2", 200));
        AttendanceReports.Window w = AttendanceReports.windowFor(standing, "p2");
        Attendance a = new Attendance();
        a.createdAt = 100;
        assertFalse(w.contains(a));
        a.createdAt = 101;
        assertTrue(w.contains(a));
        a.createdAt = 200;
        assertTrue(w.contains(a));
        a.createdAt = 201;
        assertFalse(w.contains(a));
    }

    /* ---------------------------------------- days ---------------------------------------- */

    @Test
    public void days_listsEveryRecordedDayNewestFirstWithItsTallies() throws Exception {
        seedClass();
        roll("2026-03-02", PRESENT, PRESENT, ABSENT, PRESENT);
        roll("2026-03-09", PRESENT, ABSENT, EXCUSED, PRESENT);

        List<AttendanceReports.Day> days = onWorker(() -> AttendanceReports.days(database, group.id));

        assertEquals(2, days.size());
        assertEquals("2026-03-09", days.get(0).day);
        assertEquals(2, days.get(0).stats.present);
        assertEquals(1, days.get(0).stats.absent);
        assertEquals(1, days.get(0).stats.excused);
        assertEquals("2026-03-02", days.get(1).day);
        assertEquals(3, days.get(1).stats.present);
        assertEquals(1, days.get(1).stats.absent);
    }

    @Test
    public void days_aClassWithoutRollCallsHasNone() throws Exception {
        seedClass();
        assertTrue(onWorker(() -> AttendanceReports.days(database, group.id)).isEmpty());
    }

    /* --------------------------------------- summary --------------------------------------- */

    @Test
    public void summary_totalsEachEnrolledStudentAndCountsTheDays() throws Exception {
        seedClass();
        roll("2026-03-02", PRESENT, ABSENT, EXCUSED, PRESENT);
        roll("2026-03-09", PRESENT, ABSENT, PRESENT, PRESENT);

        AttendanceReports.Summary summary = onWorker(() -> AttendanceReports.summary(database, group, "current"));

        assertNotNull(summary);
        assertEquals(2, summary.days);
        assertEquals("current", summary.period);
        assertEquals(4, summary.students.size());
        assertEquals(2, totalsOf(summary, ana).stats.present);
        assertEquals(2, totalsOf(summary, bia).stats.absent);
        assertEquals(1, totalsOf(summary, caio).stats.excused);
        assertEquals(1, totalsOf(summary, caio).stats.present);
    }

    @Test
    public void summary_aStudentWithNoRecordsStillAppearsWithZeroTotals() throws Exception {
        seedClass();
        List<Attendance> onlyAna = Arrays.asList(mark(ana, PRESENT));
        onWorker(() -> DatabaseSync.setAttendance(database, group.id, "2026-03-02", onlyAna));

        AttendanceReports.Summary summary = onWorker(() -> AttendanceReports.summary(database, group, null));

        assertEquals("current", summary.period);
        assertEquals(0, totalsOf(summary, bia).stats.total());
        assertEquals(1, totalsOf(summary, ana).stats.present);
    }

    @Test
    public void summary_anUnknownPeriodIsNull() throws Exception {
        seedClass();
        assertNull(onWorker(() -> AttendanceReports.summary(database, group, "not-a-period")));
    }

    @Test
    public void summary_aClosedPeriodKeepsItsOwnRecordsApartFromTheCurrentOne() throws Exception {
        seedClass();
        roll("2026-03-02", ABSENT, PRESENT, PRESENT, PRESENT);
        Period closed = closePeriod("Bimestre 1");
        roll("2026-05-04", PRESENT, PRESENT, PRESENT, ABSENT);

        AttendanceReports.Summary current = onWorker(() -> AttendanceReports.summary(database, group, "current"));
        AttendanceReports.Summary before = onWorker(() -> AttendanceReports.summary(database, group, closed.id));

        assertEquals(1, current.days);
        assertEquals(1, totalsOf(current, ana).stats.present);
        assertEquals(0, totalsOf(current, ana).stats.absent);
        assertEquals(1, totalsOf(current, dani).stats.absent);
        assertEquals(1, before.days);
        assertEquals(1, totalsOf(before, ana).stats.absent);
        assertEquals(0, totalsOf(before, dani).stats.absent);
        assertEquals(1, current.periods.size());
        assertEquals(closed.id, current.periods.get(0).id);
    }

    /* ---------------------------------------- alerts ---------------------------------------- */

    /** Ana misses 3 of 4, Caio 2 of 4 (both excused), Bia 1 of 4, Dani none. */
    private void fourDays() throws Exception {
        roll("2026-03-02", ABSENT, PRESENT, EXCUSED, PRESENT);
        roll("2026-03-09", ABSENT, ABSENT, EXCUSED, PRESENT);
        roll("2026-03-16", ABSENT, PRESENT, PRESENT, PRESENT);
        roll("2026-03-23", PRESENT, PRESENT, PRESENT, PRESENT);
    }

    @Test
    public void alerts_flagStudentsOverTheLimitWorstFirstAndCountExcusedAsMissed() throws Exception {
        seedClass();
        fourDays();

        AttendanceReports.Alerts result = onWorker(() -> AttendanceReports.alerts(database, 25.0, 3));

        assertEquals(1, result.classGroupsChecked);
        assertEquals(2, result.alerts.size());
        assertEquals(ana.id, result.alerts.get(0).student.id);       // 75%
        assertEquals(caio.id, result.alerts.get(1).student.id);      // 50%, excused days included
        assertEquals("BDI", result.alerts.get(0).disciplineName);
        assertEquals(group.id, result.alerts.get(0).group.id);
        assertEquals(25.0, result.limit, 0.0);
        assertEquals(3, result.minDays);
    }

    @Test
    public void alerts_exactlyAtTheLimitIsNotOverIt() throws Exception {
        seedClass();
        fourDays(); // Bia is at exactly 25%

        AttendanceReports.Alerts result = onWorker(() -> AttendanceReports.alerts(database, 25.0, 3));

        for (AttendanceReports.Alert a : result.alerts) assertFalse(a.student.id.equals(bia.id));
    }

    @Test
    public void alerts_aHigherLimitDropsTheLessAbsentStudents() throws Exception {
        seedClass();
        fourDays();

        AttendanceReports.Alerts result = onWorker(() -> AttendanceReports.alerts(database, 50.0, 3));

        assertEquals(1, result.alerts.size());
        assertEquals(ana.id, result.alerts.get(0).student.id);
    }

    @Test
    public void alerts_waitForEnoughClassDaysBeforeRaisingAnAlarm() throws Exception {
        seedClass();
        fourDays();

        assertTrue(onWorker(() -> AttendanceReports.alerts(database, 25.0, 5)).alerts.isEmpty());
        assertEquals(2, onWorker(() -> AttendanceReports.alerts(database, 25.0, 4)).alerts.size());
    }

    @Test
    public void alerts_aClassWithoutRollCallsRaisesNothingButIsStillChecked() throws Exception {
        seedClass();

        AttendanceReports.Alerts result = onWorker(() -> AttendanceReports.alerts(database, 25.0, 3));

        assertEquals(1, result.classGroupsChecked);
        assertTrue(result.alerts.isEmpty());
    }

    @Test
    public void alerts_onlyThePeriodStillOpenCounts() throws Exception {
        seedClass();
        fourDays();
        closePeriod("Bimestre 1");
        // Ana starts the new period clean.
        roll("2026-05-04", PRESENT, PRESENT, PRESENT, PRESENT);

        assertTrue(onWorker(() -> AttendanceReports.alerts(database, 25.0, 1)).alerts.isEmpty());
    }
}
