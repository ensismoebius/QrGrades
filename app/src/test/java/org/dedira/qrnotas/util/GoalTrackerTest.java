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

import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** The goal window rules: which days count, how a cancelled entry is attributed, and where today falls. */
public class GoalTrackerTest {

    private TimeZone previousZone;

    @Before
    public void setUp() {
        previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"));
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(previousZone);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static PointsHistory entry(String id, int delta, long at, String revertsId) {
        PointsHistory h = new PointsHistory();
        h.id = id;
        h.pointsDelta = delta;
        h.createdAt = at;
        h.revertsId = revertsId;
        return h;
    }

    private static Goal goal(String name, int target, String start, String end) {
        Goal g = new Goal();
        g.name = name;
        g.targetPoints = target;
        g.startDate = start;
        g.endDate = end;
        return g;
    }

    /* ---------------------------------- state ---------------------------------- */

    @Test
    public void stateOf_saysWhereTodayFallsAgainstTheWindowEdgesIncluded() {
        assertEquals(GoalProgress.STATE_UPCOMING, GoalTracker.stateOf("2026-02-01", "2026-04-30", "2026-01-31"));
        assertEquals(GoalProgress.STATE_ACTIVE, GoalTracker.stateOf("2026-02-01", "2026-04-30", "2026-02-01"));
        assertEquals(GoalProgress.STATE_ACTIVE, GoalTracker.stateOf("2026-02-01", "2026-04-30", "2026-03-15"));
        assertEquals(GoalProgress.STATE_ACTIVE, GoalTracker.stateOf("2026-02-01", "2026-04-30", "2026-04-30"));
        assertEquals(GoalProgress.STATE_ENDED, GoalTracker.stateOf("2026-02-01", "2026-04-30", "2026-05-01"));
    }

    @Test
    public void stateOf_aGoalWithoutDatesIsOpen() {
        assertEquals(GoalProgress.STATE_OPEN, GoalTracker.stateOf(null, null, "2026-03-15"));
        assertEquals(GoalProgress.STATE_OPEN, GoalTracker.stateOf("2026-02-01", null, "2026-03-15"));
    }

    @Test
    public void daysBetween_countsCalendarDaysAcrossMonthsAndYearsAndBackwards() {
        assertEquals(0, GoalTracker.daysBetween("2026-03-15", "2026-03-15"));
        assertEquals(1, GoalTracker.daysBetween("2026-03-15", "2026-03-16"));
        assertEquals(30, GoalTracker.daysBetween("2026-03-01", "2026-03-31"));
        assertEquals(31, GoalTracker.daysBetween("2025-12-25", "2026-01-25"));
        assertEquals(-2, GoalTracker.daysBetween("2026-03-15", "2026-03-13"));
        assertEquals(366, GoalTracker.daysBetween("2024-01-01", "2025-01-01")); // a leap year
    }

    /* ------------------------------ points in a window ------------------------------ */

    @Test
    public void pointsWithin_countsTheFirstAndLastDayWholeAndNothingOutside() {
        List<PointsHistory> history = new ArrayList<>();
        history.add(entry("a", 100, at(2026, 1, 31, 23, 59), null)); // the minute before the window
        history.add(entry("b", 4, at(2026, 2, 1, 0, 0), null));
        history.add(entry("c", 3, at(2026, 3, 15, 12, 0), null));
        history.add(entry("d", 2, at(2026, 4, 30, 23, 59), null));
        history.add(entry("e", 100, at(2026, 5, 1, 0, 0), null)); // the minute after

        assertEquals(9, GoalTracker.pointsWithin(history, "2026-02-01", "2026-04-30"));
    }

    @Test
    public void pointsWithin_deductionsCountAndAnEmptyWindowIsZero() {
        List<PointsHistory> history = new ArrayList<>();
        history.add(entry("a", 10, at(2026, 3, 1, 9, 0), null));
        history.add(entry("b", -4, at(2026, 3, 2, 9, 0), null));

        assertEquals(6, GoalTracker.pointsWithin(history, "2026-03-01", "2026-03-31"));
        assertEquals(0, GoalTracker.pointsWithin(history, "2026-04-01", "2026-04-30"));
        assertEquals(0, GoalTracker.pointsWithin(new ArrayList<PointsHistory>(), "2026-03-01", "2026-03-31"));
        assertEquals(0, GoalTracker.pointsWithin(null, "2026-03-01", "2026-03-31"));
    }

    @Test
    public void pointsWithin_aCancellingEntryCountsOnTheDayOfTheEntryItCancels() {
        List<PointsHistory> history = new ArrayList<>();
        // Given in March, taken back in June: inside a March window both are, and they net to nothing there.
        history.add(entry("award", 5, at(2026, 3, 10, 10, 0), null));
        history.add(entry("undo", -5, at(2026, 6, 20, 10, 0), "award"));
        history.add(entry("kept", 2, at(2026, 3, 11, 10, 0), null));

        assertEquals(2, GoalTracker.pointsWithin(history, "2026-03-01", "2026-03-31"));
        // The June window must not be charged for a mistake made in March.
        assertEquals(0, GoalTracker.pointsWithin(history, "2026-06-01", "2026-06-30"));
    }

    @Test
    public void pointsWithin_aCancellationWhoseOriginalIsMissingCountsOnItsOwnDay() {
        List<PointsHistory> history = new ArrayList<>();
        history.add(entry("undo", -5, at(2026, 6, 20, 10, 0), "gone"));

        assertEquals(-5, GoalTracker.pointsWithin(history, "2026-06-01", "2026-06-30"));
    }

    @Test
    public void pointsWithin_anOpenSideIsUnbounded() {
        List<PointsHistory> history = new ArrayList<>();
        history.add(entry("old", 1, at(2020, 1, 1, 10, 0), null));
        history.add(entry("new", 2, at(2026, 1, 1, 10, 0), null));

        assertEquals(3, GoalTracker.pointsWithin(history, null, null));
        assertEquals(2, GoalTracker.pointsWithin(history, "2025-01-01", null));
        assertEquals(1, GoalTracker.pointsWithin(history, null, "2025-01-01"));
    }

    /* ---------------------------------- progress ---------------------------------- */

    @Test
    public void progress_aRunningGoalJudgesOnlyItsWindowAndSaysHowLongIsLeft() {
        List<PointsHistory> history = new ArrayList<>();
        history.add(entry("before", 20, at(2026, 1, 20, 10, 0), null));
        history.add(entry("inside", 7, at(2026, 3, 2, 10, 0), null));

        GoalProgress p = GoalTracker.progress(goal("MB", 10, "2026-02-01", "2026-04-30"), history, 27, "2026-04-25", false);

        assertEquals(7, p.currentPoints);
        assertEquals(3, p.remaining);
        assertFalse(p.achieved);
        assertEquals(GoalProgress.STATE_ACTIVE, p.state);
        assertEquals(5, p.daysLeft);
        assertTrue(p.isCounting());
        assertEquals("2026-02-01", p.startDate);
        assertEquals("2026-04-30", p.endDate);
    }

    @Test
    public void progress_onTheLastDayZeroDaysAreLeft() {
        GoalProgress p = GoalTracker.progress(goal("R", 10, "2026-02-01", "2026-04-30"), null, 0, "2026-04-30", false);

        assertEquals(GoalProgress.STATE_ACTIVE, p.state);
        assertEquals(0, p.daysLeft);
    }

    @Test
    public void progress_aFinishedGoalIsFinalWhetherOrNotItWasReached() {
        List<PointsHistory> history = new ArrayList<>();
        history.add(entry("a", 12, at(2026, 3, 2, 10, 0), null));
        history.add(entry("late", 50, at(2026, 5, 20, 10, 0), null)); // after the end: too late to count

        GoalProgress reached = GoalTracker.progress(goal("R", 10, "2026-02-01", "2026-04-30"), history, 62, "2026-06-01", false);
        GoalProgress missed = GoalTracker.progress(goal("MB", 30, "2026-02-01", "2026-04-30"), history, 62, "2026-06-01", false);

        assertTrue(reached.achieved);
        assertEquals(GoalProgress.STATE_ENDED, reached.state);
        assertFalse(reached.isMissed());
        assertFalse(missed.achieved);
        assertTrue(missed.isMissed());
        assertEquals(12, missed.currentPoints);
        assertEquals(18, missed.remaining);
        assertFalse(missed.isCounting());
    }

    @Test
    public void progress_anUpcomingGoalHasNothingYet() {
        GoalProgress p = GoalTracker.progress(goal("R", 10, "2026-08-01", "2026-10-31"), new ArrayList<PointsHistory>(), 15, "2026-06-01", false);

        assertEquals(GoalProgress.STATE_UPCOMING, p.state);
        assertEquals(0, p.currentPoints);
        assertEquals(10, p.remaining);
        assertFalse(p.isCounting());
        assertFalse(p.isMissed());
    }

    @Test
    public void progress_inAClosedPeriodAGoalEndingTodayIsAlreadyFinal() {
        Goal g = goal("R", 10, "2026-02-01", "2026-04-30");

        assertEquals(GoalProgress.STATE_ACTIVE, GoalTracker.progress(g, null, 0, "2026-04-30", false).state);
        assertEquals(GoalProgress.STATE_ENDED, GoalTracker.progress(g, null, 0, "2026-04-30", true).state);
    }

    @Test
    public void progress_aGoalWithoutDatesJudgesThePeriodsRunningTotal() {
        List<PointsHistory> history = new ArrayList<>();
        history.add(entry("a", 99, at(2026, 3, 2, 10, 0), null)); // ignored: no window to look at

        GoalProgress p = GoalTracker.progress(goal("R", 10, null, null), history, 12, "2026-06-01", false);

        assertEquals(GoalProgress.STATE_OPEN, p.state);
        assertEquals(12, p.currentPoints);
        assertTrue(p.achieved);
        assertFalse(p.hasDates());
        assertTrue(p.isCounting());
    }

    @Test
    public void progress_aSingleDayWindowCountsThatDay() {
        List<PointsHistory> history = new ArrayList<>();
        history.add(entry("a", 3, at(2026, 4, 10, 8, 0), null));
        history.add(entry("b", 4, at(2026, 4, 11, 8, 0), null));

        GoalProgress p = GoalTracker.progress(goal("Dia", 3, "2026-04-10", "2026-04-10"), history, 7, "2026-04-10", false);

        assertEquals(3, p.currentPoints);
        assertTrue(p.achieved);
    }

    /* ------------------------------------ order ------------------------------------ */

    @Test
    public void progressFor_putsRunningGoalsFirstThenUpcomingThenFinishedMostRecentFirst() {
        List<Goal> goals = new ArrayList<>();
        goals.add(goal("Encerrada antiga", 10, "2026-01-01", "2026-01-31"));
        goals.add(goal("Futura", 10, "2026-09-01", "2026-09-30"));
        goals.add(goal("Em andamento MB", 30, "2026-05-01", "2026-07-31"));
        goals.add(goal("Encerrada recente", 10, "2026-02-01", "2026-04-30"));
        goals.add(goal("Em andamento R", 10, "2026-05-01", "2026-07-31"));
        goals.add(goal("Sem datas", 20, null, null));

        List<GoalProgress> sorted = GoalTracker.progressFor(goals, new ArrayList<PointsHistory>(), 0, "2026-06-15");

        List<String> names = new ArrayList<>();
        for (GoalProgress p : sorted) names.add(p.goalName);
        assertEquals("[Em andamento R, Em andamento MB, Sem datas, Futura, Encerrada recente, Encerrada antiga]", names.toString());
    }

    @Test
    public void progressFor_withoutGoalsIsEmpty() {
        assertTrue(GoalTracker.progressFor(null, null, 0, "2026-06-15").isEmpty());
    }
}
