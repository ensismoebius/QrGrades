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

package org.dedira.qrnotas.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link GoalProgress}, which compares a student's current point total against
 * a goal's target to compute whether the goal is {@code achieved} and, if not, how many points
 * are still {@code remaining}. The constructor under test is
 * {@code GoalProgress(String goalName, int targetPoints, int currentPoints)}. These tests cover
 * the boundary behavior around the target: below it, exactly at it, above it, and the edge case
 * of a zero-point target.
 */
public class GoalProgressTest {

    // Verifies that when current points are below the target, the goal is not achieved and
    // "remaining" is the positive difference between target and current points (10 - 4 = 6).
    @Test
    public void belowTarget_notAchievedAndRemainingIsPositive() {
        GoalProgress g = new GoalProgress("R", 10, 4);
        assertFalse(g.achieved);
        assertEquals(6, g.remaining);
    }

    // Verifies that when current points exactly equal the target, the goal counts as achieved
    // and there are zero points remaining (the ">=" comparison in the constructor includes
    // the equal case).
    @Test
    public void exactlyAtTarget_isAchieved() {
        GoalProgress g = new GoalProgress("R", 10, 10);
        assertTrue(g.achieved);
        assertEquals(0, g.remaining);
    }

    // Verifies that when current points exceed the target, the goal still counts as achieved
    // and "remaining" clamps to zero rather than going negative.
    @Test
    public void aboveTarget_isAchievedAndRemainingIsZero() {
        GoalProgress g = new GoalProgress("R", 10, 15);
        assertTrue(g.achieved);
        assertEquals(0, g.remaining);
    }

    // Verifies the edge case of a goal with a target of zero points: since 0 current points
    // already meets a 0-point target, the goal is immediately achieved with nothing remaining.
    @Test
    public void zeroTarget_alwaysAchieved() {
        GoalProgress g = new GoalProgress("R", 0, 0);
        assertTrue(g.achieved);
        assertEquals(0, g.remaining);
    }
}
