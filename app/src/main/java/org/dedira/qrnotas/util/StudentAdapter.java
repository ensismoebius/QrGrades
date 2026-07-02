package org.dedira.qrnotas.util;

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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class StudentAdapter extends RecyclerView.Adapter<StudentAdapter.StudentViewHolder> {

    public interface SelectionListener {
        void onSelectionChanged(boolean selectionMode, int count);
    }

    private final Context context;
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

    public void submitFullList(List<Student> newFullList) {
        this.studentsFull = new ArrayList<>(newFullList);
        applyDiff(new ArrayList<>(newFullList));
    }

    public void filter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty()) {
            applyDiff(new ArrayList<>(studentsFull));
            return;
        }
        List<Student> filtered = new ArrayList<>();
        for (Student s : studentsFull) {
            if (s.name != null && s.name.toLowerCase(Locale.getDefault()).contains(q)) {
                filtered.add(s);
            }
        }
        applyDiff(filtered);
    }

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

    public void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        if (!enabled) selectedIds.clear();
        notifyDataSetChanged();
        notifySelectionListener();
    }

    public void selectAll() {
        selectedIds.clear();
        for (Student s : students) selectedIds.add(s.id);
        notifyDataSetChanged();
        notifySelectionListener();
    }

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

    public void requestDelete(int position) {
        if (position < 0 || position >= students.size()) return;
        Student student = students.get(position);

        new AlertDialog.Builder(context)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(context.getString(R.string.confirm_delete_message, student.name))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setOnCancelListener(dialog -> notifyItemChanged(position))
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
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
                .setNegativeButton(android.R.string.no, (dialog, which) -> notifyItemChanged(position))
                .show();
    }

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
        holder.chkSelect.setOnCheckedChangeListener(null);
        holder.chkSelect.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.chkSelect.setChecked(selected);

        int actionsVisibility = selectionMode ? View.GONE : View.VISIBLE;
        holder.btnProgress.setVisibility(actionsVisibility);
        holder.btnEditStudent.setVisibility(actionsVisibility);
        holder.btnDeleteStudent.setVisibility(actionsVisibility);

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) toggleSelection(student.id, holder.getBindingAdapterPosition());
            else new QrCodeDialog(context, student).show();
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!selectionMode) setSelectionMode(true);
            toggleSelection(student.id, holder.getBindingAdapterPosition());
            return true;
        });

        holder.chkSelect.setOnClickListener(v -> toggleSelection(student.id, holder.getBindingAdapterPosition()));

        holder.btnProgress.setOnClickListener(v -> {
            Intent intent = new Intent(context, StudentProgress.class);
            intent.putExtra("studentId", student.id);
            context.startActivity(intent);
        });

        holder.btnEditStudent.setOnClickListener(v -> {
            Intent intent = new Intent(context, AddOrEditStudent.class);
            intent.putExtra("selectedStudentId", student.id);

            if (context instanceof Activity) {
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
        final ImageButton btnProgress;
        final ImageButton btnEditStudent;
        final ImageButton btnDeleteStudent;
        final MaterialCheckBox chkSelect;

        StudentViewHolder(@NonNull View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtNameList);
            imgPhoto = itemView.findViewById(R.id.imgPhotoList);
            btnProgress = itemView.findViewById(R.id.btnProgress);
            btnEditStudent = itemView.findViewById(R.id.btnEditStudent);
            btnDeleteStudent = itemView.findViewById(R.id.btnDeleteStudent);
            chkSelect = itemView.findViewById(R.id.chkSelect);
        }
    }

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
