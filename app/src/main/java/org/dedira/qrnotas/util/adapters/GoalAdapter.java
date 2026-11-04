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

package org.dedira.qrnotas.util.adapters;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.GoalTracker;
import org.dedira.qrnotas.util.GoalViews;
import org.dedira.qrnotas.util.ThemeColors;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Feeds {@link org.dedira.qrnotas.fragments.GoalListFragment}'s RecyclerView: shows one row per
 * goal ("R: 10 points, from 1 Feb to 30 Apr" style) for a discipline, with edit/delete buttons on
 * each row and an add/edit dialog built programmatically (no dedicated XML layout for it). Every
 * goal needs a start and an end date, so the dialog won't save without them.
 */
public class GoalAdapter extends RecyclerView.Adapter<GoalAdapter.ViewHolder> {

    private final Context context;
    private final Database database;
    private final String disciplineId;
    private final List<Goal> goals = new ArrayList<>();

    public GoalAdapter(Context context, Database database, String disciplineId) {
        this.context = context;
        this.database = database;
        this.disciplineId = disciplineId;
    }

    /** Replaces the shown list, using DiffUtil so the RecyclerView animates only the rows that actually changed. */
    public void submitList(List<Goal> newList) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new GoalDiffCallback(goals, newList));
        goals.clear();
        goals.addAll(newList);
        result.dispatchUpdatesTo(this);
    }

    public void showAddDialog() {
        showEditDialog(null);
    }

    /**
     * The window a new goal starts out with: the one of the goal that ends last, if it hasn't
     * ended yet (so goals added one after another share a window), otherwise the next 30 days.
     */
    private String[] defaultWindow() {
        String today = Attendance.today();
        Goal latest = null;
        for (Goal g : goals) {
            if (g.hasValidDates() && (latest == null || g.endDate.compareTo(latest.endDate) > 0)) latest = g;
        }
        if (latest != null && latest.endDate.compareTo(today) >= 0) return new String[]{latest.startDate, latest.endDate};

        Calendar in30 = Calendar.getInstance();
        in30.add(Calendar.DAY_OF_MONTH, 30);
        return new String[]{today, Attendance.dayOf(in30.getTimeInMillis())};
    }

    /** Told which day a date field was just set to. (Not java.util.function.Consumer: that needs API 24, the app supports 21.) */
    private interface DayListener {
        void onPicked(String day);
    }

    /** A read-only field that shows a day and opens a date picker when tapped; {@code onPicked} gets the chosen "yyyy-MM-dd". */
    private EditText dayField(int hint, String day, DayListener onPicked) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_NULL);
        field.setFocusable(false);
        field.setClickable(true);
        field.setText(GoalViews.day(context, day));
        // A calendar at the end says the field is tapped, not typed into.
        field.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_calendar, 0);
        // Kept by the field itself so a second tap opens the picker on the day shown, not on today.
        field.setTag(day);
        field.setOnClickListener(v -> {
            String[] parts = String.valueOf(field.getTag()).split("-");
            DatePickerDialog picker = new DatePickerDialog(context, (view, year, month, dayOfMonth) -> {
                String chosen = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                field.setTag(chosen);
                field.setText(GoalViews.day(context, chosen));
                field.setError(null);
                onPicked.onPicked(chosen);
            }, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
            picker.show();
        });
        return field;
    }

    /**
     * Builds and shows a small dialog with a name field, a numeric target-points field and the
     * start and end days, pre-filled when editing an existing goal. Saving persists via
     * {@link Database#saveGoal} and re-sorts the local list so goals display soonest-ending first;
     * it stays open, with the problem marked, until the name, target and a valid window are given.
     */
    private void showEditDialog(Goal existing) {
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);

        EditText nameInput = new EditText(context);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameInput.setHint(R.string.goal_name_hint);
        if (existing != null) nameInput.setText(existing.name);
        layout.addView(nameInput);

        EditText targetInput = new EditText(context);
        targetInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        targetInput.setHint(R.string.goal_target_hint);
        if (existing != null) targetInput.setText(String.valueOf(existing.targetPoints));
        layout.addView(targetInput);

        // A goal saved before dates existed has none to show: it starts from the suggested window.
        String[] window = existing != null && existing.hasValidDates()
                ? new String[]{existing.startDate, existing.endDate} : defaultWindow();
        EditText endInput = dayField(R.string.goal_end_hint, window[1], day -> window[1] = day);
        EditText startInput = dayField(R.string.goal_start_hint, window[0], day -> {
            window[0] = day;
            // Starting after the current end would be an empty window: move the end along with it.
            if (window[1].compareTo(day) < 0) {
                window[1] = day;
                endInput.setTag(day);
                endInput.setText(GoalViews.day(context, day));
            }
        });
        // The value hides a hint, so each date gets a caption saying which one it is.
        layout.addView(caption(R.string.goal_start_hint, padding));
        layout.addView(startInput);
        layout.addView(caption(R.string.goal_end_hint, padding));
        layout.addView(endInput);

        TextView help = new TextView(context);
        help.setText(R.string.goal_window_help);
        help.setPadding(0, padding / 2, 0, 0);
        layout.addView(help);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(existing == null ? R.string.add_goal : R.string.edit_goal)
                .setView(layout)
                .setPositiveButton(R.string.save, null) // wired below, so a mistake doesn't close the dialog
                .setNegativeButton(android.R.string.cancel, null)
                .show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String targetText = targetInput.getText().toString().trim();
            if (name.isEmpty()) {
                nameInput.setError(context.getString(R.string.name_required));
                return;
            }
            int target;
            try {
                target = Integer.parseInt(targetText);
            } catch (NumberFormatException e) {
                targetInput.setError(context.getString(R.string.goal_target_required));
                return;
            }
            if (window[0].compareTo(window[1]) > 0) {
                endInput.setError(context.getString(R.string.goal_dates_order));
                return;
            }

            // A copy, not the row's own object: the list diff must still see the old values to know the row changed.
            Goal g = new Goal();
            g.id = existing != null ? existing.id : null; // null: the database assigns an id on save
            g.disciplineId = disciplineId;
            g.name = name;
            g.targetPoints = target;
            g.startDate = window[0];
            g.endDate = window[1];
            database.saveGoal(g, (success, saved) -> {
                if (!success) {
                    Toast.makeText(context, R.string.save_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                List<Goal> updated = new ArrayList<>(goals);
                if (existing == null) {
                    updated.add(saved);
                } else {
                    updated.set(updated.indexOf(existing), saved);
                }
                sortByWindow(updated);
                submitList(updated);
            });
            dialog.dismiss();
        });

        nameInput.requestFocus();
        // Forces the keyboard to appear immediately since the dialog itself doesn't trigger it automatically.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    /** A small muted label above a field. */
    private TextView caption(int text, int padding) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(ThemeColors.resolve(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
        label.setPadding(0, padding / 2, 0, 0);
        return label;
    }

    /** The order the database loads goals in: soonest-ending window first, then easiest target. */
    private static void sortByWindow(List<Goal> list) {
        Collections.sort(list, (a, b) -> {
            String endA = a.endDate == null ? "" : a.endDate;
            String endB = b.endDate == null ? "" : b.endDate;
            int byEnd = endA.compareTo(endB);
            if (byEnd != 0) return byEnd;
            String startA = a.startDate == null ? "" : a.startDate;
            String startB = b.startDate == null ? "" : b.startDate;
            int byStart = startA.compareTo(startB);
            if (byStart != 0) return byStart;
            return a.targetPoints - b.targetPoints;
        });
    }

    /**
     * Confirms and performs deletion of the goal at {@code position}. If the user cancels or the
     * database call fails, the row is re-notified so any swipe/press visual state resets.
     */
    private void requestDelete(int position) {
        Goal g = goals.get(position);
        new AlertDialog.Builder(context)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(context.getString(R.string.confirm_delete_message, g.name))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setOnCancelListener(dialog -> notifyItemChanged(position))
                .setPositiveButton(R.string.delete_confirm_action, (dialog, which) -> database.deleteGoal(g.id, (success, object) -> {
                    if (success) {
                        List<Goal> updated = new ArrayList<>(goals);
                        updated.remove(g);
                        submitList(updated);
                        Toast.makeText(context, R.string.entity_deleted, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show();
                        notifyItemChanged(position);
                    }
                }))
                .setNegativeButton(R.string.cancel, (dialog, which) -> notifyItemChanged(position))
                .show();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Same "name + subtitle + edit/delete buttons" row as the other entity lists, plus a line for the goal's window.
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_goal, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Goal g = goals.get(position);
        holder.txtName.setText(g.name);
        holder.subtitle.setVisibility(View.VISIBLE);
        holder.subtitle.setText(context.getString(R.string.goal_target_points, g.targetPoints));
        bindWindow(holder.window, g);
        // Goal rows aren't tappable (no detail screen to open) — only the edit/delete buttons act.
        holder.itemView.setOnClickListener(null);
        holder.itemView.setClickable(false);

        holder.btnEdit.setOnClickListener(v -> showEditDialog(g));
        // getBindingAdapterPosition() (not the "position" param) is used here because it reflects
        // the row's *current* position at click time, which can differ if the list changed since binding.
        holder.btnDelete.setOnClickListener(v -> requestDelete(holder.getBindingAdapterPosition()));
    }

    /** "01/02/2026 – 30/04/2026 · In progress"; a goal without dates asks for them, in the error color. */
    private void bindWindow(TextView view, Goal g) {
        if (!g.hasDates()) {
            view.setText(R.string.goal_without_dates);
            view.setTextColor(ThemeColors.resolve(context, com.google.android.material.R.attr.colorError));
            return;
        }
        int state = GoalTracker.stateOf(g.startDate, g.endDate, Attendance.today());
        int label = state == GoalProgress.STATE_UPCOMING ? R.string.goal_window_state_upcoming
                : state == GoalProgress.STATE_ENDED ? R.string.goal_window_state_ended
                : R.string.goal_window_state_active;
        // Two lines — the days, then where today falls — because beside the points and the buttons there is no room for one.
        view.setText(context.getString(R.string.goal_window_line_stacked,
                GoalViews.day(context, g.startDate), GoalViews.day(context, g.endDate), context.getString(label)));
        view.setTextColor(ThemeColors.resolve(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
    }

    @Override
    public int getItemCount() {
        return goals.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtName;
        final TextView subtitle;
        final TextView window;
        final ImageButton btnEdit;
        final ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtEntityName);
            subtitle = itemView.findViewById(R.id.txtEntitySubtitle);
            window = itemView.findViewById(R.id.txtGoalWindow);
            btnEdit = itemView.findViewById(R.id.btnEditEntity);
            btnDelete = itemView.findViewById(R.id.btnDeleteEntity);
        }
    }

    /** Tells DiffUtil which goals are "the same" (by id) vs. merely equal in content (name/target), so it can animate updates vs. inserts/removals correctly. */
    private static class GoalDiffCallback extends DiffUtil.Callback {
        private final List<Goal> oldList;
        private final List<Goal> newList;

        GoalDiffCallback(List<Goal> oldList, List<Goal> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return oldList.get(oldItemPosition).id.equals(newList.get(newItemPosition).id);
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            Goal a = oldList.get(oldItemPosition);
            Goal b = newList.get(newItemPosition);
            return Objects.equals(a.name, b.name) && Objects.equals(a.targetPoints, b.targetPoints)
                    && Objects.equals(a.startDate, b.startDate) && Objects.equals(a.endDate, b.endDate);
        }
    }
}
