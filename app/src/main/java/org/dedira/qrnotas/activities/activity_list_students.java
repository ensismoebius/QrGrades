package org.dedira.qrnotas.activities;

import android.annotation.SuppressLint;
import android.graphics.Insets;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.entities.Student;
import org.dedira.qrnotas.model.Database;
import org.dedira.qrnotas.util.StudentAdapter;

import java.util.ArrayList;

public class activity_list_students extends AppCompatActivity {
    ArrayList<Student> arrStudents;
    StudentAdapter arrStudentsAdapter;
    RecyclerView lstStudents;
    Database database;

    @SuppressLint("NotifyDataSetChanged")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_list_student);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).toPlatformInsets();
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        this.database = new Database();
        this.arrStudents = new ArrayList<>();
        this.arrStudentsAdapter = new StudentAdapter(this, arrStudents);

        this.lstStudents = this.findViewById(R.id.lstStudents);
        this.lstStudents.setLayoutManager(new StaggeredGridLayoutManager(3,StaggeredGridLayoutManager.VERTICAL));
        this.lstStudents.setAdapter(this.arrStudentsAdapter);

        this.database.loadAllStudents((success, students) -> {
            if (success) {
                arrStudents.clear();
                arrStudents.addAll(students);
                arrStudentsAdapter.notifyDataSetChanged();
            } else {
                Toast.makeText(this, "Failed to load students data.", Toast.LENGTH_SHORT).show();
            }
        });
    }
}