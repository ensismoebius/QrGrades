package org.dedira.qrnotas.util;

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

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

    public void submitList(File[] files) {
        List<File> newList = new ArrayList<>(Arrays.asList(files));
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new SnapshotDiffCallback(snapshots, newList));
        snapshots.clear();
        snapshots.addAll(newList);
        result.dispatchUpdatesTo(this);
    }

    public int getSnapshotCount() {
        return snapshots.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_backup, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = snapshots.get(position);
        boolean manual = DbBackup.isManual(file);

        holder.txtTimestamp.setText(dateFormat.format(new Date(file.lastModified())));
        holder.txtDetail.setText(context.getString(
                manual ? R.string.backup_manual_detail : R.string.backup_auto_detail,
                Formatter.formatShortFileSize(context, file.length())));
        holder.btnRestore.setOnClickListener(v -> listener.onRestore(file));
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(file));
    }

    @Override
    public int getItemCount() {
        return snapshots.size();
    }

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

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return oldList.get(oldItemPosition).getAbsolutePath().equals(newList.get(newItemPosition).getAbsolutePath());
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            File oldFile = oldList.get(oldItemPosition);
            File newFile = newList.get(newItemPosition);
            return oldFile.lastModified() == newFile.lastModified() && oldFile.length() == newFile.length();
        }
    }
}
