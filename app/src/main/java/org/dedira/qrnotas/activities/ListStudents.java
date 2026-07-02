package org.dedira.qrnotas.activities;

import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.LoadingDialog;
import org.dedira.qrnotas.model.CsvImportPlan;
import org.dedira.qrnotas.model.CsvRowError;
import org.dedira.qrnotas.model.CsvStudentRow;
import org.dedira.qrnotas.model.IDatabaseOnLoad;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.ActivityTransitions;
import org.dedira.qrnotas.util.CsvImporter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.DbBackup;
import org.dedira.qrnotas.util.EdgeToEdge;
import org.dedira.qrnotas.util.Exporter;
import org.dedira.qrnotas.util.Importer;
import org.dedira.qrnotas.util.StudentAdapter;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ListStudents extends AppCompatActivity {
    private StudentAdapter arrStudentsAdapter;
    private RecyclerView lstStudents;
    private TextView txtEmpty;
    private Database database;
    private MaterialToolbar toolbar;
    private LoadingDialog loadingDialog;
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<String[]> importFileLauncher;
    private ActivityResultLauncher<String[]> importCsvFileLauncher;
    private String classGroupId;
    private String groupName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.enter(this);
        setContentView(R.layout.activity_list_student);
        EdgeToEdge.apply(this);

        this.database = new Database(this);
        this.loadingDialog = new LoadingDialog(this);
        this.classGroupId = getIntent().getStringExtra("classGroupId");
        this.groupName = getIntent().getStringExtra("groupName");

        this.importFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) prepareJsonImport(uri);
        });

        this.importCsvFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) prepareCsvImport(uri);
        });

        this.toolbar = this.findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.menu_student_list);
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        if (classGroupId != null) toolbar.setTitle(groupName);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (arrStudentsAdapter.isSelectionMode()) {
                    arrStudentsAdapter.setSelectionMode(false);
                } else {
                    finishAfterTransition();
                }
            }
        });

        this.txtEmpty = this.findViewById(R.id.txtEmpty);
        this.arrStudentsAdapter = new StudentAdapter(this);
        this.arrStudentsAdapter.setSelectionListener(this::onSelectionChanged);

        this.lstStudents = this.findViewById(R.id.lstStudents);
        this.lstStudents.setAdapter(this.arrStudentsAdapter);

        this.arrStudentsAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateEmptyState();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                updateEmptyState();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                updateEmptyState();
            }
        });

        new ItemTouchHelper(new SwipeToDeleteCallback()).attachToRecyclerView(this.lstStudents);

        TextInputEditText txtSearch = this.findViewById(R.id.txtSearch);
        txtSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                arrStudentsAdapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        onSelectionChanged(false, 0);
        loadStudents();
    }

    private void loadStudents() {
        IDatabaseOnLoad<ArrayList<Student>> callback = (success, students) -> {
            if (success) {
                arrStudentsAdapter.submitFullList(students);
            } else {
                Toast.makeText(this, R.string.load_students_failed, Toast.LENGTH_SHORT).show();
            }
        };

        if (classGroupId != null) {
            this.database.loadStudentsForClassGroup(classGroupId, callback);
        } else {
            this.database.loadAllStudents(callback);
        }
    }

    private boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_select) {
            arrStudentsAdapter.setSelectionMode(true);
            return true;
        } else if (id == R.id.action_select_all) {
            arrStudentsAdapter.selectAll();
            return true;
        } else if (id == R.id.action_export) {
            onExportClicked();
            return true;
        } else if (id == R.id.action_import) {
            importFileLauncher.launch(new String[]{"application/json"});
            return true;
        } else if (id == R.id.action_import_csv) {
            importCsvFileLauncher.launch(new String[]{"text/csv", "text/comma-separated-values",
                    "text/plain", "application/vnd.ms-excel"});
            return true;
        }
        return false;
    }

    private void onSelectionChanged(boolean selectionMode, int count) {
        toolbar.getMenu().findItem(R.id.action_select).setVisible(!selectionMode);
        toolbar.getMenu().findItem(R.id.action_select_all).setVisible(selectionMode);
        toolbar.getMenu().findItem(R.id.action_export).setVisible(selectionMode);
        toolbar.getMenu().findItem(R.id.action_import).setVisible(!selectionMode);
        toolbar.getMenu().findItem(R.id.action_import_csv).setVisible(!selectionMode);

        if (selectionMode) {
            toolbar.setTitle(getString(R.string.selected_count_title, count));
            toolbar.setNavigationIcon(R.drawable.ic_close);
            toolbar.setNavigationContentDescription(R.string.exiting_selection);
            toolbar.setNavigationOnClickListener(v -> arrStudentsAdapter.setSelectionMode(false));
        } else {
            toolbar.setTitle(classGroupId != null ? groupName : getString(R.string.show_all_students));
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
            toolbar.setNavigationContentDescription(null);
            toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        }
    }

    private void onExportClicked() {
        List<String> selectedIds = arrStudentsAdapter.getSelectedIds();
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, R.string.select_students_first, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] formats = {
                getString(R.string.export_format_md),
                getString(R.string.export_format_pdf),
                getString(R.string.export_format_json)
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.export_format_title)
                .setItems(formats, (dialog, which) -> runExport(selectedIds, which))
                .show();
    }

    private void runExport(List<String> selectedIds, int formatIndex) {
        loadingDialog.show();
        database.loadExportData(selectedIds, (success, data) -> {
            if (!success || data == null) {
                loadingDialog.dismiss();
                Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show();
                return;
            }

            exportExecutor.execute(() -> {
                try {
                    File file;
                    String mime;
                    switch (formatIndex) {
                        case 1:
                            file = Exporter.exportPdf(this, data);
                            mime = "application/pdf";
                            break;
                        case 2:
                            file = Exporter.exportJson(this, data);
                            mime = "application/json";
                            break;
                        default:
                            file = Exporter.exportMarkdown(this, data);
                            mime = "text/markdown";
                            break;
                    }

                    File finalFile = file;
                    String finalMime = mime;
                    runOnUiThread(() -> {
                        loadingDialog.dismiss();
                        Exporter.share(this, finalFile, finalMime);
                        arrStudentsAdapter.setSelectionMode(false);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        loadingDialog.dismiss();
                        Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });
    }

    /* ------------------------------ JSON import ------------------------------ */

    private void prepareJsonImport(Uri uri) {
        loadingDialog.show();
        exportExecutor.execute(() -> {
            List<StudentExportData> data;
            try {
                data = Importer.importJson(this, uri);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingDialog.dismiss();
                    Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show();
                });
                return;
            }

            if (data.isEmpty()) {
                runOnUiThread(() -> {
                    loadingDialog.dismiss();
                    Toast.makeText(this, R.string.import_empty, Toast.LENGTH_SHORT).show();
                });
                return;
            }

            runOnUiThread(() -> {
                loadingDialog.dismiss();
                confirmJsonImport(data);
            });
        });
    }

    private void confirmJsonImport(List<StudentExportData> data) {
        database.loadAllStudents((success, existingStudents) -> {
            Set<String> existingIds = new HashSet<>();
            if (success && existingStudents != null) {
                for (Student s : existingStudents) existingIds.add(s.id);
            }

            Set<String> incomingIds = new HashSet<>();
            for (StudentExportData s : data) if (s.studentId != null) incomingIds.add(s.studentId);

            int existingCount = 0;
            for (String id : incomingIds) if (existingIds.contains(id)) existingCount++;
            int newCount = incomingIds.size() - existingCount;

            new AlertDialog.Builder(this)
                    .setTitle(R.string.import_confirm_title)
                    .setMessage(getString(R.string.json_import_summary, newCount, existingCount))
                    .setPositiveButton(R.string.import_action, (dialog, which) -> runJsonImport(data))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }

    private void runJsonImport(List<StudentExportData> data) {
        loadingDialog.show();
        snapshotThenRun(() -> database.importExportData(data, (success, errorMessage) -> {
            loadingDialog.dismiss();
            if (success) {
                Toast.makeText(this, R.string.import_succeeded, Toast.LENGTH_SHORT).show();
                loadStudents();
            } else {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    /* ------------------------------- CSV import -------------------------------- */

    private void prepareCsvImport(Uri uri) {
        loadingDialog.show();
        exportExecutor.execute(() -> {
            List<CsvStudentRow> rows;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("Unable to open file");
                rows = CsvImporter.parse(in);
            } catch (Exception e) {
                String message = e.getMessage();
                runOnUiThread(() -> {
                    loadingDialog.dismiss();
                    Toast.makeText(this, getString(R.string.csv_import_invalid_file, message), Toast.LENGTH_LONG).show();
                });
                return;
            }

            runOnUiThread(() -> {
                loadingDialog.dismiss();
                if (rows.isEmpty()) {
                    Toast.makeText(this, R.string.csv_import_no_valid_rows, Toast.LENGTH_SHORT).show();
                    return;
                }
                database.resolveCsvRows(rows, (success, plan) -> confirmCsvImport(plan));
            });
        });
    }

    private void confirmCsvImport(CsvImportPlan plan) {
        if (plan.resolved.isEmpty()) {
            Toast.makeText(this, R.string.csv_import_no_valid_rows, Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder message = new StringBuilder(getString(R.string.csv_import_summary,
                plan.newStudentCount(), plan.matchedStudentCount(), plan.errors.size()));
        int shown = 0;
        for (CsvRowError error : plan.errors) {
            if (shown >= 5) break;
            message.append("\n").append(getString(R.string.csv_import_error_line, error.lineNumber, error.reason));
            shown++;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.csv_import_confirm_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.import_action, (dialog, which) -> runCsvImport(plan))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void runCsvImport(CsvImportPlan plan) {
        loadingDialog.show();
        snapshotThenRun(() -> database.importCsvRows(plan.resolved, (success, errorMessage) -> {
            loadingDialog.dismiss();
            if (success) {
                Toast.makeText(this, R.string.csv_import_succeeded, Toast.LENGTH_SHORT).show();
                loadStudents();
            } else {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    /** Snapshots the DB before a bulk-overwrite import commits, then runs it regardless of the
     *  snapshot's own success (a failed safety backup shouldn't block the import itself). */
    private void snapshotThenRun(Runnable importAction) {
        DbBackup.createFullSnapshot(this, database, false, (success, error) -> {
            if (success) DbBackup.pruneOldSnapshots(this, 15);
            importAction.run();
        });
    }

    private void updateEmptyState() {
        boolean isEmpty = arrStudentsAdapter.getItemCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        lstStudents.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private class SwipeToDeleteCallback extends ItemTouchHelper.SimpleCallback {
        private final ColorDrawable background = new ColorDrawable(ContextCompat.getColor(ListStudents.this, R.color.swipe_delete_bg));
        private final Drawable deleteIcon = ContextCompat.getDrawable(ListStudents.this, R.drawable.ic_delete);

        SwipeToDeleteCallback() {
            super(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
        }

        @Override
        public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            if (arrStudentsAdapter.isSelectionMode()) return 0;
            return super.getSwipeDirs(recyclerView, viewHolder);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
            return false;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            arrStudentsAdapter.requestDelete(viewHolder.getBindingAdapterPosition());
        }

        @Override
        public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                                 float dX, float dY, int actionState, boolean isCurrentlyActive) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);

            if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) return;

            View itemView = viewHolder.itemView;
            int iconMargin = (int) dp(16);
            int iconSize = deleteIcon != null ? deleteIcon.getIntrinsicHeight() : 0;
            int iconTop = itemView.getTop() + (itemView.getHeight() - iconSize) / 2;

            if (dX > 0) {
                background.setBounds(itemView.getLeft(), itemView.getTop(), itemView.getLeft() + (int) dX, itemView.getBottom());
                background.draw(c);
                if (deleteIcon != null) {
                    int left = itemView.getLeft() + iconMargin;
                    deleteIcon.setTint(Color.WHITE);
                    deleteIcon.setBounds(left, iconTop, left + iconSize, iconTop + iconSize);
                    deleteIcon.draw(c);
                }
            } else if (dX < 0) {
                background.setBounds(itemView.getRight() + (int) dX, itemView.getTop(), itemView.getRight(), itemView.getBottom());
                background.draw(c);
                if (deleteIcon != null) {
                    int right = itemView.getRight() - iconMargin;
                    deleteIcon.setTint(Color.WHITE);
                    deleteIcon.setBounds(right - iconSize, iconTop, right, iconTop + iconSize);
                    deleteIcon.draw(c);
                }
            }
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
