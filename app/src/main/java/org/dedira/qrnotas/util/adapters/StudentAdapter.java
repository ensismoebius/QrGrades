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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityOptionsCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.activities.AddOrEditStudent;
import org.dedira.qrnotas.activities.StudentProgress;
import org.dedira.qrnotas.dialogs.QrCodeDialog;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.TextSearch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Feeds {@link org.dedira.qrnotas.activities.StudentList}'s RecyclerView: one row per student
 * with a photo, name, and quick actions (QR code, edit, delete). Also implements the screen's
 * multi-select mode (checkbox rows, used for bulk export) and the live text-search filter.
 */
public class StudentAdapter extends RecyclerView.Adapter<StudentAdapter.StudentViewHolder> {

    /** Notified whenever selection mode toggles or the selected-row count changes, so the hosting screen can update its toolbar. */
    public interface SelectionListener {
        void onSelectionChanged(boolean selectionMode, int count);
    }

    private final Context context;
    // "students" is the currently-displayed (possibly filtered) list; "studentsFull" is the
    // unfiltered source list kept around so filter() can re-derive results without a DB round-trip.
    private final List<Student> students = new ArrayList<>();
    private List<Student> studentsFull = new ArrayList<>();
    private final Set<String> selectedIds = new LinkedHashSet<>();
    private boolean selectionMode = false;
    private SelectionListener selectionListener;

    public StudentAdapter(Context context) {
        this.context = context;
    }

    public void setSelectionListener(SelectionListener listener) {
        this.selectionListener = listener;
    }

    /** Replaces the full source list (e.g. after a fresh load from the database) and re-displays it, diffed against what's currently shown. */
    public void submitFullList(List<Student> newFullList) {
        this.studentsFull = new ArrayList<>(newFullList);
        applyDiff(new ArrayList<>(newFullList));
    }

    /** Filters the full list by name against the given query (accent/case-insensitive via {@link TextSearch}) and displays the result. */
    public void filter(String query) {
        String q = TextSearch.normalize(query == null ? "" : query.trim());
        if (q.isEmpty()) {
            applyDiff(new ArrayList<>(studentsFull));
            return;
        }
        List<Student> filtered = new ArrayList<>();
        for (Student s : studentsFull) {
            if (s.name != null && TextSearch.normalize(s.name).contains(q)) {
                filtered.add(s);
            }
        }
        applyDiff(filtered);
    }

    /** Swaps in a new displayed list using DiffUtil, so the RecyclerView animates only the rows that actually changed. */
    private void applyDiff(List<Student> newList) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new StudentDiffCallback(students, newList));
        students.clear();
        students.addAll(newList);
        result.dispatchUpdatesTo(this);
    }

    public Student getStudentAt(int position) {
        return students.get(position);
    }

    /* --------------------------- Selection mode ----------------------------- */

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public int getSelectionCount() {
        return selectedIds.size();
    }

    public List<String> getSelectedIds() {
        return new ArrayList<>(selectedIds);
    }

    // notifyDataSetChanged() below: every visible row's checkbox/selected state genuinely
    // changes at once, so a full rebind is the correct call here, not a shortcut.
    @SuppressLint("NotifyDataSetChanged")
    public void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        if (!enabled) selectedIds.clear();
        notifyDataSetChanged();
        notifySelectionListener();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void selectAll() {
        selectedIds.clear();
        for (Student s : students) selectedIds.add(s.id);
        notifyDataSetChanged();
        notifySelectionListener();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void clearSelection() {
        selectedIds.clear();
        notifyDataSetChanged();
        notifySelectionListener();
    }

    private void toggleSelection(String studentId, int position) {
        if (selectedIds.contains(studentId)) selectedIds.remove(studentId);
        else selectedIds.add(studentId);
        if (position >= 0) notifyItemChanged(position);
        notifySelectionListener();
    }

    private void notifySelectionListener() {
        if (selectionListener != null) selectionListener.onSelectionChanged(selectionMode, selectedIds.size());
    }

    /* -------------------------------------------------------------------------- */

    /** Confirms and performs deletion of the student at {@code position}, including removing their stored photo. */
    public void requestDelete(int position) {
        if (position < 0 || position >= students.size()) return;
        Student student = students.get(position);

        new AlertDialog.Builder(context)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(context.getString(R.string.confirm_delete_message, student.name))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setOnCancelListener(dialog -> notifyItemChanged(position))
                .setPositiveButton(R.string.delete_confirm_action, (dialog, which) -> {
                    Database db = new Database(context);
                    db.deleteStudent(student.id, (success, object) -> {
                        if (success) {
                            BitmapConverter.deletePhoto(student.photoPath);
                            removeStudent(student);
                            Toast.makeText(context, R.string.student_deleted, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show();
                            notifyItemChanged(position);
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> notifyItemChanged(position))
                .show();
    }

    /** Removes a student from both the displayed and full-source lists after a successful delete. */
    private void removeStudent(Student student) {
        int idx = students.indexOf(student);
        if (idx >= 0) {
            students.remove(idx);
            notifyItemRemoved(idx);
        }
        studentsFull.remove(student);
    }

    @NonNull
    @Override
    public StudentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.line, parent, false);
        return new StudentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull StudentViewHolder holder, int position) {
        Student student = students.get(position);

        holder.txtName.setText(student.name);
        holder.imgPhoto.setImageBitmap(BitmapConverter.loadBitmap(student.photoPath));

        boolean selected = selectedIds.contains(student.id);
        // Clear the listener before setChecked so restoring the checkbox state during rebind
        // doesn't itself fire a spurious selection-toggle callback.
        holder.chkSelect.setOnCheckedChangeListener(null);
        holder.chkSelect.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.chkSelect.setChecked(selected);

        int actionsVisibility = selectionMode ? View.GONE : View.VISIBLE;
        holder.btnQrCode.setVisibility(actionsVisibility);
        holder.btnEditStudent.setVisibility(actionsVisibility);
        holder.btnDeleteStudent.setVisibility(actionsVisibility);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(student.id, holder.getBindingAdapterPosition());
                return;
            }
            Intent intent = new Intent(context, StudentProgress.class);
            intent.putExtra("studentId", student.id);
            context.startActivity(intent);
        });

        holder.itemView.setOnLongClickListener(v -> {
            // Long-press enters selection mode (if not already in it) and selects this row.
            if (!selectionMode) setSelectionMode(true);
            toggleSelection(student.id, holder.getBindingAdapterPosition());
            return true;
        });

        holder.chkSelect.setOnClickListener(v -> toggleSelection(student.id, holder.getBindingAdapterPosition()));

        holder.btnQrCode.setOnClickListener(v -> new QrCodeDialog(context, student).show());

        holder.btnEditStudent.setOnClickListener(v -> {
            Intent intent = new Intent(context, AddOrEditStudent.class);
            intent.putExtra("selectedStudentId", student.id);

            if (context instanceof Activity) {
                // Shared-element transition: the tapped row's photo appears to morph into the
                // edit screen's photo, using the "studentPhoto" transition name set on both views.
                Activity activity = (Activity) context;
                ActivityOptionsCompat options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity, holder.imgPhoto, "studentPhoto");
                activity.startActivity(intent, options.toBundle());
            } else {
                context.startActivity(intent);
            }
        });

        holder.btnDeleteStudent.setOnClickListener(v -> requestDelete(holder.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return students.size();
    }

    static class StudentViewHolder extends RecyclerView.ViewHolder {
        final TextView txtName;
        final ImageView imgPhoto;
        final ImageButton btnQrCode;
        final ImageButton btnEditStudent;
        final ImageButton btnDeleteStudent;
        final MaterialCheckBox chkSelect;

        StudentViewHolder(@NonNull View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtNameList);
            imgPhoto = itemView.findViewById(R.id.imgPhotoList);
            btnQrCode = itemView.findViewById(R.id.btnQrCode);
            btnEditStudent = itemView.findViewById(R.id.btnEditStudent);
            btnDeleteStudent = itemView.findViewById(R.id.btnDeleteStudent);
            chkSelect = itemView.findViewById(R.id.chkSelect);
        }
    }

    /** Tells DiffUtil which students are "the same" (by id) vs. merely equal in content (name/photo), so it can animate updates vs. inserts/removals correctly. */
    private static class StudentDiffCallback extends DiffUtil.Callback {
        private final List<Student> oldList;
        private final List<Student> newList;

        StudentDiffCallback(List<Student> oldList, List<Student> newList) {
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
            Student a = oldList.get(oldItemPosition);
            Student b = newList.get(newItemPosition);
            return Objects.equals(a.name, b.name)
                    && Objects.equals(a.photoPath, b.photoPath);
        }
    }
}
