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

package org.dedira.qrnotas.util.adapters;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Period;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Feeds {@link org.dedira.qrnotas.activities.PeriodList}: one row per past "Fechar período" /
 * "Fechar período e turma" close for a discipline, newest first. Tapping a row opens its detail
 * (the per-student snapshot); the reopen icon is only shown while the close hasn't already been
 * undone.
 */
public class PeriodAdapter extends RecyclerView.Adapter<PeriodAdapter.ViewHolder> {

    public interface Listener {
        void onOpen(Period period);

        void onReopen(Period period);
    }

    private final Context context;
    private final Listener listener;
    private final List<Period> periods = new ArrayList<>();

    public PeriodAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void submitList(List<Period> newPeriods) {
        periods.clear();
        periods.addAll(newPeriods);
        notifyDataSetChanged();
    }

    public int getPeriodCount() {
        return periods.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_period, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Period period = periods.get(position);

        String title = period.label != null && !period.label.trim().isEmpty()
                ? period.label
                : DateFormat.format("dd/MM/yyyy HH:mm", new Date(period.closedAt)).toString();
        if (period.isReopened()) title = context.getString(R.string.period_title_reopened, title);
        holder.txtTitle.setText(title);

        String detail;
        if (!period.isClassClose()) {
            detail = context.getString(R.string.period_scope_whole_discipline);
        } else if (period.isReassignment()) {
            detail = context.getString(R.string.period_scope_class_reassigned,
                    period.closedClassGroupName, period.reassignedToClassGroupName);
        } else {
            detail = context.getString(R.string.period_scope_class_archived, period.closedClassGroupName);
        }
        holder.txtDetail.setText(detail);

        holder.itemView.setOnClickListener(v -> listener.onOpen(period));
        holder.btnReopen.setVisibility(period.isReopened() ? View.GONE : View.VISIBLE);
        holder.btnReopen.setOnClickListener(v -> listener.onReopen(period));
    }

    @Override
    public int getItemCount() {
        return periods.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtTitle;
        final TextView txtDetail;
        final ImageButton btnReopen;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            txtTitle = itemView.findViewById(R.id.txtPeriodTitle);
            txtDetail = itemView.findViewById(R.id.txtPeriodDetail);
            btnReopen = itemView.findViewById(R.id.btnReopenPeriod);
        }
    }
}
