package org.dedira.qrnotas.activities;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
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
import org.dedira.qrnotas.dialogs.LoadingDialog;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.Database;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class Main extends AppCompatActivity {
    private LoadingDialog loadingDialog;
    private CodeScanner mCodeScanner;
    private TextView txtName;
    private TextView txtPoints;
    private ImageView imgPhoto;
    private Student student;
    private Integer extraPoints = 1;
    private boolean isExpanded = false;
    private Database database;
    private MediaPlayer mediaPlayer;

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

        /********************************************/
        /********** Create media player *************/
        /********************************************/
        mediaPlayer = MediaPlayer.create(this, R.raw.qr_scanned);

        /********************************************/
        /************** Text objects ****************/
        /********************************************/
        this.txtName = this.findViewById(R.id.txtName);
        this.imgPhoto = this.findViewById(R.id.imgPhoto);
        this.txtPoints = this.findViewById(R.id.txtPoints);
        this.loadingDialog = new LoadingDialog(this);

        /****************************/
        /******* Database ***********/
        /****************************/
        this.database = new Database();

        /******************************************************/
        /**************** Buttons and actions *****************/
        /******************************************************/
        Button btnPlus = this.findViewById(R.id.btnPlus);
        btnPlus.setOnClickListener(v -> {
            if (this.extraPoints > 4) return;
            this.extraPoints++;
            this.txtPoints.setText(
                    String.format(
                            Locale.getDefault(),
                            "%s",
                            this.extraPoints.toString()
                    ));
        });

        Button btnLess = this.findViewById(R.id.btnLess);
        btnLess.setOnClickListener(v -> {
            if (this.extraPoints < 2) return;
            this.extraPoints--;
            this.txtPoints.setText(
                    String.format(
                            Locale.getDefault(),
                            "%s",
                            this.extraPoints.toString()
                    ));
        });

        MaterialButton btnAddStudent = this.findViewById(R.id.btnAddStudent);
        MaterialButton btnListStudent = this.findViewById(R.id.btnListStudents);

        btnAddStudent.setOnClickListener(v -> {
            Intent intent = new Intent(Main.this, AddOrEditStudent.class);
            startActivity(intent);
            btnAddStudent.setVisibility(View.GONE);
            btnListStudent.setVisibility(View.GONE);
            isExpanded = false;
        });

        btnListStudent.setOnClickListener(v -> {
            Intent intent = new Intent(Main.this, ListStudents.class);
            startActivity(intent);
            btnAddStudent.setVisibility(View.GONE);
            btnListStudent.setVisibility(View.GONE);
            isExpanded = false;
        });

        Button btnSave = this.findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {

            this.loadingDialog.show();

            Map<String, Object> selection = new HashMap<>();
            this.student.grades += this.extraPoints;
            selection.put("grades", this.student.grades);

            this.database.updateStudentFields(this.student.id, selection, (success, object) -> {
                if (success) Main.this.mediaPlayer.start();
                this.loadingDialog.dismiss();
            });
        });

        FloatingActionButton btnOptions = this.findViewById(R.id.btnOptions);
        btnOptions.setOnClickListener(v -> {
            if (isExpanded) {
                // Collapse
                btnAddStudent.setVisibility(View.GONE);
                btnListStudent.setVisibility(View.GONE);
            } else {
                // Expand
                btnAddStudent.setVisibility(View.VISIBLE);
                btnListStudent.setVisibility(View.VISIBLE);
            }
            isExpanded = !isExpanded;
        });

        /******************************************************/
        /****************** Code scanner events ***************/
        /******************************************************/
        CodeScannerView scannerView = findViewById(R.id.scnView);
        scannerView.setOnClickListener(view -> mCodeScanner.startPreview());
        this.mCodeScanner = new CodeScanner(this, scannerView);
        this.mCodeScanner.setDecodeCallback(result -> runOnUiThread(() -> {

            mediaPlayer.start();

            this.loadingDialog.show();
            this.loadingDialog.setCancelable(false);
            this.loadingDialog.setCanceledOnTouchOutside(false);
            this.database.loadStudent(result.getText(), (success, object) -> {

                if (!success) {
                    Toast.makeText(this, "Student non existent!", Toast.LENGTH_SHORT).show();
                } else {
                    this.student = object;
                    this.txtName.setText(object.name);
                    this.imgPhoto.setImageBitmap(BitmapConverter.stringToBitmap(object.photo));
                }
                this.loadingDialog.dismiss();
            });
        }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCodeScanner.startPreview();
        mediaPlayer = MediaPlayer.create(this, R.raw.qr_scanned);
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

        try {
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.release();
            }
        } catch (Exception ignored) {

        }

    }
}