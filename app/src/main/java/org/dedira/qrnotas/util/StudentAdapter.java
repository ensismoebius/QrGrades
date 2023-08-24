package org.dedira.qrnotas.util;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.activities.AddOrEditStudent;
import org.dedira.qrnotas.model.Student;

import java.util.ArrayList;

public class StudentAdapter extends ArrayAdapter<Student> {

    public StudentAdapter(Context context, ArrayList<Student> students) {
        super(context, 0, students);
    }

    @NonNull
    @Override
    public View getView(int position, View preloadedView, ViewGroup parent) {
        // Get the student at the current position
        Student student = getItem(position);

        if (preloadedView == null) {
            preloadedView = LayoutInflater.from(getContext()).inflate(R.layout.line, parent, false);
        }

        // Find the views in the custom layout
        TextView txtName = preloadedView.findViewById(R.id.txtNameList);
        ImageView imgPhoto = preloadedView.findViewById(R.id.imgPhotoList);
        ImageButton btnEditStudent = preloadedView.findViewById(R.id.btnEditStudent);
        ImageButton btnDelStudent = preloadedView.findViewById(R.id.btnDeleteStudent);

        // Populate the views with student data
        if (student != null) {
            txtName.setText(student.name);

            // Load the student photo into the ImageView using your BitmapConverter logic
            Bitmap studentPhotoBitmap = BitmapConverter.stringToBitmap(student.photo);
            imgPhoto.setImageBitmap(studentPhotoBitmap);

            // Set an onClickListener for the edit button
            btnEditStudent.setOnClickListener(v -> {
                Intent intent = new Intent(parent.getContext(), AddOrEditStudent.class);
                intent.putExtra("selectedStudentId", student.id); // Pass the selected student as an extra
                parent.getContext().startActivity(intent);
            });

            btnDelStudent.setOnClickListener(v -> {
                new AlertDialog.Builder(v.getContext())
                        .setTitle("Title")
                        .setMessage("Do you really want to whatever?")
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton(android.R.string.yes, (dialog, whichButton) -> {
                                    Database db = new Database();
                                    db.deleteStudent(student.id, (success, object) ->
                                            Toast.makeText(v.getContext(), "Deleted!!!", Toast.LENGTH_SHORT).show()
                                    );

                                }
                        )
                        .setNegativeButton(android.R.string.no, null).show();
            });
        }

        return preloadedView;
    }
}

