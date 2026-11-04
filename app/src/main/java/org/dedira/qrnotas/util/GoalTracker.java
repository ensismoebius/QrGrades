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

import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Works out where a student stands on each {@link Goal}. Pure data in, pure data out — no
 * database or Android — so the window rules can be unit tested on their own.
 *
 * <p>A goal covers the days from its start date to its end date, both included, and only the
 * points awarded on those days count. Days are local "yyyy-MM-dd" strings, so a class day never
 * shifts with the time zone and plain string comparison orders them correctly. A goal saved
 * before dates existed has no window and keeps counting the period's running total.
 */
public final class GoalTracker {

    private static final double DAY_MS = 24.0 * 60 * 60 * 1000;

    private GoalTracker() {
    }

    /** Where {@code today} falls against the window: not started, running, over — or no window at all. */
    public static int stateOf(String startDate, String endDate, String today) {
        if (startDate == null || endDate == null) return GoalProgress.STATE_OPEN;
        if (today.compareTo(startDate) < 0) return GoalProgress.STATE_UPCOMING;
        if (today.compareTo(endDate) > 0) return GoalProgress.STATE_ENDED;
        return GoalProgress.STATE_ACTIVE;
    }

    /** Whole calendar days from one "yyyy-MM-dd" day to another; negative if {@code to} is the earlier one. */
    public static int daysBetween(String from, String to) {
        // Both days are read as UTC midnights, so a daylight-saving change can't turn a day into 23 or 25 hours.
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            long a = format.parse(from).getTime();
            long b = format.parse(to).getTime();
            return (int) Math.round((b - a) / DAY_MS);
        } catch (ParseException e) {
            return 0;
        }
    }

    /**
     * The net points that count for a window: the sum of the entries made on a day from
     * {@code startDate} to {@code endDate} (a null side is open). A cancelling entry counts on the
     * day of the entry it cancels, so a mistake corrected later nets to zero where it was made
     * instead of leaving the points inside the window and the correction outside it.
     *
     * @param history every entry of the enrollment, cancelling ones and the entries they cancel included
     */
    public static int pointsWithin(List<PointsHistory> history, String startDate, String endDate) {
        if (history == null || history.isEmpty()) return 0;

        Map<String, Long> createdAtById = new HashMap<>();
        for (PointsHistory h : history) {
            if (h.id != null) createdAtById.put(h.id, h.createdAt);
        }

        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        int total = 0;
        for (PointsHistory h : history) {
            long at = h.createdAt;
            if (h.revertsId != null) {
                Long original = createdAtById.get(h.revertsId);
                if (original != null) at = original;
            }
            String day = format.format(new Date(at));
            boolean afterStart = startDate == null || day.compareTo(startDate) >= 0;
            boolean beforeEnd = endDate == null || day.compareTo(endDate) <= 0;
            if (afterStart && beforeEnd) total += h.pointsDelta;
        }
        return total;
    }

    /**
     * One goal's progress for one enrollment.
     *
     * @param history      the enrollment's entries (see {@link #pointsWithin})
     * @param periodPoints the enrollment's running total for the period; only used by a goal without dates
     * @param today        the local day to judge the window against
     * @param periodClosed true when this is a closed period's report: its goals are final, even one that ends today
     */
    public static GoalProgress progress(Goal goal, List<PointsHistory> history, int periodPoints,
                                        String today, boolean periodClosed) {
        int target = goal.targetPoints == null ? 0 : goal.targetPoints;
        if (!goal.hasDates()) return new GoalProgress(goal.name, target, periodPoints);

        int counted = pointsWithin(history, goal.startDate, goal.endDate);
        int state = stateOf(goal.startDate, goal.endDate, today);
        if (periodClosed && state == GoalProgress.STATE_ACTIVE) state = GoalProgress.STATE_ENDED;
        int daysLeft = state == GoalProgress.STATE_ACTIVE ? Math.max(0, daysBetween(today, goal.endDate)) : 0;
        return new GoalProgress(goal.name, target, counted, goal.startDate, goal.endDate, state, daysLeft);
    }

    /** Progress on every goal, in the order a screen wants them: running goals first, then those yet to start, then the finished ones. */
    public static List<GoalProgress> progressFor(List<Goal> goals, List<PointsHistory> history, int periodPoints, String today) {
        List<GoalProgress> list = new ArrayList<>();
        if (goals == null) return list;
        for (Goal goal : goals) list.add(progress(goal, history, periodPoints, today, false));
        sortForDisplay(list);
        return list;
    }

    /**
     * Running goals (soonest to end first, easiest target first), then upcoming ones (soonest to
     * start first), then finished ones (most recently finished first).
     */
    public static void sortForDisplay(List<GoalProgress> list) {
        Collections.sort(list, new Comparator<GoalProgress>() {
            @Override
            public int compare(GoalProgress a, GoalProgress b) {
                int byGroup = group(a) - group(b);
                if (byGroup != 0) return byGroup;
                if (a.state == GoalProgress.STATE_ENDED) {
                    int byEnd = b.endDate.compareTo(a.endDate);
                    if (byEnd != 0) return byEnd;
                } else if (a.state == GoalProgress.STATE_UPCOMING) {
                    int byStart = a.startDate.compareTo(b.startDate);
                    if (byStart != 0) return byStart;
                } else {
                    // A goal without dates never ends: it sorts after the running ones that do.
                    int byEnd = (a.endDate == null ? "9999-12-31" : a.endDate)
                            .compareTo(b.endDate == null ? "9999-12-31" : b.endDate);
                    if (byEnd != 0) return byEnd;
                }
                int byTarget = Integer.compare(a.targetPoints, b.targetPoints);
                if (byTarget != 0) return byTarget;
                return String.valueOf(a.goalName).compareToIgnoreCase(String.valueOf(b.goalName));
            }

            private int group(GoalProgress g) {
                switch (g.state) {
                    case GoalProgress.STATE_UPCOMING:
                        return 1;
                    case GoalProgress.STATE_ENDED:
                        return 2;
                    default:
                        return 0;
                }
            }
        });
    }
}
