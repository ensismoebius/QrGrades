package org.dedira.qrnotas.activities;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.LoadingDialog;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.BitmapCoverter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.QrCode;

import java.io.ByteArrayOutputStream;

public class AddStudent extends AppCompatActivity {

    private Database database;
    private LoadingDialog loadingDialog;
    private ImageView imgPhoto;
    private ImageView imgQrcode;
    private Bitmap btmPhoto;
    private EditText txtName;
    private ActivityResultLauncher<Intent> cameraLauncher;

    private Student loadedStudent;


    // Method to get URI from Bitmap
    private Uri getImageUri(Context context, Bitmap bitmap) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        String path = MediaStore.Images.Media.insertImage(context.getContentResolver(), bitmap, "QR_Code", null);
        return Uri.parse(path);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_student);

        this.imgQrcode = this.findViewById(R.id.imgQrcode);
        this.imgPhoto = this.findViewById(R.id.imgPhoto);
        this.txtName = this.findViewById(R.id.txtName);
        this.loadingDialog = new LoadingDialog(this);
        this.database = new Database();

        String selectedStudentId = getIntent().getStringExtra("selectedStudentId");
        if (selectedStudentId != null) {
            AddStudent.this.database.loadStudent(selectedStudentId, (success, object) -> {
                AddStudent.this.loadedStudent = object;
                imgQrcode.setImageBitmap(QrCode.generateQRCode(object.id));
                AddStudent.this.btmPhoto = BitmapCoverter.stringToBitmap(object.photo);
                AddStudent.this.imgPhoto.setImageBitmap(AddStudent.this.btmPhoto);
                AddStudent.this.txtName.setText(object.name);
            });
        }

        imgQrcode.setOnClickListener(v -> {
            // Get the QR code image from the ImageView
            Bitmap qrCodeBitmap = ((BitmapDrawable) imgQrcode.getDrawable()).getBitmap();

            // Convert the QR code bitmap to a URI
            Uri qrCodeUri = getImageUri(this, qrCodeBitmap);

            // Create a sharing intent
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, qrCodeUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "QR Code Share");

            // Show the sharing chooser
            startActivity(Intent.createChooser(shareIntent, "Share QR Code"));
        });

        Button btnSave = this.findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {

            this.loadingDialog.show();

            if (AddStudent.this.loadedStudent == null) {
                this.loadedStudent = new Student();
            }

            this.loadedStudent.name = this.txtName.getText().toString();
            this.loadedStudent.photo = BitmapCoverter.bitmapToString(this.btmPhoto);

            this.database.saveStudent(this.loadedStudent, (success, student) -> {
                if (success) {
                    imgQrcode.setImageBitmap(QrCode.generateQRCode(student.id));
                    Toast.makeText(this, "Student saved" + student.id, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Student not saved!", Toast.LENGTH_LONG).show();
                }
                this.loadingDialog.dismiss();
            });

        });

        cameraLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                Intent data = result.getData();
                Bundle extras = data.getExtras();
                AddStudent.this.btmPhoto = BitmapCoverter.scaleBitmap((Bitmap) extras.get("data"), 150, 150);
                AddStudent.this.imgPhoto.setImageBitmap(AddStudent.this.btmPhoto);
            }
        });

        findViewById(R.id.imgPhoto).setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cameraLauncher.launch(takePictureIntent);
            } else {
                Toast.makeText(this, "No permission to access the camera!", Toast.LENGTH_LONG).show();
            }
        });
    }
}