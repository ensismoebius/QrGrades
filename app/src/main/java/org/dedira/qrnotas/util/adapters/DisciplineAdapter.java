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
import org.dedira.qrnotas.activities.DisciplineDetail;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.util.Database;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Feeds the top-level "disciplines" list screen — each row is one discipline (subject),
 * tappable to open its detail screen, with Edit/Delete actions.
 * <p>
 * This is a {@link RecyclerView.Adapter}, Android's standard way to show a scrolling list
 * efficiently without building a new row view for every item:
 * <ul>
 *     <li>{@link #onCreateViewHolder} inflates (builds from XML) one empty row layout and
 *     wraps its views in a {@link ViewHolder}. Called only enough times to fill the screen.</li>
 *     <li>{@link #onBindViewHolder} fills a reused row with data for a specific list position
 *     every time that row scrolls into view.</li>
 *     <li>{@link ViewHolder} caches the row's child views so {@code findViewById} runs once
 *     per row instead of on every scroll.</li>
 * </ul>
 */
public class DisciplineAdapter extends RecyclerView.Adapter<DisciplineAdapter.ViewHolder> {

    private final Context context;
    private final Database database;
    private final List<Discipline> disciplines = new ArrayList<>();

    public DisciplineAdapter(Context context, Database database) {
        this.context = context;
        this.database = database;
    }

    /**
     * Replaces the visible list of disciplines with a new one, using {@link DiffUtil} to
     * compute the minimal set of row changes rather than redrawing every row.
     */
    public void submitList(List<Discipline> newList) {
        // DiffUtil compares old vs new lists and produces a precise diff (added/removed/moved
        // rows), which is cheaper and animates better than a full notifyDataSetChanged().
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DisciplineDiffCallback(disciplines, newList));
        disciplines.clear();
        disciplines.addAll(newList);
        // Fires only the specific insert/remove/change notifications needed for this diff.
        result.dispatchUpdatesTo(this);
    }

    /** Opens the "add new discipline" dialog (an edit dialog with no pre-filled discipline). */
    public void showAddDialog() {
        showEditDialog(null);
    }

    /**
     * Shows a text-input dialog to create a new discipline, or rename an existing one if
     * {@code existing} is non-null. Saving persists to the database, then either inserts the
     * new row or refreshes the edited row in place.
     */
    private void showEditDialog(Discipline existing) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        if (existing != null) input.setText(existing.name);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(existing == null ? R.string.add_discipline : R.string.edit_discipline)
                .setView(wrapInputPadding(input))
                .setPositiveButton(R.string.save, (d, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;

                    Discipline saved = existing != null ? existing : new Discipline();
                    saved.name = name;
                    database.saveDiscipline(saved, (success, savedResult) -> {
                        if (!success) {
                            Toast.makeText(context, R.string.save_failed, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (existing == null) {
                            List<Discipline> updated = new ArrayList<>(disciplines);
                            updated.add(savedResult);
                            submitList(updated);
                        } else {
                            notifyItemChanged(disciplines.indexOf(existing));
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

    /** Wraps the dialog's text input in some padding so it isn't flush against the dialog edges. */
    private View wrapInputPadding(EditText input) {
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);
        return input;
    }

    /**
     * Asks the user to confirm deleting a discipline, then deletes it from the database and
     * removes its row. If the delete fails (e.g. the discipline still has class groups), the
     * row is redrawn instead of removed so nothing disappears incorrectly.
     */
    private void requestDelete(int position) {
        Discipline d = disciplines.get(position);
        new AlertDialog.Builder(context)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(context.getString(R.string.confirm_delete_message, d.name))
                .setIcon(android.R.drawable.ic_dialog_alert)
                // If the user backs out of the dialog (taps outside it), redraw the row in
                // case it was left in a stale visual state.
                .setOnCancelListener(dialog -> notifyItemChanged(position))
                .setPositiveButton(R.string.delete_confirm_action, (dialog, which) -> database.deleteDiscipline(d.id, (success, reason) -> {
                    if (success) {
                        List<Discipline> updated = new ArrayList<>(disciplines);
                        updated.remove(d);
                        submitList(updated);
                        Toast.makeText(context, R.string.entity_deleted, Toast.LENGTH_SHORT).show();
                    } else {
                        int messageRes = "HAS_GROUPS".equals(reason) ? R.string.discipline_has_groups : R.string.delete_failed;
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

    /** Fills a recycled row with the discipline's data and wires up its click actions. */
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Discipline d = disciplines.get(position);
        holder.txtName.setText(d.name);
        // This shared row layout has an optional subtitle line; disciplines don't use it.
        holder.subtitle.setVisibility(View.GONE);

        // Tapping the row (outside the edit/delete buttons) opens the discipline's detail
        // screen, passing its id/name via Intent extras.
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, DisciplineDetail.class);
            intent.putExtra("disciplineId", d.id);
            intent.putExtra("disciplineName", d.name);
            context.startActivity(intent);
        });

        holder.btnEdit.setOnClickListener(v -> showEditDialog(d));
        // getBindingAdapterPosition() reflects the row's current position at click time,
        // which may differ from the "position" parameter if the list changed since binding.
        holder.btnDelete.setOnClickListener(v -> requestDelete(holder.getBindingAdapterPosition()));
    }

    /** Tells the RecyclerView how many rows to show. */
    @Override
    public int getItemCount() {
        return disciplines.size();
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

    // Tells DiffUtil how to compare the old and new discipline lists so it can compute the
    // minimal set of row changes instead of the adapter redrawing every row on every update.
    private static class DisciplineDiffCallback extends DiffUtil.Callback {
        private final List<Discipline> oldList;
        private final List<Discipline> newList;

        DisciplineDiffCallback(List<Discipline> oldList, List<Discipline> newList) {
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
        // renamed discipline is still recognized as the same row (not removed+added).
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
