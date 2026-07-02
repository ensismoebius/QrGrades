package org.dedira.qrnotas.activities;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.LoadingDialog;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.ActivityTransitions;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.QrCode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AddOrEditStudent extends AppCompatActivity {

    private Database database;
    private LoadingDialog loadingDialog;
    private ImageView imgPhoto;
    private ImageView imgQrcode;
    private MaterialButton btnSave;
    private Bitmap btmPhoto;
    private EditText txtName;
    private AutoCompleteTextView dropdownDiscipline;
    private AutoCompleteTextView dropdownClassGroup;
    private Student loadedStudent;
    private Uri pendingCaptureUri;
    private List<Discipline> allDisciplines = new ArrayList<>();
    private List<ClassGroup> allClassGroups = new ArrayList<>();
    private String selectedClassGroupId;

    private ActivityResultLauncher<Intent> cameraLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String selectedStudentId = getIntent().getStringExtra("selectedStudentId");
        boolean isEditFlow = selectedStudentId != null;

        if (isEditFlow) {
            // Delay the enter transition until the shared-element photo has loaded,
            // so it doesn't pop in mid-animation.
            postponeEnterTransition();
        } else {
            ActivityTransitions.enter(this);
        }

        setContentView(R.layout.activity_add_edit_student);

        this.database = new Database(this);

        this.txtName = this.findViewById(R.id.txtName);
        this.imgPhoto = this.findViewById(R.id.imgPhoto);
        this.imgQrcode = this.findViewById(R.id.imgQrcode);
        this.btnSave = this.findViewById(R.id.btnSave);
        this.dropdownDiscipline = this.findViewById(R.id.dropdownDiscipline);
        this.dropdownClassGroup = this.findViewById(R.id.dropdownClassGroup);
        this.loadingDialog = new LoadingDialog(this);

        MaterialToolbar toolbar = this.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAfterTransition();
            }
        });

        this.btnSave.setEnabled(false);
        this.txtName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateSaveEnabled();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        /*********************************************************/
        /***** Camera permission + capture launchers **************/
        /*********************************************************/
        this.cameraLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || pendingCaptureUri == null) return;
            onPhotoCaptured(pendingCaptureUri);
        });

        this.cameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                launchCamera();
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show();
            }
        });

        this.imgPhoto.setOnClickListener(v -> requestCameraCapture());
        FloatingActionButton btnCameraBadge = this.findViewById(R.id.btnCameraBadge);
        btnCameraBadge.setOnClickListener(v -> requestCameraCapture());

        /**********************************************************************/
        /********* Load disciplines/classes, then student (if any) ************/
        /**********************************************************************/
        if (isEditFlow) {
            toolbar.setTitle(R.string.edit_student_title);
            // Safety net: never leave the screen invisible if a DB callback stalls.
            this.imgPhoto.postDelayed(this::startPostponedEnterTransition, 600);
        }
        loadDropdownDataThenStudent(selectedStudentId, isEditFlow);

        /**********************************************************************/
        /********* Opens the share activity for generated qrCode **************/
        /**********************************************************************/
        this.findViewById(R.id.qrCard).setOnClickListener(v -> shareQrCode());

        /*********************************************************/
        /********* Save student and generate qrCode **************/
        /*********************************************************/
        this.btnSave.setOnClickListener(v -> saveStudent());
    }

    private void loadDropdownDataThenStudent(String selectedStudentId, boolean isEditFlow) {
        database.loadAllDisciplines((success, disciplines) -> {
            this.allDisciplines = disciplines != null ? disciplines : new ArrayList<>();
            setupDisciplineDropdown();

            database.loadAllClassGroups((success2, groups) -> {
                this.allClassGroups = groups != null ? groups : new ArrayList<>();

                if (!isEditFlow) return;

                database.loadStudent(selectedStudentId, (success3, object) -> {
                    if (!success3 || object == null) {
                        Toast.makeText(this, R.string.student_not_found, Toast.LENGTH_SHORT).show();
                        startPostponedEnterTransition();
                        return;
                    }
                    this.loadedStudent = object;
                    this.imgQrcode.setImageBitmap(QrCode.generateQRCode(object.id));
                    Bitmap loadedPhoto = BitmapConverter.loadBitmap(object.photoPath);
                    if (loadedPhoto != null) this.imgPhoto.setImageBitmap(loadedPhoto);
                    this.txtName.setText(object.name);
                    prefillDisciplineAndClass(object.classGroupId);
                    startPostponedEnterTransition();
                });
            });
        });
    }

    private void setupDisciplineDropdown() {
        List<String> names = new ArrayList<>();
        for (Discipline d : allDisciplines) names.add(d.name);

        dropdownDiscipline.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        dropdownDiscipline.setOnItemClickListener((parent, view, position, id) ->
                onDisciplinePicked(allDisciplines.get(position).id, null));

        if (allDisciplines.isEmpty()) {
            Toast.makeText(this, R.string.no_disciplines_registered, Toast.LENGTH_LONG).show();
        }
    }

    private void onDisciplinePicked(String disciplineId, String preselectClassGroupId) {
        selectedClassGroupId = null;
        dropdownClassGroup.setText("", false);

        List<ClassGroup> filtered = new ArrayList<>();
        for (ClassGroup g : allClassGroups) {
            if (disciplineId.equals(g.disciplineId)) filtered.add(g);
        }

        List<String> names = new ArrayList<>();
        for (ClassGroup g : filtered) names.add(g.name);

        dropdownClassGroup.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        dropdownClassGroup.setOnItemClickListener((parent, view, position, id) -> {
            selectedClassGroupId = filtered.get(position).id;
            updateSaveEnabled();
        });

        if (preselectClassGroupId != null) {
            for (ClassGroup g : filtered) {
                if (g.id.equals(preselectClassGroupId)) {
                    dropdownClassGroup.setText(g.name, false);
                    selectedClassGroupId = g.id;
                    break;
                }
            }
        }

        updateSaveEnabled();
    }

    private void prefillDisciplineAndClass(String classGroupId) {
        if (classGroupId == null) return;

        ClassGroup group = null;
        for (ClassGroup g : allClassGroups) {
            if (g.id.equals(classGroupId)) {
                group = g;
                break;
            }
        }
        if (group == null) return;

        for (Discipline d : allDisciplines) {
            if (d.id.equals(group.disciplineId)) {
                dropdownDiscipline.setText(d.name, false);
                break;
            }
        }

        onDisciplinePicked(group.disciplineId, classGroupId);
    }

    private void updateSaveEnabled() {
        boolean hasName = txtName.getText().toString().trim().length() > 0;
        btnSave.setEnabled(hasName && selectedClassGroupId != null);
    }

    private void requestCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCamera() {
        pendingCaptureUri = createCaptureUri();
        if (pendingCaptureUri == null) {
            Toast.makeText(this, R.string.photo_capture_failed, Toast.LENGTH_LONG).show();
            return;
        }

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCaptureUri);
        takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        if (takePictureIntent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            cameraLauncher.launch(takePictureIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_LONG).show();
        }
    }

    private Uri createCaptureUri() {
        try {
            File dir = new File(getCacheDir(), "captures");
            if (!dir.exists()) dir.mkdirs();
            File file = File.createTempFile("capture_", ".jpg", dir);
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        } catch (IOException e) {
            return null;
        }
    }

    private void onPhotoCaptured(Uri captureUri) {
        Bitmap decoded = decodeSampledBitmapFromUri(captureUri, 600, 800);
        if (decoded == null) {
            Toast.makeText(this, R.string.photo_capture_failed, Toast.LENGTH_LONG).show();
            return;
        }

        int rotationDegrees = getExifRotationDegrees(captureUri);
        Bitmap rotated = rotationDegrees != 0 ? rotateBitmap(decoded, rotationDegrees) : decoded;

        this.btmPhoto = BitmapConverter.scaleBitmap(rotated, 150, 200);
        this.imgPhoto.setImageBitmap(this.btmPhoto);
    }

    private Bitmap decodeSampledBitmapFromUri(Uri uri, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, options);
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;

            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private int getExifRotationDegrees(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return 0;
            ExifInterface exif = new ExifInterface(in);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }

    private Bitmap rotateBitmap(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private void saveStudent() {
        String name = txtName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean hasExistingPhoto = loadedStudent != null && loadedStudent.photoPath != null;
        if (btmPhoto == null && !hasExistingPhoto) {
            Toast.makeText(this, R.string.photo_required, Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedClassGroupId == null) {
            Toast.makeText(this, R.string.class_group_required, Toast.LENGTH_SHORT).show();
            return;
        }

        this.loadingDialog.show();

        if (this.loadedStudent == null) {
            this.loadedStudent = new Student();
        }
        if (this.loadedStudent.id == null) {
            this.loadedStudent.id = UUID.randomUUID().toString();
        }

        this.loadedStudent.name = name;
        this.loadedStudent.classGroupId = selectedClassGroupId;

        if (this.btmPhoto != null) {
            String savedPath = BitmapConverter.saveStudentPhoto(this, this.btmPhoto, this.loadedStudent.id);
            if (savedPath != null) this.loadedStudent.photoPath = savedPath;
        }

        this.database.saveStudent(this.loadedStudent, (success, student) -> {
            if (success) {
                imgQrcode.setImageBitmap(QrCode.generateQRCode(student.id));
                Toast.makeText(this, getString(R.string.student_saved, student.name), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.student_not_saved, Toast.LENGTH_LONG).show();
            }
            this.loadingDialog.dismiss();
        });
    }

    private void shareQrCode() {
        Drawable drawable = imgQrcode.getDrawable();
        if (!(drawable instanceof BitmapDrawable)) return;
        Bitmap qrCodeBitmap = ((BitmapDrawable) drawable).getBitmap();
        if (qrCodeBitmap == null) return;

        try {
            File dir = new File(getCacheDir(), "qrcodes");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "qrcode_" + System.currentTimeMillis() + ".png");

            try (FileOutputStream out = new FileOutputStream(file)) {
                qrCodeBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }

            Uri qrCodeUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, qrCodeUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "QR Code Share");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "Share QR Code"));
        } catch (IOException e) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
