package org.dedira.qrnotas.activities;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
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
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.QrCode;

import java.io.ByteArrayOutputStream;

public class AddOrEditStudent extends AppCompatActivity {

    private Database database;
    private LoadingDialog loadingDialog;
    private ImageView imgPhoto;
    private ImageView imgQrcode;
    private Bitmap btmPhoto;
    private EditText txtName;
    private Student loadedStudent;

    // Add a new method to rotate the bitmap
    private Bitmap rotateBitmap(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private Uri getImageUri(Context context, Bitmap bitmap) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        String path = MediaStore.Images.Media.insertImage(context.getContentResolver(), bitmap, "QR_Code", null);
        return Uri.parse(path);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_edit_student);

        this.database = new Database();

        this.txtName = this.findViewById(R.id.txtName);
        this.loadingDialog = new LoadingDialog(this);

        /*********************************************************/
        /***** Opens the camera to take a student photo **********/
        /*********************************************************/
        ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                Intent data = result.getData();
                Bundle extras = data.getExtras();
                Bitmap capturedImage = (Bitmap) extras.get("data");

                // Rotate the bitmap by 90 degrees
                Bitmap rotatedImage = rotateBitmap(capturedImage, 90);

                AddOrEditStudent.this.btmPhoto = BitmapConverter.scaleBitmap(rotatedImage, 150, 200);
                AddOrEditStudent.this.imgPhoto.setImageBitmap(AddOrEditStudent.this.btmPhoto);
            }
        });

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        takePictureIntent.putExtra(MediaStore.EXTRA_SCREEN_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_LOCKED);

        /**********************************************************************/
        /********* Get student id (if any) from previous activity *************/
        /**********************************************************************/
        String selectedStudentId = getIntent().getStringExtra("selectedStudentId");
        if (selectedStudentId != null) {
            this.database.loadStudent(selectedStudentId, (success, object) -> {
                this.loadedStudent = object;
                imgQrcode.setImageBitmap(QrCode.generateQRCode(object.id));
                this.btmPhoto = BitmapConverter.stringToBitmap(object.photo);
                this.imgPhoto.setImageBitmap(this.btmPhoto);
                this.txtName.setText(object.name);
            });
        }

        /**********************************************************************/
        /********* Opens the share activity for generated qrCode **************/
        /**********************************************************************/
        this.imgQrcode = this.findViewById(R.id.imgQrcode);

        imgQrcode.setOnClickListener(v -> {
            // Get the QR code image from the ImageView
            Bitmap qrCodeBitmap = ((BitmapDrawable) imgQrcode.getDrawable()).getBitmap();

            if (qrCodeBitmap == null) return;

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

        /*********************************************************/
        /********* Save student and generate qrCode **************/
        /*********************************************************/
        Button btnSave = this.findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {

            this.loadingDialog.show();

            if (AddOrEditStudent.this.loadedStudent == null) {
                this.loadedStudent = new Student();
            }

            this.loadedStudent.name = this.txtName.getText().toString();
            this.loadedStudent.photo = BitmapConverter.bitmapToString(this.btmPhoto);

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

        this.imgPhoto = this.findViewById(R.id.imgPhoto);
        this.imgPhoto.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraLauncher.launch(takePictureIntent);
            } else {
                Toast.makeText(this, "No permission to access the camera!", Toast.LENGTH_LONG).show();
            }
        });
    }
}