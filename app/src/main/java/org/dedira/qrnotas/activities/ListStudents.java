package org.dedira.qrnotas.activities;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.Database;

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
        this.arrStudentsAdapter = new ArrayAdapter<>(this, R.layout.line, R.id.txtNameList, arrStudents); // Adaptador

        this.lstStudents = this.findViewById(R.id.lstStudents);
        this.lstStudents.setAdapter(this.arrStudentsAdapter);

        this.lstStudents.setOnItemClickListener((parent, view, position, id) -> {
            // Edit student
        });

        this.database.loadAllStudents((success, object) -> {
            ListStudents.this.arrStudents = object;
            arrStudentsAdapter.notifyDataSetChanged();
        });
    }

}