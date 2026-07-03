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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.BitmapConverter;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only ranked list for {@link org.dedira.qrnotas.activities.LeaderboardList}: one row per
 * student, showing their rank (1-based position in the already points-sorted list handed to
 * {@link #submitList}), photo, name, and current points. No editing/selection — this screen is
 * purely a "who's ahead" view.
 */
public class LeaderboardAdapter extends RecyclerView.Adapter<LeaderboardAdapter.ViewHolder> {

    private final Context context;
    private final List<StudentExportData> rows = new ArrayList<>();

    public LeaderboardAdapter(Context context) {
        this.context = context;
    }

    /** Replaces the shown rows. Callers are expected to have already sorted this list by points, descending — the adapter itself just numbers whatever order it's given. */
    public void submitList(List<StudentExportData> newRows) {
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
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_leaderboard_row, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        StudentExportData row = rows.get(position);
        // Rank is simply the row's position in the pre-sorted list, 1-based for display.
        holder.txtRank.setText(String.valueOf(position + 1));
        holder.txtName.setText(row.studentName);
        holder.txtPoints.setText(String.valueOf(row.points));
        holder.imgPhoto.setImageBitmap(BitmapConverter.loadBitmap(row.photoPath));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtRank;
        final ImageView imgPhoto;
        final TextView txtName;
        final TextView txtPoints;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            txtRank = itemView.findViewById(R.id.txtRank);
            imgPhoto = itemView.findViewById(R.id.imgPhotoLeaderboard);
            txtName = itemView.findViewById(R.id.txtNameLeaderboard);
            txtPoints = itemView.findViewById(R.id.txtPointsLeaderboard);
        }
    }
}
