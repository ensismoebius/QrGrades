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

/**
 * A read-only snapshot of how close one specific student is to reaching one {@link Goal}.
 * Unlike {@code Goal} (which just stores the target and the window), this class combines them
 * with the points the student collected inside that window to answer "has this student achieved
 * this goal yet, and if not, how many points do they still need, and for how long?". It is
 * computed on the fly, not stored in the database (see {@link org.dedira.qrnotas.util.GoalTracker}).
 */
public class GoalProgress {
    /** The goal has no dates (it was saved before goals had them): it counts the whole period's total. */
    public static final int STATE_OPEN = 0;
    /** The window hasn't started yet, so nothing counts so far. */
    public static final int STATE_UPCOMING = 1;
    /** Today is inside the window: points are still being collected. */
    public static final int STATE_ACTIVE = 2;
    /** The window is over: the result is final and can't change any more. */
    public static final int STATE_ENDED = 3;

    public String goalName;
    public int targetPoints;
    public int currentPoints; // Points that count towards this goal: the ones awarded inside its window.
    public int remaining; // How many more points the student needs to reach the target; 0 once achieved.
    public boolean achieved; // True once the counted points reached (or passed) targetPoints.
    public String startDate; // Local "yyyy-MM-dd"; null for a goal without dates.
    public String endDate; // Local "yyyy-MM-dd", included; null for a goal without dates.
    public int state = STATE_OPEN;
    public int daysLeft; // Only while ACTIVE: whole days until the last day (0 = it ends today).

    /** A goal without a window: {@code currentPoints} is simply the period's running total. */
    public GoalProgress(String goalName, int targetPoints, int currentPoints) {
        this.goalName = goalName;
        this.targetPoints = targetPoints;
        this.currentPoints = currentPoints;
        // The goal is achieved as soon as the student's current points meet or exceed the target.
        this.achieved = currentPoints >= targetPoints;
        // If already achieved there's nothing left to do; otherwise compute the point gap.
        this.remaining = this.achieved ? 0 : (targetPoints - currentPoints);
    }

    /** A goal with a window; {@code state} and {@code daysLeft} say where today falls in it. */
    public GoalProgress(String goalName, int targetPoints, int currentPoints,
                        String startDate, String endDate, int state, int daysLeft) {
        this(goalName, targetPoints, currentPoints);
        this.startDate = startDate;
        this.endDate = endDate;
        this.state = state;
        this.daysLeft = daysLeft;
    }

    public boolean hasDates() {
        return startDate != null && endDate != null;
    }

    /** True while the goal can still change: it has no dates, or its window is open right now. */
    public boolean isCounting() {
        return state == STATE_OPEN || state == STATE_ACTIVE;
    }

    /** True for a finished goal that the student didn't reach. */
    public boolean isMissed() {
        return state == STATE_ENDED && !achieved;
    }

    /** The state as the JSON/web UI spells it. */
    public String stateName() {
        return nameOf(state);
    }

    public static String nameOf(int state) {
        switch (state) {
            case STATE_UPCOMING:
                return "upcoming";
            case STATE_ACTIVE:
                return "active";
            case STATE_ENDED:
                return "ended";
            default:
                return "open";
        }
    }
}
