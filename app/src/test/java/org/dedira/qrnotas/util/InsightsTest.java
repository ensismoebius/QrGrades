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

import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.StudentExportData;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The "who needs attention" lists and the weekly summary, which must match what the browser shows. */
public class InsightsTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = Insights.DAY_MS;

    private int nextId = 1;

    private StudentExportData row(String student, String group, int points, GoalProgress... goals) {
        StudentExportData d = new StudentExportData();
        d.studentId = "s-" + student;
        d.studentName = student;
        d.enrollmentId = "e-" + student + "-" + group;
        d.disciplineId = "d1";
        d.classGroupId = group;
        d.points = points;
        d.goals.addAll(Arrays.asList(goals));
        return d;
    }

    private PointsHistory entry(StudentExportData row, int delta, long daysAgo, String tag) {
        PointsHistory h = new PointsHistory();
        h.id = "h" + nextId++;
        h.enrollmentId = row.enrollmentId;
        h.pointsDelta = delta;
        h.createdAt = NOW - daysAgo * DAY - 1000;
        h.tag = tag;
        row.history.add(h);
        return h;
    }

    private PointsHistory cancel(StudentExportData row, PointsHistory original, long daysAgo) {
        PointsHistory h = new PointsHistory();
        h.id = "h" + nextId++;
        h.enrollmentId = row.enrollmentId;
        h.pointsDelta = -original.pointsDelta;
        h.createdAt = NOW - daysAgo * DAY - 500;
        h.tag = original.tag;
        h.revertsId = original.id;
        row.history.add(h);
        return h;
    }

    private static GoalProgress goal(String name, int target, int current) {
        return new GoalProgress(name, target, current);
    }

    private static GoalProgress dated(String name, int target, int current, int state) {
        return new GoalProgress(name, target, current, "2026-01-01", "2026-12-31", state, 5);
    }

    /* ------------------------------ below the minimum ------------------------------ */

    @Test
    public void belowMinimum_listsStudentsUnderTheLowestGoalFurthestFirst() {
        StudentExportData ana = row("Ana", "g1", 4, goal("Média", 20, 4), goal("Mínima", 10, 4));
        StudentExportData bia = row("Bia", "g1", 9, goal("Mínima", 10, 9));
        StudentExportData caio = row("Caio", "g1", 12, goal("Mínima", 10, 12));

        List<Insights.GoalFlag> out = Insights.belowMinimum(Arrays.asList(bia, caio, ana));

        assertEquals(2, out.size());
        assertEquals("Ana", out.get(0).row.studentName);
        assertEquals(6, out.get(0).missing);
        assertEquals("Mínima", out.get(0).goal.goalName);
        assertEquals("Bia", out.get(1).row.studentName);
        assertEquals(1, out.get(1).missing);
    }

    @Test
    public void belowMinimum_ignoresGoalsThatCanNoLongerChange() {
        // The lowest goal is over and the next one hasn't begun: neither is the passing mark right now.
        StudentExportData ana = row("Ana", "g1", 0,
                dated("Bimestre 1", 10, 0, GoalProgress.STATE_ENDED),
                dated("Bimestre 3", 15, 0, GoalProgress.STATE_UPCOMING));
        assertTrue(Insights.belowMinimum(Arrays.asList(ana)).isEmpty());

        // With a running goal, that one is the minimum.
        ana.goals.add(dated("Bimestre 2", 20, 8, GoalProgress.STATE_ACTIVE));
        List<Insights.GoalFlag> out = Insights.belowMinimum(Arrays.asList(ana));
        assertEquals(1, out.size());
        assertEquals("Bimestre 2", out.get(0).goal.goalName);
        assertEquals(12, out.get(0).missing);
    }

    @Test
    public void belowMinimum_skipsArchivedClassesAndStudentsWithoutGoals() {
        StudentExportData archived = row("Ana", "g1", 0, goal("Mínima", 10, 0));
        archived.classGroupClosed = true;
        StudentExportData noGoals = row("Bia", "g1", 0);
        assertTrue(Insights.belowMinimum(Arrays.asList(archived, noGoals)).isEmpty());
    }

    @Test
    public void goalPresence_tellsWhyAListIsEmpty() {
        StudentExportData none = row("Ana", "g1", 0);
        assertFalse(Insights.hasGoals(Arrays.asList(none)));

        StudentExportData over = row("Bia", "g1", 0, dated("Velha", 10, 0, GoalProgress.STATE_ENDED));
        assertTrue(Insights.hasGoals(Arrays.asList(none, over)));
        assertFalse(Insights.hasRunningGoals(Arrays.asList(none, over)));

        over.goals.add(dated("Nova", 10, 0, GoalProgress.STATE_ACTIVE));
        assertTrue(Insights.hasRunningGoals(Arrays.asList(none, over)));
    }

    /* ------------------------------ near the next goal ------------------------------ */

    @Test
    public void nearNextGoal_picksTheFirstUnreachedGoalWhenItIsClose() {
        // 8 of 10 reached the first goal... no: 9 of 10 is 1 away; and the 30 goal is far.
        StudentExportData ana = row("Ana", "g1", 9, goal("Mínima", 10, 9), goal("Alta", 30, 9));
        // Reached the first goal; the next (50) is 3 away, "a few" for a target of 50 is max(3, 5) = 5.
        StudentExportData bia = row("Bia", "g1", 47, goal("Mínima", 10, 47), goal("Alta", 50, 47));
        // Too far from the next goal.
        StudentExportData caio = row("Caio", "g1", 20, goal("Mínima", 10, 20), goal("Alta", 50, 20));

        List<Insights.GoalFlag> out = Insights.nearNextGoal(Arrays.asList(bia, caio, ana));

        assertEquals(2, out.size());
        assertEquals("Ana", out.get(0).row.studentName);
        assertEquals(1, out.get(0).missing);
        assertEquals("Bia", out.get(1).row.studentName);
        assertEquals(3, out.get(1).missing);
        assertEquals("Alta", out.get(1).goal.goalName);
    }

    @Test
    public void nearNextGoal_onlyLooksAtTheNextGoalNotAtLaterOnes() {
        // The student is far from the next goal; being close to a later one doesn't count.
        StudentExportData ana = row("Ana", "g1", 20, goal("A", 40, 20), goal("B", 21, 20));
        // Sorted by target: B(21) first, which is 1 away.
        List<Insights.GoalFlag> out = Insights.nearNextGoal(Arrays.asList(ana));
        assertEquals(1, out.size());
        assertEquals("B", out.get(0).goal.goalName);

        // Once B is reached, A (40) is 20 away: nothing to report.
        StudentExportData bia = row("Bia", "g1", 30, goal("A", 40, 30), goal("B", 21, 30));
        assertTrue(Insights.nearNextGoal(Arrays.asList(bia)).isEmpty());
    }

    /* ------------------------------ inactive ------------------------------ */

    @Test
    public void inactive_singlesOutStudentsOnlyWhileTheirClassIsStillScoring() {
        StudentExportData busy = row("Ana", "g1", 5);
        entry(busy, 5, 1, "x");
        StudentExportData quiet = row("Bia", "g1", 5);
        entry(quiet, 5, 30, "x");
        StudentExportData never = row("Caio", "g1", 0);

        // A whole class that stopped months ago is not singled out.
        StudentExportData stopped = row("Dani", "g2", 5);
        entry(stopped, 5, 60, "x");
        StudentExportData stopped2 = row("Edu", "g2", 0);

        List<Insights.Quiet> out = Insights.inactive(Arrays.asList(busy, quiet, never, stopped, stopped2), NOW, 14);

        assertEquals(2, out.size());
        assertEquals("Caio", out.get(0).row.studentName);
        assertNull(out.get(0).lastAt);
        assertEquals("Bia", out.get(1).row.studentName);
        assertEquals(30, Insights.daysBetween(out.get(1).lastAt, NOW));
    }

    @Test
    public void inactive_aCancelledEntryDoesNotCountAsRecentActivity() {
        StudentExportData ana = row("Ana", "g1", 0);
        PointsHistory old = entry(ana, 5, 40, "x");
        PointsHistory recent = entry(ana, 5, 2, "x");
        cancel(ana, recent, 1);
        StudentExportData bia = row("Bia", "g1", 5);
        entry(bia, 5, 1, "x"); // keeps the class "alive"

        List<Insights.Quiet> out = Insights.inactive(Arrays.asList(ana, bia), NOW, 14);

        assertEquals(1, out.size());
        assertEquals("Ana", out.get(0).row.studentName);
        // The last real award is the old one; the cancelled one and its reversal are ignored.
        assertEquals(old.createdAt, (long) out.get(0).lastAt);
    }

    @Test
    public void inactive_skipsArchivedClasses() {
        StudentExportData ana = row("Ana", "g1", 0);
        ana.classGroupClosed = true;
        StudentExportData bia = row("Bia", "g1", 5);
        entry(bia, 5, 1, "x");
        assertTrue(Insights.inactive(Arrays.asList(ana, bia), NOW, 14).isEmpty());
    }

    /* ------------------------------ weekly summary ------------------------------ */

    @Test
    public void weeklySummary_countsTheLastSevenDaysAgainstTheSevenBefore() {
        StudentExportData ana = row("Ana", "g1", 0);
        entry(ana, 5, 1, "Participação");
        entry(ana, 3, 3, "Participação");
        entry(ana, -2, 2, "Atraso");
        entry(ana, 10, 9, "Atividade");   // previous week
        entry(ana, 50, 20, "Atividade");  // older: counted nowhere
        StudentExportData bia = row("Bia", "g1", 0);
        entry(bia, 4, 4, "Atividade");

        Insights.Week w = Insights.weeklySummary(Arrays.asList(ana, bia), NOW);

        assertEquals(4, w.entries);
        assertEquals(10, w.net);
        assertEquals(12, w.given);
        assertEquals(2, w.deducted);
        assertEquals(2, w.students);
        assertEquals(10, w.prevNet);
        assertEquals(1, w.prevEntries);
        assertEquals(2, w.top.size());
        assertEquals("Ana", w.top.get(0).name);
        assertEquals(6, w.top.get(0).points);
        assertEquals("Participação", w.tags.get(0).tag);
        assertEquals(2, w.tags.get(0).entries);
    }

    @Test
    public void weeklySummary_leavesOutCancelledEntriesAndTheirReversals() {
        StudentExportData ana = row("Ana", "g1", 0);
        PointsHistory mistake = entry(ana, 8, 2, "Atividade");
        cancel(ana, mistake, 1);
        entry(ana, 2, 1, "Participação");

        Insights.Week w = Insights.weeklySummary(Arrays.asList(ana), NOW);

        assertEquals(1, w.entries);
        assertEquals(2, w.net);
        assertEquals(2, w.given);
        assertEquals(0, w.deducted);
        assertEquals(1, w.tags.size());
    }

    @Test
    public void weeklySummary_topIsLimitedToThreeStudentsWithPositivePoints() {
        List<StudentExportData> rows = new ArrayList<>();
        String[] names = {"A", "B", "C", "D", "E"};
        for (int i = 0; i < names.length; i++) {
            StudentExportData r = row(names[i], "g1", 0);
            entry(r, (i + 1) * 2, 1, null);
            rows.add(r);
        }
        StudentExportData negative = row("N", "g1", 0);
        entry(negative, -5, 1, null);
        rows.add(negative);

        Insights.Week w = Insights.weeklySummary(rows, NOW);

        assertEquals(3, w.top.size());
        assertEquals("E", w.top.get(0).name);
        assertEquals("D", w.top.get(1).name);
        assertEquals("C", w.top.get(2).name);
        assertEquals(6, w.students);
        assertTrue(w.tags.isEmpty());
    }

    @Test
    public void weeklySummary_aWeekWithNothingIsAllZeros() {
        Insights.Week w = Insights.weeklySummary(Arrays.asList(row("Ana", "g1", 0)), NOW);
        assertEquals(0, w.entries);
        assertEquals(0, w.net);
        assertTrue(w.top.isEmpty());
    }
}
