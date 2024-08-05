package org.dedira.qrnotas.activities;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.budiyev.android.codescanner.CodeScanner;
import com.budiyev.android.codescanner.CodeScannerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.dialog_loading;
import org.dedira.qrnotas.model.Database;
import org.dedira.qrnotas.model.entities.Evaluation;
import org.dedira.qrnotas.model.entities.Grade;
import org.dedira.qrnotas.model.entities.Student;
import org.dedira.qrnotas.util.BitmapConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class activity_main extends AppCompatActivity {
    private dialog_loading dialogLoading;
    private CodeScanner mCodeScanner;
    private TextView txtName;
    private Spinner spnGrade;
    private Spinner spnEvaluation;
    private ImageView imgPhoto;
    private Student student;
    private Integer extraPoints = 1;
    private boolean isExpanded = false;
    private Database database;
    private MediaPlayer mediaPlayer;

    private ArrayList<String> grades = new ArrayList<>();
    private ArrayList<String> evaluations = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initializeMediaPlayer();
        initializeUIComponents();
        initializeDatabase();

        setupSpinners();
        setupButtons();
        setupCodeScanner();
    }

    private void loadEvaluations() {
        database.loadAllEvaluations((success, evaluations) -> {
            for (Evaluation e : evaluations) {
                this.evaluations.add(e.name);
            }

            ArrayAdapter<String> adapterEvaluations = new ArrayAdapter<>(this, R.layout.spinner_item, this.evaluations);
            adapterEvaluations.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spnEvaluation.setAdapter(adapterEvaluations);
        });
    }

    private void initializeMediaPlayer() {
        mediaPlayer = MediaPlayer.create(this, R.raw.qr_scanned);
    }

    private void initializeUIComponents() {
        txtName = findViewById(R.id.txtName);
        imgPhoto = findViewById(R.id.imgPhoto);
        spnGrade = findViewById(R.id.spnGrade);
        spnEvaluation = findViewById(R.id.spnEvaluation);
        dialogLoading = new dialog_loading(this);
    }

    private void initializeDatabase() {
        database = new Database();
    }

    private void setupSpinners() {

        loadGrades();
        loadEvaluations();

        spnGrade.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedItem = parent.getItemAtPosition(position).toString();
                Toast.makeText(activity_main.this, "Selected: " + selectedItem, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void loadGrades() {
        for (Grade item : Grade.values()) {
            this.grades.add(item.name());
        }

        ArrayAdapter<String> adapterGrades = new ArrayAdapter<>(this, R.layout.spinner_item, this.grades);
        adapterGrades.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spnGrade.setAdapter(adapterGrades);
    }

    private void setupButtons() {
        MaterialButton btnAddStudent = findViewById(R.id.btnAddStudent);
        MaterialButton btnListStudent = findViewById(R.id.btnListStudents);
        Button btnSave = findViewById(R.id.btnSave);
        FloatingActionButton btnOptions = findViewById(R.id.btnOptions);

        btnAddStudent.setOnClickListener(v -> navigateToActivity(activity_add_or_edit_student.class, btnAddStudent, btnListStudent));
        btnListStudent.setOnClickListener(v -> navigateToActivity(activity_list_students.class, btnAddStudent, btnListStudent));

        btnSave.setOnClickListener(v -> saveStudent());

        btnOptions.setOnClickListener(v -> toggleOptionsVisibility(btnAddStudent, btnListStudent));
    }

    private void navigateToActivity(Class<?> targetActivity, MaterialButton btnAddStudent, MaterialButton btnListStudent) {
        Intent intent = new Intent(activity_main.this, targetActivity);
        startActivity(intent);
        btnAddStudent.setVisibility(View.GONE);
        btnListStudent.setVisibility(View.GONE);
        isExpanded = false;
    }

    private void saveStudent() {
        dialogLoading.show();

        Map<String, Object> selection = new HashMap<>();
        student.grades += extraPoints;
        selection.put("grades", student.grades);

        database.updateStudentFields(student.id, selection, (success, object) -> {
            if (success) mediaPlayer.start();
            dialogLoading.dismiss();
        });
    }

    private void toggleOptionsVisibility(MaterialButton btnAddStudent, MaterialButton btnListStudent) {
        if (isExpanded) {
            btnAddStudent.setVisibility(View.GONE);
            btnListStudent.setVisibility(View.GONE);
        } else {
            btnAddStudent.setVisibility(View.VISIBLE);
            btnListStudent.setVisibility(View.VISIBLE);
        }
        isExpanded = !isExpanded;
    }

    private void setupCodeScanner() {
        CodeScannerView scannerView = findViewById(R.id.scnView);
        scannerView.setOnClickListener(view -> mCodeScanner.startPreview());

        mCodeScanner = new CodeScanner(this, scannerView);
        mCodeScanner.setDecodeCallback(result -> runOnUiThread(() -> handleCodeScan(result.getText())));
    }

    private void handleCodeScan(String scannedText) {
        mediaPlayer.start();

        dialogLoading.show();
        dialogLoading.setCancelable(false);
        dialogLoading.setCanceledOnTouchOutside(false);

        database.loadStudent(scannedText, (success, object) -> {
            if (!success) {
                Toast.makeText(this, "Student non existent!", Toast.LENGTH_SHORT).show();
            } else {
                student = object;
                txtName.setText(object.name);
                imgPhoto.setImageBitmap(BitmapConverter.stringToBitmap(object.photo));
            }
            dialogLoading.dismiss();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCodeScanner.startPreview();
        initializeMediaPlayer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mCodeScanner.stopPreview();
        mCodeScanner.releaseResources();
    }

    @Override
    protected void onStop() {
        super.onStop();
        releaseMediaPlayer();
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
        }
    }
}
