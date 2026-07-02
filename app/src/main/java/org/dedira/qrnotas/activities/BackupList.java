package org.dedira.qrnotas.activities;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.LoadingDialog;
import org.dedira.qrnotas.util.ActivityTransitions;
import org.dedira.qrnotas.util.BackupAdapter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.DbBackup;
import org.dedira.qrnotas.util.EdgeToEdge;

import java.io.File;
import java.io.IOException;

public class BackupList extends AppCompatActivity {
    private static final int MAX_SNAPSHOTS = 15;

    private BackupAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;
    private Database database;
    private LoadingDialog loadingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.enter(this);
        setContentView(R.layout.activity_entity_list);
        EdgeToEdge.apply(this);

        this.database = new Database(this);
        this.loadingDialog = new LoadingDialog(this);

        MaterialToolbar toolbar = this.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.backups_title);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAfterTransition();
            }
        });

        this.txtEmpty = this.findViewById(R.id.txtEmpty);
        this.txtEmpty.setText(R.string.no_backups_yet);

        this.adapter = new BackupAdapter(this, new BackupAdapter.Listener() {
            @Override
            public void onRestore(File snapshot) {
                confirmRestore(snapshot);
            }

            @Override
            public void onDelete(File snapshot) {
                DbBackup.deleteSnapshot(snapshot);
                loadSnapshots();
            }
        });
        this.recyclerView = this.findViewById(R.id.lstEntities);
        this.recyclerView.setAdapter(adapter);

        FloatingActionButton btnBackupNow = this.findViewById(R.id.btnAddEntity);
        btnBackupNow.setImageResource(R.drawable.ic_backup);
        btnBackupNow.setContentDescription(getString(R.string.backup_now));
        btnBackupNow.setOnClickListener(v -> createManualBackup());

        loadSnapshots();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSnapshots();
    }

    private void loadSnapshots() {
        adapter.submitList(DbBackup.listSnapshots(this));
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean isEmpty = adapter.getSnapshotCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void createManualBackup() {
        loadingDialog.show();
        File dest = DbBackup.newSnapshotFile(this, true);
        database.createSnapshot(dest, (success, error) -> {
            loadingDialog.dismiss();
            if (success) {
                DbBackup.pruneOldSnapshots(this, MAX_SNAPSHOTS);
                Toast.makeText(this, R.string.backup_created, Toast.LENGTH_SHORT).show();
                loadSnapshots();
            } else {
                Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmRestore(File snapshot) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(R.string.restore_confirm_message)
                .setPositiveButton(R.string.restore_action, (dialog, which) -> runRestore(snapshot))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void runRestore(File snapshot) {
        loadingDialog.show();
        new Thread(() -> {
            try {
                DbBackup.restoreSnapshot(this, snapshot);
                runOnUiThread(this::restartApp);
            } catch (IOException e) {
                runOnUiThread(() -> {
                    loadingDialog.dismiss();
                    Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void restartApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent == null) {
            loadingDialog.dismiss();
            Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        Runtime.getRuntime().exit(0);
    }
}
