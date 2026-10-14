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
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.dedira.qrnotas.R;
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
    private final Runnable onPeriodClosed;
    private final List<ClassGroup> groups = new ArrayList<>();

    /**
     * @param onPeriodClosed called after "Fechar período e turma" successfully closes a group,
     *                       since that can both change this row (closedAt) and create a brand
     *                       new one elsewhere in the list — simplest for the owning screen to
     *                       just reload everything from the database rather than patch the list
     *                       in place.
     */
    public ClassGroupAdapter(Context context, Database database, String disciplineId, Runnable onPeriodClosed) {
        this.context = context;
        this.database = database;
        this.disciplineId = disciplineId;
        this.onPeriodClosed = onPeriodClosed;
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

    /**
     * "Fechar período e turma": shows a dialog letting the teacher pick whether this class
     * group's students are archived in place or moved into a brand-new class group, then calls
     * {@link Database#closeClassGroupPeriod}. Triggered by long-pressing an active row — a plain
     * icon button wasn't used here (unlike Edit/Delete) since this is a bigger, less frequent,
     * more consequential action than a rename/delete, and this row layout is shared with
     * Discipline/Goal rows that have no use for it.
     */
    private void showClosePeriodDialog(ClassGroup g) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_close_class_period, null);
        TextInputEditText txtLabel = view.findViewById(R.id.txtClosePeriodLabel);
        RadioGroup radioGroup = view.findViewById(R.id.radioClosePeriodOption);
        TextInputLayout layoutNewName = view.findViewById(R.id.layoutClosePeriodNewName);
        TextInputEditText txtNewName = view.findViewById(R.id.txtClosePeriodNewName);

        radioGroup.setOnCheckedChangeListener((group, checkedId) ->
                layoutNewName.setVisibility(checkedId == R.id.radioReassign ? View.VISIBLE : View.GONE));

        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.close_class_period_title, g.name))
                .setView(view)
                .setPositiveButton(R.string.close_period, (dialog, which) -> {
                    String label = txtLabel.getText() == null ? "" : txtLabel.getText().toString().trim();
                    boolean reassign = radioGroup.getCheckedRadioButtonId() == R.id.radioReassign;
                    String newName = txtNewName.getText() == null ? "" : txtNewName.getText().toString().trim();
                    if (reassign && newName.isEmpty()) {
                        Toast.makeText(context, R.string.close_class_period_new_name_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    database.closeClassGroupPeriod(g.id, label.isEmpty() ? null : label, reassign ? newName : null,
                            (success, period) -> {
                                Toast.makeText(context, success ? R.string.close_class_period_succeeded : R.string.close_class_period_failed, Toast.LENGTH_SHORT).show();
                                if (success) onPeriodClosed.run();
                            });
                })
                .setNegativeButton(R.string.cancel, null)
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
        if (g.isClosed()) {
            // Reuses this shared row layout's otherwise-unused subtitle line to flag a closed
            // class group, rather than adding a dedicated view that Discipline/Goal rows would
            // never use.
            holder.subtitle.setText(R.string.class_group_closed_suffix);
            holder.subtitle.setVisibility(View.VISIBLE);
        } else {
            holder.subtitle.setVisibility(View.GONE);
        }
        // Tapping the row (outside the edit/delete buttons) opens the student list for this
        // class group (including a closed one, so its historical roster stays viewable).
        holder.itemView.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString("classGroupId", g.id);
            bundle.putString("groupName", g.name);
            Navigation.findNavController(v).navigate(R.id.action_classGroupList_to_studentList, bundle);
        });
        holder.itemView.setClickable(true);
        // Long-press is "Fechar período e turma" — only offered for an active class group;
        // an already-closed one is corrected/undone instead via PeriodDetail (Reabrir/Trocar destino).
        holder.itemView.setOnLongClickListener(g.isClosed() ? null : v -> {
            showClosePeriodDialog(g);
            return true;
        });

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
        // closedAt matters here too (not just name) — otherwise a group that just got closed by
        // "Fechar período e turma" wouldn't be rebound, and its "(encerrada)" tag wouldn't appear
        // until some unrelated row changed forced a redraw.
        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            ClassGroup oldGroup = oldList.get(oldItemPosition);
            ClassGroup newGroup = newList.get(newItemPosition);
            return Objects.equals(oldGroup.name, newGroup.name) && Objects.equals(oldGroup.closedAt, newGroup.closedAt);
        }
    }
}
