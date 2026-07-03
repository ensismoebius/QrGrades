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
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.util.DbBackup;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Feeds the backup-management screen, where each row shows one database snapshot file
 * (manual or automatic) with its timestamp, size, and Restore/Delete actions.
 * <p>
 * This class is a {@link RecyclerView.Adapter}, the standard Android building block for
 * showing a scrolling list efficiently. Three methods do the real work:
 * <ul>
 *     <li>{@link #onCreateViewHolder} — inflates (builds) the XML layout for a single empty
 *     row and wraps its views in a {@link ViewHolder}. Called only a handful of times, just
 *     enough to fill the screen plus a little buffer, no matter how many rows exist.</li>
 *     <li>{@link #onBindViewHolder} — takes one of those reused rows and fills it with the
 *     data for a specific list position (e.g. "row 5"). Called every time a row scrolls
 *     into view.</li>
 *     <li>{@link ViewHolder} — caches the row's child views (TextViews, buttons) after the
 *     first {@code findViewById} call, so scrolling doesn't repeatedly search the view tree,
 *     which would be slow.</li>
 * </ul>
 */
public class BackupAdapter extends RecyclerView.Adapter<BackupAdapter.ViewHolder> {

    public interface Listener {
        void onRestore(File snapshot);

        void onDelete(File snapshot);
    }

    private final Context context;
    private final Listener listener;
    private final List<File> snapshots = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

    public BackupAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /**
     * Replaces the current list of backup files with a new one, computing the minimal set of
     * row insertions/removals/moves via {@link DiffUtil} instead of redrawing every row.
     * This keeps the RecyclerView's built-in item animations working and avoids the visible
     * "flash" that a full {@code notifyDataSetChanged()} would cause.
     */
    public void submitList(File[] files) {
        List<File> newList = new ArrayList<>(Arrays.asList(files));
        // DiffUtil compares the old and new lists off the main list-adapter state and produces
        // a precise diff (which rows were added/removed/moved) so only the changed rows repaint.
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new SnapshotDiffCallback(snapshots, newList));
        snapshots.clear();
        snapshots.addAll(newList);
        // Tells the RecyclerView exactly which positions changed, triggering targeted
        // insert/remove/change animations instead of redrawing the whole list.
        result.dispatchUpdatesTo(this);
    }

    /** Returns how many backup snapshots are currently shown, e.g. for an empty-state check. */
    public int getSnapshotCount() {
        return snapshots.size();
    }

    /** Inflates (builds from XML) one empty row layout and wraps it in a reusable {@link ViewHolder}. */
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_backup, parent, false);
        return new ViewHolder(view);
    }

    /** Fills a recycled row with the backup file's data for the given list position. */
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = snapshots.get(position);
        boolean manual = DbBackup.isManual(file);

        holder.txtTimestamp.setText(dateFormat.format(new Date(file.lastModified())));
        holder.txtDetail.setText(context.getString(
                manual ? R.string.backup_manual_detail : R.string.backup_auto_detail,
                Formatter.formatShortFileSize(context, file.length())));
        // Each button click just forwards the tapped file to the screen/activity that owns
        // this adapter, which is expected to perform the actual restore/delete operation.
        holder.btnRestore.setOnClickListener(v -> listener.onRestore(file));
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(file));
    }

    /** Tells the RecyclerView how many rows to show; called whenever it needs to know the list size. */
    @Override
    public int getItemCount() {
        return snapshots.size();
    }

    // Holds references to one row's child views, found via findViewById() only once (here in
    // the constructor) instead of on every bind/scroll. This is the standard "view holder"
    // pattern that makes RecyclerView scrolling fast.
    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtTimestamp;
        final TextView txtDetail;
        final ImageButton btnRestore;
        final ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            txtTimestamp = itemView.findViewById(R.id.txtBackupTimestamp);
            txtDetail = itemView.findViewById(R.id.txtBackupDetail);
            btnRestore = itemView.findViewById(R.id.btnRestoreBackup);
            btnDelete = itemView.findViewById(R.id.btnDeleteBackup);
        }
    }

    // Tells DiffUtil how to compare the old and new snapshot lists so it can figure out the
    // minimal set of row changes (insert/remove/move) instead of redrawing everything.
    private static class SnapshotDiffCallback extends DiffUtil.Callback {
        private final List<File> oldList;
        private final List<File> newList;

        SnapshotDiffCallback(List<File> oldList, List<File> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        // "Are these the same real-world item?" — used to detect moves/inserts/removals.
        // Here two files are considered the same backup if they live at the same path.
        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return oldList.get(oldItemPosition).getAbsolutePath().equals(newList.get(newItemPosition).getAbsolutePath());
        }

        // Only called when areItemsTheSame() is true: "did the displayed content change?"
        // If the file's modified time or size differs, the row needs to be redrawn even
        // though it's still the "same" backup file (e.g. it was overwritten).
        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            File oldFile = oldList.get(oldItemPosition);
            File newFile = newList.get(newItemPosition);
            return oldFile.lastModified() == newFile.lastModified() && oldFile.length() == newFile.length();
        }
    }
}
