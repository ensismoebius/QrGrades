package org.dedira.qrnotas.model;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.activities.AddOrEditStudent;
import org.dedira.qrnotas.util.Database;

public class Item extends RecyclerView.ViewHolder {

    public Student student;
    public TextView txtNameList;
    public ImageView imgPhotoList;
    public ImageButton btnEditStudent;
    public ImageButton btnDeleteStudent;

    public Item(View item) {
        super(item);
        txtNameList = item.findViewById(R.id.txtNameList);
        imgPhotoList = item.findViewById(R.id.imgPhotoList);
        btnEditStudent = item.findViewById(R.id.btnEditStudent);
        btnDeleteStudent = item.findViewById(R.id.btnDeleteStudent);

        // Set an onClickListener for the edit button
        btnEditStudent.setOnClickListener(v -> {
            Intent intent = new Intent(item.getContext(), AddOrEditStudent.class);
            intent.putExtra("selectedStudentId", student.id); // Pass the selected student as an extra
            item.getContext().startActivity(intent);
        });

        btnDeleteStudent.setOnClickListener(v -> {
            new AlertDialog.Builder(v.getContext())
                    .setTitle("Student deletion")
                    .setMessage("Do you really want to delete this student?")
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                        Database db = new Database();
                        db.deleteStudent(student.id, (success, object) ->
                                Toast.makeText(v.getContext(), student.name + " deleted.", Toast.LENGTH_SHORT).show()
                        );

                    })
                    .setNegativeButton(android.R.string.cancel, null).show();
        });
    }
}
