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
import org.dedira.qrnotas.util.adapters.BackupAdapter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.DbBackup;
import org.dedira.qrnotas.util.EdgeToEdge;

import java.io.File;
import java.io.IOException;

/**
 * Screen that lists the database backup "snapshots" the app has saved on disk, and lets the
 * teacher create a new backup, restore an existing one, or delete one.
 *
 * <p>This is one of the destinations reachable from the app's main navigation drawer/menu
 * (see {@code Main.java}, which opens this Activity when the user taps the "Backups" item).
 * It does not receive any Intent extras — it always shows every snapshot found on the device.</p>
 *
 * <p>For a beginner: an "Activity" in Android is roughly one full screen of the app. This one
 * reuses a generic list layout ({@code R.layout.activity_entity_list}) that has a toolbar,
 * a RecyclerView (a scrollable list), an "empty state" text view, and a floating action
 * button (the round "+"-like button, here repurposed as "backup now").</p>
 */
public class BackupList extends AppCompatActivity {
    // Safety cap so backups don't grow forever and fill up the device's storage: once we pass
    // this many snapshots, the oldest ones get deleted automatically after a successful backup.
    private static final int MAX_SNAPSHOTS = 15;

    private BackupAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;
    private Database database;
    private LoadingDialog loadingDialog;

    /**
     * Called by Android once, when this screen is first created (e.g. right after the user
     * taps the "Backups" menu item). This is where we inflate the layout and wire up all the
     * views, listeners and initial data — it is the Android equivalent of a constructor for
     * the visible screen.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Plays the shared enter animation configured for this app (e.g. a fade/slide) so
        // navigating into this screen feels consistent with the rest of the app.
        ActivityTransitions.enter(this);
        // Loads the XML layout file and makes it the content the user sees on screen.
        setContentView(R.layout.activity_entity_list);
        // Adjusts padding/margins so content doesn't sit under the status bar / navigation bar
        // when the app draws "edge-to-edge" (behind the system bars) on newer Android versions.
        EdgeToEdge.apply(this);

        // Opens (or creates) the app's SQLite database wrapper, used later when creating a
        // fresh backup snapshot.
        this.database = new Database(this);
        // A small reusable "please wait" dialog shown during slow operations like backup/restore.
        this.loadingDialog = new LoadingDialog(this);

        MaterialToolbar toolbar = this.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.backups_title);
        // Tapping the toolbar's back arrow behaves the same as pressing the system Back button.
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        // Registers custom handling for the system Back button/gesture: instead of the default
        // behavior, we finish the Activity using the matching reverse of the enter transition
        // (finishAfterTransition), so navigating back looks visually consistent.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAfterTransition();
            }
        });

        this.txtEmpty = this.findViewById(R.id.txtEmpty);
        this.txtEmpty.setText(R.string.no_backups_yet);

        // The adapter is what feeds data into the RecyclerView (the scrollable list) and turns
        // each backup file into a row on screen. The anonymous Listener below reacts to the
        // user tapping "restore" or "delete" on a given row.
        this.adapter = new BackupAdapter(this, new BackupAdapter.Listener() {
            @Override
            public void onRestore(File snapshot) {
                confirmRestore(snapshot);
            }

            @Override
            public void onDelete(File snapshot) {
                DbBackup.deleteSnapshot(snapshot);
                // Refresh the on-screen list so the deleted snapshot disappears immediately.
                loadSnapshots();
            }
        });
        this.recyclerView = this.findViewById(R.id.lstEntities);
        this.recyclerView.setAdapter(adapter);

        // Repurpose the generic "add" floating action button as a "backup now" button.
        FloatingActionButton btnBackupNow = this.findViewById(R.id.btnAddEntity);
        btnBackupNow.setImageResource(R.drawable.ic_backup);
        btnBackupNow.setContentDescription(getString(R.string.backup_now));
        btnBackupNow.setOnClickListener(v -> createManualBackup());

        loadSnapshots();
    }

    /**
     * Called by Android every time this screen becomes visible again — not just on first
     * creation, but also when returning from another screen (e.g. after a restore/backup
     * dialog closes). We reload the snapshot list here so it always reflects the latest files
     * on disk, in case they changed while this screen was in the background.
     */
    @Override
    protected void onResume() {
        super.onResume();
        loadSnapshots();
    }

    /**
     * Reads the list of backup snapshot files from disk and pushes it into the adapter so the
     * RecyclerView redraws with the current data, then updates the "no backups yet" placeholder.
     */
    private void loadSnapshots() {
        adapter.submitList(DbBackup.listSnapshots(this));
        updateEmptyState();
    }

    /**
     * Shows the "no backups yet" text and hides the list when there are zero snapshots, or the
     * other way around when there is at least one. This is a common Android pattern: keep both
     * views in the layout and toggle their visibility rather than swapping layouts entirely.
     */
    private void updateEmptyState() {
        boolean isEmpty = adapter.getSnapshotCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    /**
     * Kicks off creation of a brand-new backup snapshot when the user taps the "backup now"
     * button, showing a loading dialog while it runs and reporting success/failure via a Toast.
     */
    private void createManualBackup() {
        loadingDialog.show();
        // createFullSnapshot runs the actual copy/export work and then calls back with
        // (success, error) once it's done. Depending on the implementation this may happen off
        // the main thread, so the callback itself is expected to be safe to update the UI from.
        DbBackup.createFullSnapshot(this, database, true, (success, error) -> {
            loadingDialog.dismiss();
            if (success) {
                // Enforce the MAX_SNAPSHOTS cap by removing the oldest backups beyond the limit.
                DbBackup.pruneOldSnapshots(this, MAX_SNAPSHOTS);
                Toast.makeText(this, R.string.backup_created, Toast.LENGTH_SHORT).show();
                loadSnapshots();
            } else {
                Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Shows a confirmation dialog before overwriting the current database with the given
     * snapshot, since restoring is a destructive action for the user's current data.
     */
    private void confirmRestore(File snapshot) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(R.string.restore_confirm_message)
                .setPositiveButton(R.string.restore_action, (dialog, which) -> runRestore(snapshot))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Actually performs the restore of a chosen snapshot. This is done on a background thread
     * because file I/O (copying/replacing the database file) can take noticeable time and must
     * never run on the main/UI thread, or the app would freeze and Android could kill it.
     */
    private void runRestore(File snapshot) {
        loadingDialog.show();
        // Manually spin up a plain background thread for the restore work.
        new Thread(() -> {
            try {
                DbBackup.restoreSnapshot(this, snapshot);
                // Once the file work is done, hop back onto the main/UI thread to restart the
                // app — UI and Activity lifecycle calls are only allowed from the main thread.
                runOnUiThread(this::restartApp);
            } catch (IOException e) {
                // Any failure also needs to update the UI (dismiss dialog, show a Toast), so it
                // must likewise be posted back to the main thread.
                runOnUiThread(() -> {
                    loadingDialog.dismiss();
                    Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /**
     * Fully restarts the app process after a successful restore, so every screen and cached
     * in-memory value is reloaded fresh against the newly-restored database instead of keeping
     * stale state around.
     */
    private void restartApp() {
        // Ask the system for the Intent that normally launches this app (its "launcher" entry
        // point), so we can relaunch it from scratch.
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent == null) {
            loadingDialog.dismiss();
            Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        // FLAG_ACTIVITY_NEW_TASK starts the app in a fresh task; FLAG_ACTIVITY_CLEAR_TASK wipes
        // out any existing screens/back-stack first, so the user lands on a clean launch.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        // Kill this process outright so no old, now-stale in-memory state survives the restart.
        Runtime.getRuntime().exit(0);
    }
}
