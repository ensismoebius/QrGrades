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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.PeriodSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only list for {@link org.dedira.qrnotas.activities.PeriodDetail}: one row per student
 * showing their final point total at the moment this period was closed. Purely informational —
 * no editing/selection, same spirit as {@link LeaderboardAdapter}.
 */
public class PeriodSnapshotAdapter extends RecyclerView.Adapter<PeriodSnapshotAdapter.ViewHolder> {

    private final Context context;
    private final List<PeriodSnapshot> rows = new ArrayList<>();

    public PeriodSnapshotAdapter(Context context) {
        this.context = context;
    }

    public void submitList(List<PeriodSnapshot> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    public int getRowCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_period_snapshot, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PeriodSnapshot row = rows.get(position);
        holder.txtName.setText(row.studentName);
        holder.txtPoints.setText(String.valueOf(row.points));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtName;
        final TextView txtPoints;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtSnapshotStudentName);
            txtPoints = itemView.findViewById(R.id.txtSnapshotPoints);
        }
    }
}
