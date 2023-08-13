package org.dedira.qrnotas;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;


public class AddStudent extends AppCompatActivity {


    private ImageView imgPhoto;
    private ImageView imgQrcode;

    private Bitmap btmPhoto;

    private EditText txtName;

    private ActivityResultLauncher<Intent> cameraLauncher;

    private void generateQRCode(String data) {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        try {
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, 300, 300);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap btmQrcode = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    btmQrcode.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            imgQrcode.setImageBitmap(btmQrcode);
        } catch (WriterException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_student);

        this.imgQrcode = this.findViewById(R.id.imgQrcode);
        this.imgPhoto = this.findViewById(R.id.imgFoto);
        this.txtName = this.findViewById(R.id.txtName);
        Button btnSave = this.findViewById(R.id.btnSave);

        btnSave.setOnClickListener(v -> {
            Student s = new Student();
            s.name = this.txtName.getText().toString();
            s.photo = BitmapCoverter.stringToBitmap(this.btmPhoto);

            Database.saveStudent(s, (success, student) -> {
                if (success) {
                    AddStudent.this.generateQRCode(student.id);
                    Toast.makeText(this, "Student saved" + student.id, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Student not saved!", Toast.LENGTH_LONG).show();
                }
            });
        });


        cameraLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        if (result.getResultCode() == RESULT_OK) {
                            Intent data = result.getData();
                            Bundle extras = data.getExtras();
                            AddStudent.this.btmPhoto = BitmapCoverter.scaleBitmap((Bitmap) extras.get("data"),150,150);
                            AddStudent.this.imgPhoto.setImageBitmap(AddStudent.this.btmPhoto);
                        }
                    }
                });

        findViewById(R.id.imgFoto).setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cameraLauncher.launch(takePictureIntent);
            } else {
                Toast.makeText(this, "No permission to access the camera!", Toast.LENGTH_LONG).show();
            }
        });
    }
}