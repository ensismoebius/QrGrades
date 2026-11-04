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

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.util.AutoBackup;
import org.dedira.qrnotas.util.adapters.BackupAdapter;
import org.dedira.qrnotas.util.DbBackup;
import org.dedira.qrnotas.util.Exporter;

import java.io.File;
import java.io.IOException;

/**
 * Screen that lists the database backup "snapshots" the app has saved on disk, and lets the
 * teacher create a new backup, restore an existing one, or delete one. Reached from the "More"
 * tab's "Backups" card.
 */
public class BackupListFragment extends BaseFragment {
    // Safety cap so backups don't grow forever and fill up the device's storage: once we pass
    // this many snapshots, the oldest ones get deleted automatically after a successful backup.
    private static final int MAX_SNAPSHOTS = 15;

    private BackupAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;

    public BackupListFragment() {
        super(R.layout.activity_entity_list);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.backups_title);
        toolbar.inflateMenu(R.menu.menu_backups);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.action_auto_backup) return false;
            showAutoBackupSettings();
            return true;
        });

        this.txtEmpty = view.findViewById(R.id.txtEmpty);
        this.txtEmpty.setText(R.string.no_backups_yet);

        this.adapter = new BackupAdapter(requireContext(), new BackupAdapter.Listener() {
            @Override
            public void onRestore(File snapshot) {
                confirmRestore(snapshot);
            }

            @Override
            public void onDelete(File snapshot) {
                DbBackup.deleteSnapshot(snapshot);
                loadSnapshots();
            }

            @Override
            public void onShare(File snapshot) {
                shareSnapshot(snapshot);
            }
        });
        this.recyclerView = view.findViewById(R.id.lstEntities);
        this.recyclerView.setAdapter(adapter);

        FloatingActionButton btnBackupNow = view.findViewById(R.id.btnAddEntity);
        btnBackupNow.setImageResource(R.drawable.ic_backup);
        btnBackupNow.setContentDescription(getString(R.string.backup_now));
        btnBackupNow.setOnClickListener(v -> createManualBackup());

        loadSnapshots();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadSnapshots();
    }

    private void loadSnapshots() {
        adapter.submitList(DbBackup.listSnapshots(requireContext()));
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean isEmpty = adapter.getSnapshotCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void createManualBackup() {
        loadingDialog.show();
        DbBackup.createFullSnapshot(requireContext(), database, true, (success, error) -> {
            if (getView() == null) return;
            loadingDialog.dismiss();
            if (success) {
                DbBackup.pruneOldSnapshots(requireContext(), MAX_SNAPSHOTS);
                Toast.makeText(requireContext(), R.string.backup_created, Toast.LENGTH_SHORT).show();
                loadSnapshots();
            } else {
                Toast.makeText(requireContext(), R.string.backup_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** How often the app backs itself up, without being asked. */
    private void showAutoBackupSettings() {
        final int[] choices = {AutoBackup.DAILY, AutoBackup.WEEKLY, AutoBackup.OFF};
        final String[] labels = {
                getString(R.string.auto_backup_daily),
                getString(R.string.auto_backup_weekly),
                getString(R.string.auto_backup_off)
        };
        int current = AutoBackup.intervalDays(requireContext());
        int checked = 0;
        for (int i = 0; i < choices.length; i++) if (choices[i] == current) checked = i;

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.auto_backup_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AutoBackup.setIntervalDays(requireContext(), choices[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void shareSnapshot(File snapshot) {
        try {
            Exporter.share(requireContext(), snapshot, "application/zip");
        } catch (IllegalArgumentException e) {
            Toast.makeText(requireContext(), R.string.share_backup_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmRestore(File snapshot) {
        new AlertDialog.Builder(requireContext())
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
                DbBackup.restoreSnapshot(requireContext(), snapshot);
                requireActivity().runOnUiThread(this::restartApp);
            } catch (IOException e) {
                requireActivity().runOnUiThread(() -> {
                    if (getView() == null) return;
                    loadingDialog.dismiss();
                    Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /** Fully restarts the app process after a successful restore, so every screen reloads fresh against the newly-restored database. */
    private void restartApp() {
        Intent intent = requireContext().getPackageManager().getLaunchIntentForPackage(requireContext().getPackageName());
        if (intent == null) {
            if (getView() != null) {
                loadingDialog.dismiss();
                Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        Runtime.getRuntime().exit(0);
    }
}
