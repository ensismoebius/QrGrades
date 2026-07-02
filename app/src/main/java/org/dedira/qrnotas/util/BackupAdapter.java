package org.dedira.qrnotas.util;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.dedira.qrnotas.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
        snapshots.clear();
        for (File f : files) snapshots.add(f);
        notifyDataSetChanged();
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
}
