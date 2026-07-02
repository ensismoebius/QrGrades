package org.dedira.qrnotas.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.budiyev.android.codescanner.CodeScanner;
import com.budiyev.android.codescanner.CodeScannerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.LoadingDialog;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.ActivityTransitions;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.Database;

import java.util.HashMap;
import java.util.Map;

public class Main extends AppCompatActivity {
    private LoadingDialog loadingDialog;
    private CodeScanner mCodeScanner;
    private TextView txtName;
    private TextView txtPoints;
    private ImageView imgPhoto;
    private View contentGroup;
    private View txtScanHint;
    private View cameraPermissionOverlay;
    private MaterialButton btnSave;
    private Student student;
    private Integer extraPoints = 1;
    private boolean isExpanded = false;
    private boolean scannerStarted = false;
    private Database database;
    private MediaPlayer mediaPlayer;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.forward(this);
        setContentView(R.layout.activity_main);

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
        this.contentGroup = this.findViewById(R.id.contentGroup);
        this.txtScanHint = this.findViewById(R.id.txtScanHint);
        this.cameraPermissionOverlay = this.findViewById(R.id.cameraPermissionOverlay);
        this.loadingDialog = new LoadingDialog(this);

        this.cameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) showScannerGranted();
            else cameraPermissionOverlay.setVisibility(View.VISIBLE);
        });

        this.findViewById(R.id.btnGrantCameraPermission).setOnClickListener(v ->
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA));

        /****************************/
        /******* Database ***********/
        /****************************/
        this.database = new Database(this);

        /******************************************************/
        /**************** Buttons and actions *****************/
        /******************************************************/
        FloatingActionButton btnPlus = this.findViewById(R.id.btnPlus);
        btnPlus.setOnClickListener(this::onClick);

        FloatingActionButton btnLess = this.findViewById(R.id.btnLess);
        btnLess.setOnClickListener(v -> {
            if (this.extraPoints < 2) return;
            this.extraPoints--;
            this.txtPoints.setText(this.extraPoints.toString());
            bounce(this.txtPoints);
        });

        ExtendedFloatingActionButton btnAddStudent = this.findViewById(R.id.btnAddStudent);
        ExtendedFloatingActionButton btnListStudent = this.findViewById(R.id.btnListStudents);
        ExtendedFloatingActionButton btnDisciplines = this.findViewById(R.id.btnDisciplines);

        btnAddStudent.setOnClickListener(v -> {
            Intent intent = new Intent(Main.this, AddOrEditStudent.class);
            startActivity(intent);
        });

        btnListStudent.setOnClickListener(v -> {
            Intent intent = new Intent(Main.this, ListStudents.class);
            startActivity(intent);
        });

        btnDisciplines.setOnClickListener(v -> {
            Intent intent = new Intent(Main.this, DisciplineList.class);
            startActivity(intent);
        });

        this.btnSave = this.findViewById(R.id.btnSave);
        this.btnSave.setOnClickListener(v -> onSaveClick());

        FloatingActionButton btnOptions = this.findViewById(R.id.btnOptions);
        btnOptions.setOnClickListener(v -> {
            boolean expanding = !isExpanded;
            toggleOptionsMenu(expanding, btnOptions, btnAddStudent, btnListStudent, btnDisciplines);
            isExpanded = expanding;
        });

        /******************************************************/
        /****************** Code scanner events ***************/
        /******************************************************/
        CodeScannerView scannerView = findViewById(R.id.scnView);
        scannerView.setOnClickListener(view -> requestCameraAndStartScanning());
        this.mCodeScanner = new CodeScanner(this, scannerView);
        this.mCodeScanner.setDecodeCallback(result -> runOnUiThread(() -> {

            mediaPlayer.start();

            this.loadingDialog.show();
            this.loadingDialog.setCancelable(false);
            this.loadingDialog.setCanceledOnTouchOutside(false);
            this.database.loadStudent(result.getText(), (success, object) -> {

                if (!success) {
                    Toast.makeText(this, R.string.student_not_found, Toast.LENGTH_SHORT).show();
                } else {
                    this.student = object;
                    this.txtName.setText(object.name);
                    this.imgPhoto.setImageBitmap(BitmapConverter.loadBitmap(object.photoPath));
                    revealStudentContent();
                }
                this.loadingDialog.dismiss();
            });
        }));
    }

    private void requestCameraAndStartScanning() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScannerGranted();
        } else {
            cameraPermissionOverlay.setVisibility(View.VISIBLE);
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void showScannerGranted() {
        cameraPermissionOverlay.setVisibility(View.GONE);
        mCodeScanner.startPreview();
        scannerStarted = true;
    }

    private void toggleOptionsMenu(boolean expanding, FloatingActionButton btnOptions,
                                    ExtendedFloatingActionButton btnAddStudent, ExtendedFloatingActionButton btnListStudent,
                                    ExtendedFloatingActionButton btnDisciplines) {
        btnOptions.animate().rotation(expanding ? 135f : 0f).setDuration(200).start();

        if (expanding) {
            animateFabIn(btnDisciplines, 80);
            animateFabIn(btnAddStudent, 40);
            animateFabIn(btnListStudent, 0);
        } else {
            animateFabOut(btnDisciplines, 0);
            animateFabOut(btnAddStudent, 40);
            animateFabOut(btnListStudent, 80);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void animateFabIn(View fab, long startDelay) {
        fab.setVisibility(View.VISIBLE);
        fab.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelay)
                .setDuration(200)
                .start();
    }

    private void animateFabOut(View fab, long startDelay) {
        fab.animate()
                .alpha(0f)
                .translationY(dp(24))
                .setStartDelay(startDelay)
                .setDuration(150)
                .withEndAction(() -> fab.setVisibility(View.INVISIBLE))
                .start();
    }

    private void revealStudentContent() {
        if (contentGroup.getVisibility() == View.VISIBLE) return;

        txtScanHint.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            txtScanHint.setVisibility(View.GONE);

            contentGroup.setTranslationY(dp(16));
            contentGroup.setVisibility(View.VISIBLE);
            contentGroup.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .start();
        }).start();
    }

    private void bounce(View view) {
        view.animate().cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.animate()
                .scaleX(1.3f)
                .scaleY(1.3f)
                .setDuration(90)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                .start();
    }

    private void onSaveClick() {
        if (this.student == null) {
            Toast.makeText(this, R.string.scan_first, Toast.LENGTH_SHORT).show();
            return;
        }

        this.loadingDialog.show();

        final String studentId = this.student.id;
        final int previousGrades = this.student.grades;
        final int newGrades = previousGrades + this.extraPoints;
        final int addedPoints = this.extraPoints;

        Map<String, Object> selection = new HashMap<>();
        selection.put("grades", newGrades);

        this.database.updateStudentFields(studentId, selection, (success, updatedStudent) -> {
            this.loadingDialog.dismiss();

            if (!success) {
                Toast.makeText(this, R.string.points_not_saved, Toast.LENGTH_SHORT).show();
                return;
            }

            this.student = updatedStudent;
            this.mediaPlayer.start();

            Snackbar.make(this.btnSave, getString(R.string.points_added, addedPoints), Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo, v -> undoPoints(studentId, previousGrades))
                    .show();
        });
    }

    private void undoPoints(String studentId, int previousGrades) {
        Map<String, Object> revert = new HashMap<>();
        revert.put("grades", previousGrades);

        this.database.updateStudentFields(studentId, revert, (success, updatedStudent) -> {
            if (!success) {
                Toast.makeText(this, R.string.undo_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            if (this.student != null && this.student.id.equals(studentId)) {
                this.student = updatedStudent;
            }
            Toast.makeText(this, R.string.undo_done, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestCameraAndStartScanning();
        mediaPlayer = MediaPlayer.create(this, R.raw.qr_scanned);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scannerStarted) {
            mCodeScanner.stopPreview();
            mCodeScanner.releaseResources();
            scannerStarted = false;
        }
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

    private void onClick(View v) {
        if (this.extraPoints > 4) return;
        this.extraPoints++;
        this.txtPoints.setText(this.extraPoints.toString());
        bounce(this.txtPoints);
    }
}
