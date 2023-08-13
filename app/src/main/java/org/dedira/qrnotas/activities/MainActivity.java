package org.dedira.qrnotas.activities;

import android.app.Activity;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.budiyev.android.codescanner.CodeScanner;
import com.budiyev.android.codescanner.CodeScannerView;
import com.budiyev.android.codescanner.DecodeCallback;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.zxing.Result;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.LoadingDialog;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.BitmapCoverter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.IDatabaseOnLoad;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private LoadingDialog loadingDialog;
    private Ringtone notificationSound;
    private CodeScanner mCodeScanner;
    private TextView txtName;
    private TextView txtPoints;
    private ImageView imgPhoto;
    private Student student;
    private Integer extraPoints = 1;
    private boolean isExpanded = false;
    private Database database;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.database = new Database();

        this.notificationSound = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));

        this.loadingDialog = new LoadingDialog(this);

        this.txtName = this.findViewById(R.id.txtName);
        this.txtPoints = this.findViewById(R.id.txtPoints);
        this.imgPhoto = this.findViewById(R.id.imgPhoto);

        Button btnPlus = this.findViewById(R.id.btnPlus);
        btnPlus.setOnClickListener(v -> {
            if (this.extraPoints > 4) return;
            this.extraPoints++;
            this.txtPoints.setText(this.extraPoints.toString());
        });

        Button btnLess = this.findViewById(R.id.btnLess);
        btnLess.setOnClickListener(v -> {
            if (this.extraPoints < 2) return;
            this.extraPoints--;
            this.txtPoints.setText(this.extraPoints.toString());
        });

        Button btnSave = this.findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {

            MainActivity.this.loadingDialog.show();

            Map<String, Object> selection = new HashMap<>();
            this.student.grades += this.extraPoints;
            selection.put("grades", this.student.grades);

            this.database.updateStudentFields(
                    MainActivity.this.student.id,
                    selection,
                    (success, object) -> {
                        if (success) MainActivity.this.notificationSound.play();
                        MainActivity.this.loadingDialog.dismiss();
                    }
            );
        });

        MaterialButton btnAddStudent = this.findViewById(R.id.btnAddStudent);
        btnAddStudent.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AddStudent.class);
            startActivity(intent);
        });

        MaterialButton btnListStudent = this.findViewById(R.id.btnListStudents);
        btnListStudent.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ListStudents.class);
            startActivity(intent);
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

        CodeScannerView scannerView = findViewById(R.id.scnView);

        this.mCodeScanner = new CodeScanner(this, scannerView);
        this.mCodeScanner.setDecodeCallback(new DecodeCallback() {
            /**
             * Called when decoder has successfully decoded the code
             * Note that this method always called on a worker thread
             *
             * @param result Encapsulates the result of decoding a barcode within an image
             * @see Handler
             * @see Looper#getMainLooper()
             * @see Activity#runOnUiThread(Runnable)
             */
            @Override
            public void onDecoded(@NonNull Result result) {
                runOnUiThread(() -> {

                    if (notificationSound != null) {
                        MainActivity.this.notificationSound.play();
                    }

                    MainActivity.this.loadingDialog.show();
                    MainActivity.this.loadingDialog.setCancelable(false);
                    MainActivity.this.loadingDialog.setCanceledOnTouchOutside(false);
                    MainActivity.this.database.loadStudent(result.getText(), new IDatabaseOnLoad<Student>() {
                        @Override
                        public void onLoadComplete(boolean success, Student object) {

                            if (!success) {
                                Toast.makeText(MainActivity.this, "Student non existent!", Toast.LENGTH_SHORT).show();
                            } else {
                                MainActivity.this.student = object;
                                MainActivity.this.txtName.setText(object.name);
                                MainActivity.this.imgPhoto.setImageBitmap(BitmapCoverter.stringToBitmap(object.photo));
                            }
                            MainActivity.this.loadingDialog.dismiss();
                        }
                    });
                });
            }
        });


        scannerView.setOnClickListener(view -> mCodeScanner.startPreview());
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCodeScanner.startPreview();
    }

    @Override
    protected void onPause() {
        mCodeScanner.releaseResources();
        super.onPause();
    }
}