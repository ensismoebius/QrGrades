package org.dedira.qrnotas.util;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.activities.DisciplineDetail;
import org.dedira.qrnotas.model.Discipline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DisciplineAdapter extends RecyclerView.Adapter<DisciplineAdapter.ViewHolder> {

    private final Context context;
    private final Database database;
    private final List<Discipline> disciplines = new ArrayList<>();

    public DisciplineAdapter(Context context, Database database) {
        this.context = context;
        this.database = database;
    }

    public void submitList(List<Discipline> newList) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DisciplineDiffCallback(disciplines, newList));
        disciplines.clear();
        disciplines.addAll(newList);
        result.dispatchUpdatesTo(this);
    }

    public void showAddDialog() {
        showEditDialog(null);
    }

    private void showEditDialog(Discipline existing) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        if (existing != null) input.setText(existing.name);

        new AlertDialog.Builder(context)
                .setTitle(existing == null ? R.string.add_discipline : R.string.edit_discipline)
                .setView(wrapInputPadding(input))
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;

                    Discipline d = existing != null ? existing : new Discipline();
                    d.name = name;
                    database.saveDiscipline(d, (success, saved) -> {
                        if (!success) {
                            Toast.makeText(context, R.string.save_failed, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (existing == null) {
                            List<Discipline> updated = new ArrayList<>(disciplines);
                            updated.add(saved);
                            submitList(updated);
                        } else {
                            notifyDataSetChanged();
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private View wrapInputPadding(EditText input) {
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);
        return input;
    }

    private void requestDelete(int position) {
        Discipline d = disciplines.get(position);
        new AlertDialog.Builder(context)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(context.getString(R.string.confirm_delete_message, d.name))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setOnCancelListener(dialog -> notifyItemChanged(position))
                .setPositiveButton(R.string.delete_confirm_action, (dialog, which) -> database.deleteDiscipline(d.id, (success, reason) -> {
                    if (success) {
                        List<Discipline> updated = new ArrayList<>(disciplines);
                        updated.remove(d);
                        submitList(updated);
                        Toast.makeText(context, R.string.entity_deleted, Toast.LENGTH_SHORT).show();
                    } else {
                        int messageRes = "HAS_GROUPS".equals(reason) ? R.string.discipline_has_groups : R.string.delete_failed;
                        Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show();
                        notifyItemChanged(position);
                    }
                }))
                .setNegativeButton(R.string.cancel, (dialog, which) -> notifyItemChanged(position))
                .show();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_named_entity, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Discipline d = disciplines.get(position);
        holder.txtName.setText(d.name);
        holder.subtitle.setVisibility(View.GONE);

        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, DisciplineDetail.class);
            intent.putExtra("disciplineId", d.id);
            intent.putExtra("disciplineName", d.name);
            context.startActivity(intent);
        });

        holder.btnEdit.setOnClickListener(v -> showEditDialog(d));
        holder.btnDelete.setOnClickListener(v -> requestDelete(holder.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return disciplines.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtName;
        final TextView subtitle;
        final ImageButton btnEdit;
        final ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtEntityName);
            subtitle = itemView.findViewById(R.id.txtEntitySubtitle);
            btnEdit = itemView.findViewById(R.id.btnEditEntity);
            btnDelete = itemView.findViewById(R.id.btnDeleteEntity);
        }
    }

    private static class DisciplineDiffCallback extends DiffUtil.Callback {
        private final List<Discipline> oldList;
        private final List<Discipline> newList;

        DisciplineDiffCallback(List<Discipline> oldList, List<Discipline> newList) {
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
            return oldList.get(oldItemPosition).id.equals(newList.get(newItemPosition).id);
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            return Objects.equals(oldList.get(oldItemPosition).name, newList.get(newItemPosition).name);
        }
    }
}
