/*
 * QrGrades — track student grades/points, scan QR codes to award points, and optionally
 * expose the same data to a browser on the local network.
 * Copyright (C) 2026 André Furlan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.dedira.qrnotas.fragments;

import android.Manifest;
import android.app.AlertDialog;
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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputLayout;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.QrCodeDialog;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.KeyboardUtils;
import org.dedira.qrnotas.util.QrCode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Used both to add a brand-new student and to edit an existing one — which mode it's in is
 * decided by whether a {@code selectedStudentId} argument was supplied by whichever screen
 * navigated here (e.g. {@link StudentListFragment}). The teacher can take/pick a photo, type a
 * name, and check which disciplines (and which class group within each discipline) the student
 * belongs to. Saving creates or updates the Student record plus one Enrollment per checked
 * discipline, and shows a generated QR code that can be printed or shared.
 */
public class AddOrEditStudentFragment extends BaseFragment {

    private ImageView imgPhoto;
    private ImageView imgQrcode;
    private MaterialButton btnSave;
    private Bitmap btmPhoto;
    private EditText txtName;
    private LinearLayout disciplinesChecklist;
    private View txtNoDisciplines;
    private Student loadedStudent;
    private Uri pendingCaptureUri;
    private List<Discipline> allDisciplines = new ArrayList<>();
    private List<ClassGroup> allClassGroups = new ArrayList<>();
    private final Map<String, Enrollment> existingEnrollmentByDiscipline = new HashMap<>();
    private final Map<String, String> selectedClassGroupByDiscipline = new HashMap<>();

    private ActivityResultLauncher<Intent> cameraLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<PickVisualMediaRequest> galleryLauncher;

    public AddOrEditStudentFragment() {
        super(R.layout.activity_add_edit_student);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.cameraLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != android.app.Activity.RESULT_OK || pendingCaptureUri == null) return;
            onPhotoCaptured(pendingCaptureUri);
        });

        this.cameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                launchCamera();
            } else {
                Toast.makeText(requireContext(), R.string.camera_permission_denied, Toast.LENGTH_LONG).show();
            }
        });

        this.galleryLauncher = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) onPhotoCaptured(uri);
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        String selectedStudentId = args != null ? args.getString("selectedStudentId") : null;
        boolean isEditFlow = selectedStudentId != null;

        this.txtName = view.findViewById(R.id.txtName);
        this.imgPhoto = view.findViewById(R.id.imgPhoto);
        this.imgQrcode = view.findViewById(R.id.imgQrcode);
        this.btnSave = view.findViewById(R.id.btnSave);
        this.disciplinesChecklist = view.findViewById(R.id.disciplinesChecklist);
        this.txtNoDisciplines = view.findViewById(R.id.txtNoDisciplines);
        KeyboardUtils.focusAndShowKeyboard(this.txtName);

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);

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

        this.imgPhoto.setOnClickListener(v -> requestPhotoSource());
        FloatingActionButton btnCameraBadge = view.findViewById(R.id.btnCameraBadge);
        btnCameraBadge.setOnClickListener(v -> requestPhotoSource());

        if (isEditFlow) {
            toolbar.setTitle(R.string.edit_student_title);
        }
        loadDropdownDataThenStudent(selectedStudentId, isEditFlow);

        view.findViewById(R.id.qrCard).setOnClickListener(v -> shareQrCode());

        this.btnSave.setOnClickListener(v -> saveStudent());
    }

    private void loadDropdownDataThenStudent(String selectedStudentId, boolean isEditFlow) {
        database.loadAllDisciplines((success, disciplines) -> {
            if (getView() == null) return;
            this.allDisciplines = disciplines != null ? disciplines : new ArrayList<>();

            database.loadAllClassGroups((success2, groups) -> {
                if (getView() == null) return;
                this.allClassGroups = groups != null ? groups : new ArrayList<>();

                if (!isEditFlow) {
                    setupDisciplinesChecklist();
                    return;
                }

                database.loadStudent(selectedStudentId, (success3, object) -> {
                    if (getView() == null) return;
                    if (!success3 || object == null) {
                        Toast.makeText(requireContext(), R.string.student_not_found, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    this.loadedStudent = object;
                    this.imgQrcode.setImageBitmap(QrCode.generateQRCode(object.id));
                    Bitmap loadedPhoto = BitmapConverter.loadBitmap(object.photoPath);
                    if (loadedPhoto != null) this.imgPhoto.setImageBitmap(loadedPhoto);
                    this.txtName.setText(object.name);

                    database.loadEnrollmentsForStudent(selectedStudentId, (success4, enrollments) -> {
                        if (getView() == null) return;
                        if (enrollments != null) {
                            for (Enrollment e : enrollments) {
                                ClassGroup cg = findClassGroup(e.classGroupId);
                                if (cg == null) continue;
                                this.existingEnrollmentByDiscipline.put(cg.disciplineId, e);
                                this.selectedClassGroupByDiscipline.put(cg.disciplineId, e.classGroupId);
                            }
                        }
                        setupDisciplinesChecklist();
                    });
                });
            });
        });
    }

    private ClassGroup findClassGroup(String classGroupId) {
        for (ClassGroup g : allClassGroups) {
            if (g.id.equals(classGroupId)) return g;
        }
        return null;
    }

    private void setupDisciplinesChecklist() {
        disciplinesChecklist.removeAllViews();

        if (allDisciplines.isEmpty()) {
            txtNoDisciplines.setVisibility(View.VISIBLE);
            updateSaveEnabled();
            return;
        }
        txtNoDisciplines.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (Discipline d : allDisciplines) {
            View row = inflater.inflate(R.layout.item_discipline_checkbox, disciplinesChecklist, false);
            MaterialCheckBox chk = row.findViewById(R.id.chkDiscipline);
            TextInputLayout classGroupInputLayout = row.findViewById(R.id.classGroupInputLayout);
            AutoCompleteTextView dropdown = row.findViewById(R.id.dropdownClassGroup);

            List<ClassGroup> groupsForDiscipline = new ArrayList<>();
            for (ClassGroup g : allClassGroups) {
                if (d.id.equals(g.disciplineId)) groupsForDiscipline.add(g);
            }

            if (groupsForDiscipline.isEmpty()) {
                chk.setText(getString(R.string.discipline_no_classes, d.name));
                chk.setEnabled(false);
                disciplinesChecklist.addView(row);
                continue;
            }

            chk.setText(d.name);

            List<String> names = new ArrayList<>();
            for (ClassGroup g : groupsForDiscipline) names.add(g.name);
            dropdown.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, names));
            dropdown.setOnItemClickListener((parent, v, position, id) -> {
                selectedClassGroupByDiscipline.put(d.id, groupsForDiscipline.get(position).id);
                updateSaveEnabled();
            });

            String preselectedClassGroupId = selectedClassGroupByDiscipline.get(d.id);
            boolean checked = preselectedClassGroupId != null;
            chk.setChecked(checked);
            classGroupInputLayout.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) {
                ClassGroup match = findClassGroup(preselectedClassGroupId);
                if (match != null) dropdown.setText(match.name, false);
            }

            chk.setOnCheckedChangeListener((buttonView, isChecked) -> {
                classGroupInputLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (isChecked) {
                    ClassGroup defaultGroup = groupsForDiscipline.get(0);
                    selectedClassGroupByDiscipline.put(d.id, defaultGroup.id);
                    dropdown.setText(defaultGroup.name, false);
                } else {
                    selectedClassGroupByDiscipline.remove(d.id);
                }
                updateSaveEnabled();
            });

            disciplinesChecklist.addView(row);
        }

        updateSaveEnabled();
    }

    private void updateSaveEnabled() {
        boolean hasName = txtName.getText().toString().trim().length() > 0;
        btnSave.setEnabled(hasName && !selectedClassGroupByDiscipline.isEmpty());
    }

    private void requestPhotoSource() {
        String[] options = {
                getString(R.string.photo_source_camera),
                getString(R.string.photo_source_gallery)
        };

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.photo_source_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) requestCameraCapture();
                    else launchGallery();
                })
                .show();
    }

    private void requestCameraCapture() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchGallery() {
        galleryLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void launchCamera() {
        pendingCaptureUri = createCaptureUri();
        if (pendingCaptureUri == null) {
            Toast.makeText(requireContext(), R.string.photo_capture_failed, Toast.LENGTH_LONG).show();
            return;
        }

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCaptureUri);
        takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        if (takePictureIntent.resolveActivity(requireContext().getPackageManager()) == null) {
            Toast.makeText(requireContext(), R.string.no_camera_app, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            cameraLauncher.launch(takePictureIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.no_camera_app, Toast.LENGTH_LONG).show();
        }
    }

    private Uri createCaptureUri() {
        try {
            File dir = new File(requireContext().getCacheDir(), "captures");
            if (!dir.exists()) dir.mkdirs();
            File file = File.createTempFile("capture_", ".jpg", dir);
            return FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file);
        } catch (IOException e) {
            return null;
        }
    }

    private void onPhotoCaptured(Uri captureUri) {
        if (getView() == null) return;
        Bitmap decoded = decodeSampledBitmapFromUri(captureUri, 600, 800);
        if (decoded == null) {
            Toast.makeText(requireContext(), R.string.photo_capture_failed, Toast.LENGTH_LONG).show();
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
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, options);
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;

            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
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
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
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
            Toast.makeText(requireContext(), R.string.name_required, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean hasExistingPhoto = loadedStudent != null && loadedStudent.photoPath != null;
        if (btmPhoto == null && !hasExistingPhoto) {
            Toast.makeText(requireContext(), R.string.photo_required, Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedClassGroupByDiscipline.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_discipline_selected, Toast.LENGTH_SHORT).show();
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

        if (this.btmPhoto != null) {
            String savedPath = BitmapConverter.saveStudentPhoto(requireContext(), this.btmPhoto, this.loadedStudent.id);
            if (savedPath != null) this.loadedStudent.photoPath = savedPath;
        }

        this.database.saveStudent(this.loadedStudent, (success, student) -> {
            if (getView() == null) return;
            if (!success) {
                Toast.makeText(requireContext(), R.string.student_not_saved, Toast.LENGTH_LONG).show();
                this.loadingDialog.dismiss();
                return;
            }

            syncEnrollments(student);
        });
    }

    private void syncEnrollments(Student student) {
        List<String> toRemove = new ArrayList<>();
        for (String disciplineId : existingEnrollmentByDiscipline.keySet()) {
            if (!selectedClassGroupByDiscipline.containsKey(disciplineId)) toRemove.add(disciplineId);
        }

        int pending = selectedClassGroupByDiscipline.size() + toRemove.size();
        if (pending == 0) {
            finishSave(student);
            return;
        }

        int[] remaining = {pending};
        boolean[] hadFailure = {false};

        Runnable onStepDone = () -> {
            remaining[0]--;
            if (remaining[0] > 0) return;
            if (getView() == null) return;

            if (hadFailure[0]) {
                this.loadingDialog.dismiss();
                Toast.makeText(requireContext(), R.string.student_not_saved, Toast.LENGTH_LONG).show();
            } else {
                finishSave(student);
            }
        };

        for (Map.Entry<String, String> entry : selectedClassGroupByDiscipline.entrySet()) {
            String disciplineId = entry.getKey();
            Enrollment existing = existingEnrollmentByDiscipline.get(disciplineId);
            Enrollment enrollment = existing != null ? existing : new Enrollment();
            enrollment.studentId = student.id;
            enrollment.classGroupId = entry.getValue();

            this.database.saveEnrollment(enrollment, (enrollmentSuccess, savedEnrollment) -> {
                if (enrollmentSuccess) existingEnrollmentByDiscipline.put(disciplineId, savedEnrollment);
                else hadFailure[0] = true;
                onStepDone.run();
            });
        }

        for (String disciplineId : toRemove) {
            Enrollment existing = existingEnrollmentByDiscipline.get(disciplineId);
            this.database.deleteEnrollment(existing.id, (deleteSuccess, ignored) -> {
                if (deleteSuccess) existingEnrollmentByDiscipline.remove(disciplineId);
                else hadFailure[0] = true;
                onStepDone.run();
            });
        }
    }

    private void finishSave(Student student) {
        this.loadingDialog.dismiss();
        imgQrcode.setImageBitmap(QrCode.generateQRCode(student.id));
        Toast.makeText(requireContext(), getString(R.string.student_saved, student.name), Toast.LENGTH_LONG).show();
        new QrCodeDialog(requireContext(), student).show();
    }

    private void shareQrCode() {
        Drawable drawable = imgQrcode.getDrawable();
        if (!(drawable instanceof BitmapDrawable)) return;
        Bitmap qrCodeBitmap = ((BitmapDrawable) drawable).getBitmap();
        QrCode.share(requireContext(), qrCodeBitmap);
    }
}
