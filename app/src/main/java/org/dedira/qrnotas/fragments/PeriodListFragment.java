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
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.util.adapters.PeriodAdapter;

/**
 * Lists every "Fechar período" / "Fechar período e turma" close recorded for one discipline,
 * newest first, reached from the "Periods" card in {@link DisciplineDetailFragment}. Mirrors
 * {@link BackupListFragment}'s pattern: a history list plus a FAB that performs the action
 * which produces a new entry in that list.
 */
public class PeriodListFragment extends BaseFragment {

    private PeriodAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;
    private String disciplineId;

    public PeriodListFragment() {
        super(R.layout.activity_entity_list);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        this.disciplineId = args != null ? args.getString("disciplineId") : null;
        String disciplineName = args != null ? args.getString("disciplineName") : null;

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.periods_title, disciplineName));

        this.txtEmpty = view.findViewById(R.id.txtEmpty);
        this.txtEmpty.setText(R.string.no_periods_yet);

        this.adapter = new PeriodAdapter(requireContext(), new PeriodAdapter.Listener() {
            @Override
            public void onOpen(Period period) {
                Bundle periodArgs = new Bundle();
                periodArgs.putString("periodId", period.id);
                Navigation.findNavController(view).navigate(R.id.action_periodList_to_periodDetail, periodArgs);
            }

            @Override
            public void onReopen(Period period) {
                confirmReopen(period);
            }
        });
        this.recyclerView = view.findViewById(R.id.lstEntities);
        this.recyclerView.setAdapter(adapter);

        FloatingActionButton btnClosePeriod = view.findViewById(R.id.btnAddEntity);
        btnClosePeriod.setImageResource(R.drawable.ic_history);
        btnClosePeriod.setContentDescription(getString(R.string.close_period));
        btnClosePeriod.setOnClickListener(v -> promptClosePeriod());

        loadPeriods();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadPeriods();
    }

    private void loadPeriods() {
        database.loadPeriodsForDiscipline(disciplineId, (success, periods) -> {
            if (getView() == null) return;
            adapter.submitList(periods);
            updateEmptyState();
        });
    }

    private void updateEmptyState() {
        boolean isEmpty = adapter.getPeriodCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    /** Asks for an optional label, then closes the period for the whole discipline. */
    private void promptClosePeriod() {
        EditText input = new EditText(requireContext());
        input.setHint(R.string.close_period_label_hint);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, 0);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.close_period)
                .setMessage(R.string.close_period_confirm_message)
                .setView(input)
                .setPositiveButton(R.string.close_period, (dialog, which) -> {
                    String label = input.getText() == null ? "" : input.getText().toString().trim();
                    loadingDialog.show();
                    database.closeDisciplinePeriod(disciplineId, label.isEmpty() ? null : label, (success, period) -> {
                        if (getView() == null) return;
                        loadingDialog.dismiss();
                        Toast.makeText(requireContext(), success ? R.string.close_period_succeeded : R.string.close_period_failed, Toast.LENGTH_SHORT).show();
                        if (success) loadPeriods();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Warns that reopening overwrites any new points already awarded since the close, before actually undoing it. */
    private void confirmReopen(Period period) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.reopen_period_confirm_title)
                .setMessage(R.string.reopen_period_confirm_message)
                .setPositiveButton(R.string.reopen_period, (dialog, which) -> {
                    loadingDialog.show();
                    database.reopenPeriod(period.id, (success, error) -> {
                        if (getView() == null) return;
                        loadingDialog.dismiss();
                        Toast.makeText(requireContext(), success ? R.string.reopen_period_succeeded : R.string.reopen_period_failed, Toast.LENGTH_SHORT).show();
                        if (success) loadPeriods();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
