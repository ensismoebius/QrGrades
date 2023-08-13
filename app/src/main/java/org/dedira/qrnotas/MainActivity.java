package org.dedira.qrnotas;

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
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.zxing.Result;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private LoadingDialog loadingDialog;
    private Ringtone notificationSound;
    private CodeScanner mCodeScanner;
    private FloatingActionButton btnAddStudent;
    private TextView txtName;
    private TextView txtPoints;
    private ImageView imgPhoto;
    private Button btnPlus;
    private Button btnLess;
    private Button btnSave;
    private Student student;
    private Integer extraPoints = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.notificationSound = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));

        this.loadingDialog = new LoadingDialog(this);

        this.txtName = this.findViewById(R.id.txtName);
        this.txtPoints = this.findViewById(R.id.txtPoints);
        this.imgPhoto = this.findViewById(R.id.imgFoto);

        this.btnPlus = this.findViewById(R.id.btnPlus);
        this.btnPlus.setOnClickListener(v -> {
            if (this.extraPoints > 4) return;
            this.extraPoints++;
            this.txtPoints.setText(this.extraPoints.toString());
        });

        this.btnLess = this.findViewById(R.id.btnLess);
        this.btnLess.setOnClickListener(v -> {
            if (this.extraPoints < 2) return;
            this.extraPoints--;
            this.txtPoints.setText(this.extraPoints.toString());
        });

        this.btnSave = this.findViewById(R.id.btnSave);
        this.btnSave.setOnClickListener(v -> {

            MainActivity.this.loadingDialog.show();

            Map<String, Object> selection = new HashMap<>();
            this.student.grades += this.extraPoints;
            selection.put("grades", this.student.grades);

            Database.updateStudentFields(
                    MainActivity.this.student.id,
                    selection,
                    (success, object) -> {
                        if (success) MainActivity.this.notificationSound.play();
                        MainActivity.this.loadingDialog.dismiss();
                    }
            );
        });

        this.btnAddStudent = this.findViewById(R.id.btnAddStudent);
        this.btnAddStudent.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AddStudent.class);
            startActivity(intent);
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
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {

                        if (notificationSound != null) {
                            MainActivity.this.notificationSound.play();
                        }

                        MainActivity.this.loadingDialog.show();
                        MainActivity.this.loadingDialog.setCancelable(false);
                        MainActivity.this.loadingDialog.setCanceledOnTouchOutside(false);
                        Database.loadStudent("vCWyyQKZTxFMYYob5KX9", new Database.OnLoadListener<Student>() {
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
                    }
                });
            }
        });


        scannerView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mCodeScanner.startPreview();
            }
        });
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