package org.dedira.qrnotas.activities;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.StudentAdapter;

import java.util.ArrayList;

public class ListStudents extends AppCompatActivity {
    ArrayList<Student> arrStudents;
    ArrayAdapter<Student> arrStudentsAdapter;
    ListView lstStudents;
    private Database database;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_students);

        this.database = new Database();

        this.arrStudents = new ArrayList<>();

        this.arrStudentsAdapter = new StudentAdapter(this, arrStudents);

        this.lstStudents = this.findViewById(R.id.lstStudents);
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