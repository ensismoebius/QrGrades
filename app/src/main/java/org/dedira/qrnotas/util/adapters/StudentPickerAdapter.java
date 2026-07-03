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
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.TextSearch;

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

    // notifyDataSetChanged() below: this adapter is deliberately simpler than StudentAdapter
    // (no DiffUtil) since the whole visible set legitimately changes on every load/search
    // keystroke — a full rebind is the correct call, not a shortcut.
    @SuppressLint("NotifyDataSetChanged")
    public void submitFullList(List<Student> newFullList) {
        this.studentsFull = new ArrayList<>(newFullList);
        this.students.clear();
        this.students.addAll(newFullList);
        notifyDataSetChanged();
    }

    @SuppressLint("NotifyDataSetChanged")
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
