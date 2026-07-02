package org.dedira.qrnotas.util;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Student;

import java.util.ArrayList;
import java.util.List;

/** Simple searchable student list for {@link org.dedira.qrnotas.dialogs.StudentPickerDialog} — tap a row to pick it. */
public class StudentPickerAdapter extends RecyclerView.Adapter<StudentPickerAdapter.ViewHolder> {

    public interface OnStudentPickedListener {
        void onStudentPicked(Student student);
    }

    private final List<Student> students = new ArrayList<>();
    private List<Student> studentsFull = new ArrayList<>();
    private final OnStudentPickedListener listener;

    public StudentPickerAdapter(OnStudentPickedListener listener) {
        this.listener = listener;
    }

    public void submitFullList(List<Student> newFullList) {
        this.studentsFull = new ArrayList<>(newFullList);
        this.students.clear();
        this.students.addAll(newFullList);
        notifyDataSetChanged();
    }

    public void filter(String query) {
        String q = TextSearch.normalize(query == null ? "" : query.trim());
        students.clear();
        if (q.isEmpty()) {
            students.addAll(studentsFull);
        } else {
            for (Student s : studentsFull) {
                if (s.name != null && TextSearch.normalize(s.name).contains(q)) students.add(s);
            }
        }
        notifyDataSetChanged();
    }

    public int getStudentCount() {
        return students.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_student_picker, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Student student = students.get(position);
        holder.txtName.setText(student.name);
        holder.imgPhoto.setImageBitmap(BitmapConverter.loadBitmap(student.photoPath));
        holder.itemView.setOnClickListener(v -> listener.onStudentPicked(student));
    }

    @Override
    public int getItemCount() {
        return students.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtName;
        final ImageView imgPhoto;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtNamePicker);
            imgPhoto = itemView.findViewById(R.id.imgPhotoPicker);
        }
    }
}
