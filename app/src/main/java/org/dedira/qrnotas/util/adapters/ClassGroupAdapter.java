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
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.activities.StudentList;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.util.Database;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Feeds the "class groups" list inside a discipline's detail screen — each row is one class
 * group (e.g. "Morning A"), tappable to open its student list, with Edit/Delete actions.
 * <p>
 * This is a {@link RecyclerView.Adapter}, Android's standard way to display a scrolling list
 * efficiently without creating a new row view for every item:
 * <ul>
 *     <li>{@link #onCreateViewHolder} builds one empty row layout from XML and wraps its
 *     views in a {@link ViewHolder}. Called only enough times to fill the visible screen.</li>
 *     <li>{@link #onBindViewHolder} fills a reused row with the data for a given list
 *     position every time that row scrolls into view.</li>
 *     <li>{@link ViewHolder} caches the row's child views so {@code findViewById} runs once
 *     per row instead of on every scroll, which keeps scrolling smooth.</li>
 * </ul>
 */
public class ClassGroupAdapter extends RecyclerView.Adapter<ClassGroupAdapter.ViewHolder> {

    private final Context context;
    private final Database database;
    private final String disciplineId;
    private final List<ClassGroup> groups = new ArrayList<>();

    public ClassGroupAdapter(Context context, Database database, String disciplineId) {
        this.context = context;
        this.database = database;
        this.disciplineId = disciplineId;
    }

    /**
     * Replaces the visible list of class groups with a new one, using {@link DiffUtil} to
     * compute exactly which rows were added/removed/changed instead of redrawing everything.
     */
    public void submitList(List<ClassGroup> newList) {
        // DiffUtil compares old vs new lists in the background-friendly way and returns a
        // precise diff, which is far cheaper (and animates nicer) than notifyDataSetChanged().
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new GroupDiffCallback(groups, newList));
        groups.clear();
        groups.addAll(newList);
        // Applies the diff to this adapter, firing only the specific insert/remove/change
        // notifications needed so the RecyclerView animates and redraws minimally.
        result.dispatchUpdatesTo(this);
    }

    /** Opens the "add new class group" dialog (an edit dialog with no pre-filled group). */
    public void showAddDialog() {
        showEditDialog(null);
    }

    /**
     * Shows a text-input dialog to create a new class group, or edit an existing one's name
     * if {@code existing} is non-null. Saving persists to the database and either inserts the
     * new row (via {@link #submitList}) or refreshes the edited row in place.
     */
    private void showEditDialog(ClassGroup existing) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        if (existing != null) input.setText(existing.name);
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(existing == null ? R.string.add_group : R.string.edit_group)
                .setView(input)
                .setPositiveButton(R.string.save, (d, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;

                    ClassGroup g = existing != null ? existing : new ClassGroup();
                    g.name = name;
                    g.disciplineId = disciplineId;
                    database.saveClassGroup(g, (success, saved) -> {
                        if (!success) {
                            Toast.makeText(context, R.string.save_failed, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (existing == null) {
                            List<ClassGroup> updated = new ArrayList<>(groups);
                            updated.add(saved);
                            submitList(updated);
                        } else {
                            notifyItemChanged(groups.indexOf(existing));
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        input.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    /**
     * Asks the user to confirm deleting a class group, then deletes it from the database and
     * removes its row. If the delete fails (e.g. the group still has students in it), the row
     * is redrawn instead of removed so it doesn't disappear from the list incorrectly.
     */
    private void requestDelete(int position) {
        ClassGroup g = groups.get(position);
        new AlertDialog.Builder(context)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(context.getString(R.string.confirm_delete_message, g.name))
                .setIcon(android.R.drawable.ic_dialog_alert)
                // If the user backs out of the dialog (taps outside it), redraw the row in
                // case it was left in a stale visual state.
                .setOnCancelListener(dialog -> notifyItemChanged(position))
                .setPositiveButton(R.string.delete_confirm_action, (dialog, which) -> database.deleteClassGroup(g.id, (success, reason) -> {
                    if (success) {
                        List<ClassGroup> updated = new ArrayList<>(groups);
                        updated.remove(g);
                        submitList(updated);
                        Toast.makeText(context, R.string.entity_deleted, Toast.LENGTH_SHORT).show();
                    } else {
                        // The database can refuse deletion (e.g. group still has students).
                        // Show why, and redraw this single row rather than removing it since
                        // it's still present in the underlying list.
                        int messageRes = "HAS_STUDENTS".equals(reason) ? R.string.group_has_students : R.string.delete_failed;
                        Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show();
                        notifyItemChanged(position);
                    }
                }))
                .setNegativeButton(R.string.cancel, (dialog, which) -> notifyItemChanged(position))
                .show();
    }

    /** Inflates (builds from XML) one empty row layout and wraps it in a reusable {@link ViewHolder}. */
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_named_entity, parent, false);
        return new ViewHolder(view);
    }

    /** Fills a recycled row with the class group's data and wires up its click actions. */
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ClassGroup g = groups.get(position);
        holder.txtName.setText(g.name);
        // This shared row layout has an optional subtitle line; class groups don't use it.
        holder.subtitle.setVisibility(View.GONE);
        // Tapping the row (outside the edit/delete buttons) opens the student list for
        // this class group, passing its id/name via Intent extras.
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, StudentList.class);
            intent.putExtra("classGroupId", g.id);
            intent.putExtra("groupName", g.name);
            context.startActivity(intent);
        });
        holder.itemView.setClickable(true);

        holder.btnEdit.setOnClickListener(v -> showEditDialog(g));
        // getBindingAdapterPosition() is used instead of the "position" parameter because by
        // the time the button is tapped, the list may have changed (rows added/removed) and
        // this always reflects the row's current position.
        holder.btnDelete.setOnClickListener(v -> requestDelete(holder.getBindingAdapterPosition()));
    }

    /** Tells the RecyclerView how many rows to show. */
    @Override
    public int getItemCount() {
        return groups.size();
    }

    // Holds references to one row's child views, found via findViewById() only once (in the
    // constructor) rather than on every bind/scroll — this is what makes RecyclerView fast.
    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtName;
        final TextView subtitle;
        final ImageButton btnEdit;
        final ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtEntityName);
            subtitle = itemView.findViewById(R.id.txtEntitySubtitle);
            btnEdit = itemView.findViewById(R.id.btnEditEntity);
            btnDelete = itemView.findViewById(R.id.btnDeleteEntity);
        }
    }

    // Tells DiffUtil how to compare the old and new group lists so it can compute the minimal
    // set of row changes instead of the adapter redrawing every row on every update.
    private static class GroupDiffCallback extends DiffUtil.Callback {
        private final List<ClassGroup> oldList;
        private final List<ClassGroup> newList;

        GroupDiffCallback(List<ClassGroup> oldList, List<ClassGroup> newList) {
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

        // "Are these the same real-world item?" — compares by stable database id, so a
        // renamed group is still recognized as the same row (not removed+added).
        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return oldList.get(oldItemPosition).id.equals(newList.get(newItemPosition).id);
        }

        // Only called when areItemsTheSame() is true: "did the displayed content change?"
        // Here only the name is shown, so that's all that needs comparing.
        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            return Objects.equals(oldList.get(oldItemPosition).name, newList.get(newItemPosition).name);
        }
    }
}
