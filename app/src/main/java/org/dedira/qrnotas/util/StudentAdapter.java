package org.dedira.qrnotas.util;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.activities.list_item_student;
import org.dedira.qrnotas.model.entities.Student;

import java.util.List;

public class StudentAdapter extends RecyclerView.Adapter<list_item_student> {
    private final Context context;
    private final List<Student> studentList;

    public StudentAdapter(Context context, List<Student> studentList) {
        this.context = context;
        this.studentList = studentList;
    }

    @NonNull
    @Override
    public list_item_student onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new list_item_student(LayoutInflater.from(context).inflate(R.layout.list_item_student, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull list_item_student holder, int position) {
        Student item = studentList.get(position);
        holder.student = item;
        holder.txtNameList.setText(item.name);
        holder.imgPhotoList.setImageBitmap(BitmapConverter.stringToBitmap(item.photo));
    }

    @Override
    public int getItemCount() {
        return studentList.size();
    }
}
