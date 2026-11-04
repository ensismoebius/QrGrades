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

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.GoalProgress;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Draws a {@link GoalProgress} into an {@code item_goal_progress} row and words its dates, so the
 * scan screen and the student's progress screen show a goal the same way.
 */
public final class GoalViews {

    private GoalViews() {
    }

    /** A stored "yyyy-MM-dd" day in the date format the user's phone is set to; unreadable input comes back unchanged. */
    public static String day(Context context, String day) {
        if (day == null) return "";
        try {
            Date parsed = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day);
            return android.text.format.DateFormat.getDateFormat(context).format(parsed);
        } catch (ParseException e) {
            return day;
        }
    }

    /** "ends today" / "ends tomorrow" / "ends in 5 days". */
    public static String endsIn(Context context, int daysLeft) {
        if (daysLeft <= 0) return context.getString(R.string.goal_ends_today);
        if (daysLeft == 1) return context.getString(R.string.goal_ends_tomorrow);
        return context.getString(R.string.goal_ends_in_days, daysLeft);
    }

    /**
     * "01/02/2026 – 30/04/2026 · ends in 5 days" — the window plus where today falls in it (and,
     * for a goal that ended unreached, how far the student got). Empty for a goal without dates.
     */
    public static String windowLine(Context context, GoalProgress goal) {
        if (!goal.hasDates()) return "";
        String note;
        switch (goal.state) {
            case GoalProgress.STATE_UPCOMING:
                note = context.getString(R.string.goal_not_started);
                break;
            case GoalProgress.STATE_ENDED:
                note = context.getString(R.string.goal_ended);
                break;
            default:
                note = endsIn(context, goal.daysLeft);
        }
        String line = context.getString(R.string.goal_window_line, day(context, goal.startDate), day(context, goal.endDate), note);
        // A goal that ended unreached: the final tally belongs next to the dates (the status label stays short).
        if (goal.isMissed()) line += " · " + context.getString(R.string.goal_points_of, goal.currentPoints, goal.targetPoints);
        return line;
    }

    /** Fills one {@code item_goal_progress} row: name, window, bar, and a status that says what is left (or that it's done, missed or yet to start). */
    public static void bind(Context context, View row, GoalProgress goal) {
        TextView name = row.findViewById(R.id.txtGoalName);
        TextView status = row.findViewById(R.id.txtGoalStatus);
        TextView dates = row.findViewById(R.id.txtGoalDates);
        View achievedIcon = row.findViewById(R.id.imgGoalAchieved);
        LinearProgressIndicator progress = row.findViewById(R.id.progressGoal);

        int success = ThemeColors.resolve(context, R.attr.colorSuccess);
        int muted = ThemeColors.resolve(context, com.google.android.material.R.attr.colorOnSurfaceVariant);
        int accent = ThemeColors.resolve(context, com.google.android.material.R.attr.colorTertiary);
        int error = ThemeColors.resolve(context, com.google.android.material.R.attr.colorError);

        name.setText(goal.goalName);

        String line = windowLine(context, goal);
        dates.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
        dates.setText(line);

        // The bar shows the points that count for this goal, which for a goal with a window is not the row's total.
        int percent = goal.targetPoints > 0
                ? Math.max(0, Math.min(100, Math.round(100f * goal.currentPoints / goal.targetPoints)))
                : 100;
        progress.setProgress(percent);

        achievedIcon.setVisibility(goal.achieved ? View.VISIBLE : View.GONE);
        if (goal.achieved) {
            status.setText(R.string.goal_achieved);
            status.setTextColor(success);
            progress.setIndicatorColor(success);
        } else if (goal.isMissed()) {
            status.setText(R.string.goal_missed);
            status.setTextColor(error);
            progress.setIndicatorColor(error);
        } else if (goal.state == GoalProgress.STATE_UPCOMING) {
            status.setText(R.string.goal_upcoming);
            status.setTextColor(muted);
            progress.setIndicatorColor(muted);
        } else {
            status.setText(context.getString(R.string.goal_remaining, goal.remaining));
            status.setTextColor(muted);
            progress.setIndicatorColor(accent);
        }
    }
}
