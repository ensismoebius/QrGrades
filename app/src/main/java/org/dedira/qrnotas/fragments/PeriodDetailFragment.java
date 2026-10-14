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
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.util.adapters.PeriodSnapshotAdapter;

import java.util.Date;

/**
 * Shows one closed period's details: its scope (whole discipline, or which class — and, for a
 * reassignment, which new class its students moved to), and the final per-student point total
 * each enrollment had at the moment it was closed. Reached by tapping a row in
 * {@link PeriodListFragment}. Offers "Reabrir" (undo the close) and, only for a reassignment
 * that hasn't been reopened, "Trocar destino" (point the reassignment at a different new class
 * without undoing the close itself).
 */
public class PeriodDetailFragment extends BaseFragment {

    private PeriodSnapshotAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;
    private TextView txtScope;
    private MaterialButton btnReopen;
    private MaterialButton btnChangeDestination;
    private String periodId;

    public PeriodDetailFragment() {
        super(R.layout.activity_period_detail);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        this.periodId = args != null ? args.getString("periodId") : null;

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.period_detail_title);

        this.txtScope = view.findViewById(R.id.txtPeriodScope);
        this.btnReopen = view.findViewById(R.id.btnReopenPeriodDetail);
        this.btnChangeDestination = view.findViewById(R.id.btnChangeDestination);
        this.txtEmpty = view.findViewById(R.id.txtEmpty);
        this.txtEmpty.setText(R.string.no_period_snapshots);

        this.adapter = new PeriodSnapshotAdapter(requireContext());
        this.recyclerView = view.findViewById(R.id.lstEntities);
        this.recyclerView.setAdapter(adapter);

        this.btnReopen.setOnClickListener(v -> confirmReopen());
        this.btnChangeDestination.setOnClickListener(v -> promptChangeDestination());

        loadPeriodAndSnapshots();
    }

    private void loadPeriodAndSnapshots() {
        database.loadPeriodSnapshots(periodId, (success, snapshots) -> {
            if (getView() == null) return;
            adapter.submitList(snapshots);
            updateEmptyState();
        });
        database.loadPeriod(periodId, (success, loadedPeriod) -> {
            if (getView() == null) return;
            bindPeriod(loadedPeriod);
        });
    }

    private void bindPeriod(Period period) {
        if (period == null) return;

        String scope;
        if (!period.isClassClose()) {
            scope = getString(R.string.period_scope_whole_discipline);
        } else if (period.isReassignment()) {
            scope = getString(R.string.period_scope_class_reassigned, period.closedClassGroupName, period.reassignedToClassGroupName);
        } else {
            scope = getString(R.string.period_scope_class_archived, period.closedClassGroupName);
        }
        String closedAt = DateFormat.format("dd/MM/yyyy HH:mm", new Date(period.closedAt)).toString();
        txtScope.setText(scope + "\n" + closedAt);

        boolean reopened = period.isReopened();
        btnReopen.setVisibility(reopened ? View.GONE : View.VISIBLE);
        btnChangeDestination.setVisibility(!reopened && period.isReassignment() ? View.VISIBLE : View.GONE);
    }

    private void updateEmptyState() {
        boolean isEmpty = adapter.getRowCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void confirmReopen() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.reopen_period_confirm_title)
                .setMessage(R.string.reopen_period_confirm_message)
                .setPositiveButton(R.string.reopen_period, (dialog, which) -> {
                    loadingDialog.show();
                    database.reopenPeriod(periodId, (success, error) -> {
                        if (getView() == null) return;
                        loadingDialog.dismiss();
                        Toast.makeText(requireContext(), success ? R.string.reopen_period_succeeded : R.string.reopen_period_failed, Toast.LENGTH_SHORT).show();
                        if (success) loadPeriodAndSnapshots();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void promptChangeDestination() {
        EditText input = new EditText(requireContext());
        input.setHint(R.string.change_reassignment_target_hint);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, 0);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.change_reassignment_target)
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String name = input.getText() == null ? "" : input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    loadingDialog.show();
                    database.changePeriodReassignmentTarget(periodId, name, (success, error) -> {
                        if (getView() == null) return;
                        loadingDialog.dismiss();
                        Toast.makeText(requireContext(), success ? R.string.change_reassignment_target_succeeded : R.string.change_reassignment_target_failed, Toast.LENGTH_SHORT).show();
                        if (success) loadPeriodAndSnapshots();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
