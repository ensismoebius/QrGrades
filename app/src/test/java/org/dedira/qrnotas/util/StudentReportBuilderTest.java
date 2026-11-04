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
import static org.junit.Assert.assertTrue;

import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.model.PeriodSnapshot;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentReport;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The period-splitting rules: a record belongs to the first standing close at or after it, everything later is the open period. */
public class StudentReportBuilderTest {

    private StudentReportBuilder.Input in;

    @Before
    public void setUp() {
        in = new StudentReportBuilder.Input();
        in.student = new Student();
        in.student.id = "s1";
        in.student.name = "Ana";

        ClassGroup group = new ClassGroup();
        group.id = "cg1";
        group.disciplineId = "d1";
        group.name = "Turma A";
        in.classGroupsById.put("cg1", group);
        in.disciplineNamesById.put("d1", "BDI");

        Goal regular = new Goal();
        regular.name = "R";
        regular.targetPoints = 10;
        Goal good = new Goal();
        good.name = "B";
        good.targetPoints = 20;
        List<Goal> goals = new ArrayList<>();
        goals.add(regular);
        goals.add(good);
        in.goalsByDiscipline.put("d1", goals);

        Enrollment e = new Enrollment();
        e.id = "e1";
        e.studentId = "s1";
        e.classGroupId = "cg1";
        e.grades = 7;
        in.enrollments.add(e);
        in.historyByEnrollment.put("e1", new ArrayList<PointsHistory>());
    }

    private Period period(String id, String label, long closedAt, Long reopenedAt) {
        Period p = new Period();
        p.id = id;
        p.disciplineId = "d1";
        p.disciplineName = "BDI";
        p.label = label;
        p.closedAt = closedAt;
        p.reopenedAt = reopenedAt;
        in.periodsById.put(id, p);
        return p;
    }

    private void snapshot(String periodId, int points) {
        PeriodSnapshot s = new PeriodSnapshot();
        s.id = "snap-" + periodId;
        s.periodId = periodId;
        s.enrollmentId = "e1";
        s.studentId = "s1";
        s.classGroupId = "cg1";
        s.points = points;
        in.snapshots.add(s);
    }

    private PointsHistory entry(String id, int delta, long at, String revertsId) {
        PointsHistory h = new PointsHistory();
        h.id = id;
        h.enrollmentId = "e1";
        h.pointsDelta = delta;
        h.createdAt = at;
        h.revertsId = revertsId;
        in.historyByEnrollment.get("e1").add(h);
        return h;
    }

    private void attendance(String day, int status, long createdAt) {
        Attendance a = new Attendance();
        a.id = day;
        a.classGroupId = "cg1";
        a.studentId = "s1";
        a.day = day;
        a.status = status;
        a.createdAt = createdAt;
        in.attendance.add(a);
    }

    @Test
    public void splitsHistoryAndAttendanceAcrossClosedPeriodsAndTheOpenOne() {
        period("p1", "Bimestre 1", 1000, null);
        period("p2", "Bimestre 2", 2000, null);
        snapshot("p1", 12);
        snapshot("p2", 5);
        entry("a", 12, 900, null);
        entry("b", 3, 1500, null);
        entry("c", 2, 1900, null);
        entry("d", 7, 2500, null);
        attendance("2026-03-02", Attendance.STATUS_PRESENT, 800);
        attendance("2026-05-04", Attendance.STATUS_ABSENT, 1800);
        attendance("2026-08-03", Attendance.STATUS_PRESENT, 2600);

        StudentReport report = StudentReportBuilder.build(in);

        assertEquals(2, report.closed.size());
        StudentReport.Bucket first = report.closed.get(0);
        StudentReport.Bucket second = report.closed.get(1);
        assertEquals("Bimestre 1", first.label);
        assertEquals(12, first.points);
        assertEquals(1, first.history.size());
        assertEquals("a", first.history.get(0).id);
        assertEquals(1, first.attendance.present);
        assertEquals(0, first.attendance.absent);

        assertEquals("Bimestre 2", second.label);
        assertEquals(2, second.history.size());
        // Newest first.
        assertEquals("c", second.history.get(0).id);
        assertEquals("b", second.history.get(1).id);
        assertEquals(1, second.attendance.absent);

        assertEquals(1, report.current.size());
        StudentReport.Bucket open = report.current.get(0);
        assertEquals(7, open.points);
        assertEquals(1, open.history.size());
        assertEquals("d", open.history.get(0).id);
        assertEquals(1, open.attendance.present);
        assertEquals("BDI", open.discipline);
        assertEquals("Turma A", open.classGroup);
    }

    @Test
    public void aRecordMadeAtTheMomentOfTheCloseBelongsToThePeriodItClosed() {
        period("p1", "Bimestre 1", 1000, null);
        snapshot("p1", 4);
        entry("edge", 4, 1000, null);

        StudentReport report = StudentReportBuilder.build(in);

        assertEquals(1, report.closed.get(0).history.size());
        assertTrue(report.current.get(0).history.isEmpty());
    }

    @Test
    public void aReopenedPeriodIsIgnoredAndItsRecordsFoldIntoTheOpenPeriod() {
        period("p1", "Bimestre 1", 1000, 5000L);
        snapshot("p1", 4);
        entry("a", 4, 900, null);

        StudentReport report = StudentReportBuilder.build(in);

        assertTrue(report.closed.isEmpty());
        assertEquals(1, report.current.get(0).history.size());
    }

    @Test
    public void marksCancelledEntriesAndOnlyOpenPeriodOriginalsCanStillBeCancelled() {
        period("p1", "Bimestre 1", 1000, null);
        snapshot("p1", 0);
        entry("old", 3, 500, null);
        entry("cancelled", 5, 1500, null);
        entry("cancel", -5, 1600, "cancelled");
        entry("live", 2, 1700, null);

        StudentReport report = StudentReportBuilder.build(in);
        StudentReport.Bucket open = report.current.get(0);
        StudentReport.Bucket closed = report.closed.get(0);

        PointsHistory cancelled = null, cancel = null, live = null;
        for (PointsHistory h : open.history) {
            if (h.id.equals("cancelled")) cancelled = h;
            if (h.id.equals("cancel")) cancel = h;
            if (h.id.equals("live")) live = h;
        }
        assertTrue(cancelled.reverted);
        assertFalse(cancel.reverted);
        assertFalse(open.canRevert(cancelled));
        assertFalse(open.canRevert(cancel));
        assertTrue(open.canRevert(live));
        // Anything in a closed period is locked.
        assertFalse(closed.canRevert(closed.history.get(0)));
    }

    @Test
    public void goalsAreEvaluatedAgainstTheBucketsOwnPoints() {
        period("p1", "Bimestre 1", 1000, null);
        snapshot("p1", 15);

        StudentReport report = StudentReportBuilder.build(in);

        // Closed at 15 points: "R" (10) reached, "B" (20) five short.
        assertTrue(report.closed.get(0).goals.get(0).achieved);
        assertFalse(report.closed.get(0).goals.get(1).achieved);
        assertEquals(5, report.closed.get(0).goals.get(1).remaining);
        // Open period at 7 points: nothing reached yet.
        assertFalse(report.current.get(0).goals.get(0).achieved);
        assertEquals(3, report.current.get(0).goals.get(0).remaining);
    }

    @Test
    public void attendanceOfAnotherClassGroupIsNotMixedIn() {
        attendance("2026-03-02", Attendance.STATUS_PRESENT, 800);
        Attendance other = new Attendance();
        other.id = "x";
        other.classGroupId = "other-group";
        other.studentId = "s1";
        other.day = "2026-03-03";
        other.status = Attendance.STATUS_PRESENT;
        other.createdAt = 900;
        in.attendance.add(other);

        StudentReport report = StudentReportBuilder.build(in);

        assertEquals(1, report.current.get(0).attendance.total());
    }

    /* ------------------------------ goals with a window ------------------------------ */

    private static long local(int year, int month, int day, int hour) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day, hour, 0, 0);
        return c.getTimeInMillis();
    }

    private static Goal windowGoal(String name, int target, String start, String end) {
        Goal g = new Goal();
        g.name = name;
        g.targetPoints = target;
        g.startDate = start;
        g.endDate = end;
        return g;
    }

    private static StudentReport.Bucket bucketOf(List<StudentReport.Bucket> buckets, int index) {
        return buckets.get(index);
    }

    private static List<String> goalNames(StudentReport.Bucket bucket) {
        List<String> names = new ArrayList<>();
        for (GoalProgress g : bucket.goals) names.add(g.goalName);
        return names;
    }

    @Test
    public void aGoalWithAWindowAppearsOnceInThePeriodItEndsIn_countingAllItsPoints() {
        in.today = "2026-06-15";
        // Bimestre 1 closed on its last day, 30 April, in the evening.
        long closed = local(2026, 4, 30, 21);
        period("p1", "Bimestre 1", closed, null);
        snapshot("p1", 6);
        entry("a", 4, local(2026, 3, 10, 10), null);
        entry("b", 2, local(2026, 4, 10, 10), null);
        entry("c", 5, local(2026, 5, 10, 10), null);

        List<Goal> goals = new ArrayList<>();
        goals.add(windowGoal("B1", 5, "2026-02-01", "2026-04-30"));   // ends on the close day: belongs to the closed period
        goals.add(windowGoal("B2", 4, "2026-05-01", "2026-07-31"));   // still running
        goals.add(windowGoal("Semestre", 10, "2026-02-01", "2026-06-30")); // spans the close: lives in the open period
        in.goalsByDiscipline.put("d1", goals);

        StudentReport report = StudentReportBuilder.build(in);

        StudentReport.Bucket closedBucket = bucketOf(report.closed, 0);
        assertEquals("[B1]", goalNames(closedBucket).toString());
        GoalProgress b1 = closedBucket.goals.get(0);
        assertEquals(6, b1.currentPoints); // 4 + 2 given before the close, not the 5 given after
        assertTrue(b1.achieved);
        assertEquals(GoalProgress.STATE_ENDED, b1.state);

        // Soonest-ending first: the semester ends on 30 June, the second bimester on 31 July.
        StudentReport.Bucket open = bucketOf(report.current, 0);
        assertEquals("[Semestre, B2]", goalNames(open).toString());
        GoalProgress b2 = open.goals.get(1);
        assertEquals(5, b2.currentPoints);
        assertTrue(b2.achieved);
        assertEquals(GoalProgress.STATE_ACTIVE, b2.state);
        assertEquals(46, b2.daysLeft);
        // The semester started before the close, so the points of the closed period still count for it.
        GoalProgress semester = open.goals.get(0);
        assertEquals(11, semester.currentPoints);
        assertEquals("2026-02-01", semester.startDate);
    }

    @Test
    public void aFinishedGoalStaysInThePeriodItEndedIn_notInTheNextOne() {
        in.today = "2026-09-01";
        period("p1", "Bimestre 1", local(2026, 5, 3, 9), null); // closed three days after the goal ended
        snapshot("p1", 3);
        entry("a", 3, local(2026, 4, 20, 10), null);
        in.goalsByDiscipline.put("d1", new ArrayList<>(java.util.Collections.singletonList(windowGoal("B1", 5, "2026-02-01", "2026-04-30"))));

        StudentReport report = StudentReportBuilder.build(in);

        assertEquals("[B1]", goalNames(report.closed.get(0)).toString());
        assertTrue(report.closed.get(0).goals.get(0).isMissed());
        assertTrue(report.current.get(0).goals.isEmpty());
    }

    @Test
    public void aGoalWithoutDatesShowsInEveryPeriodJudgingThatPeriodsTotal() {
        in.today = "2026-06-15";
        period("p1", "Bimestre 1", local(2026, 4, 30, 21), null);
        snapshot("p1", 12);
        List<Goal> goals = new ArrayList<>();
        goals.add(windowGoal("R", 10, null, null));
        in.goalsByDiscipline.put("d1", goals);

        StudentReport report = StudentReportBuilder.build(in);

        GoalProgress inClosed = report.closed.get(0).goals.get(0);
        assertEquals(12, inClosed.currentPoints);
        assertTrue(inClosed.achieved);
        assertEquals(GoalProgress.STATE_OPEN, inClosed.state);
        GoalProgress inOpen = report.current.get(0).goals.get(0);
        assertEquals(7, inOpen.currentPoints); // the open enrollment's own total from setUp
        assertFalse(inOpen.achieved);
    }

    @Test
    public void aCancelledEntryDoesNotCountForAGoalEvenWhenItWasCancelledLater() {
        in.today = "2026-06-15";
        entry("award", 8, local(2026, 3, 10, 10), null);
        entry("undo", -8, local(2026, 6, 1, 10), "award");
        entry("kept", 2, local(2026, 3, 12, 10), null);
        in.goalsByDiscipline.put("d1", new ArrayList<>(java.util.Collections.singletonList(windowGoal("Março", 5, "2026-03-01", "2026-03-31"))));

        StudentReport report = StudentReportBuilder.build(in);

        GoalProgress goal = report.current.get(0).goals.get(0);
        assertEquals(2, goal.currentPoints);
        assertFalse(goal.achieved);
    }
}
