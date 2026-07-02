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
import org.dedira.qrnotas.activities.ListStudents;
import org.dedira.qrnotas.model.ClassGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ClassGroupAdapter extends RecyclerView.Adapter<ClassGroupAdapter.ViewHolder> {

    private final Context context;
    private final Database database;
    private final String disciplineId;
    private final List<ClassGroup> groups = new ArrayList<>();

    public ClassGroupAdapter(Context context, Database database, String disciplineId) {
        this.context = context;
        this.database = database;
        this.disciplineId = disciplineId;
    }

    public void submitList(List<ClassGroup> newList) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new GroupDiffCallback(groups, newList));
        groups.clear();
        groups.addAll(newList);
        result.dispatchUpdatesTo(this);
    }

    public void showAddDialog() {
        showEditDialog(null);
    }

    private void showEditDialog(ClassGroup existing) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        if (existing != null) input.setText(existing.name);
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);

        new AlertDialog.Builder(context)
                .setTitle(existing == null ? R.string.add_group : R.string.edit_group)
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;

                    ClassGroup g = existing != null ? existing : new ClassGroup();
                    g.name = name;
                    g.disciplineId = disciplineId;
                    database.saveClassGroup(g, (success, saved) -> {
                        if (!success) {
                            Toast.makeText(context, R.string.save_failed, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (existing == null) {
                            List<ClassGroup> updated = new ArrayList<>(groups);
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

    private void requestDelete(int position) {
        ClassGroup g = groups.get(position);
        new AlertDialog.Builder(context)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(context.getString(R.string.confirm_delete_message, g.name))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setOnCancelListener(dialog -> notifyItemChanged(position))
                .setPositiveButton(R.string.delete_confirm_action, (dialog, which) -> database.deleteClassGroup(g.id, (success, reason) -> {
                    if (success) {
                        List<ClassGroup> updated = new ArrayList<>(groups);
                        updated.remove(g);
                        submitList(updated);
                        Toast.makeText(context, R.string.entity_deleted, Toast.LENGTH_SHORT).show();
                    } else {
                        int messageRes = "HAS_STUDENTS".equals(reason) ? R.string.group_has_students : R.string.delete_failed;
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
        ClassGroup g = groups.get(position);
        holder.txtName.setText(g.name);
        holder.subtitle.setVisibility(View.GONE);
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, ListStudents.class);
            intent.putExtra("classGroupId", g.id);
            intent.putExtra("groupName", g.name);
            context.startActivity(intent);
        });
        holder.itemView.setClickable(true);

        holder.btnEdit.setOnClickListener(v -> showEditDialog(g));
        holder.btnDelete.setOnClickListener(v -> requestDelete(holder.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return groups.size();
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

    private static class GroupDiffCallback extends DiffUtil.Callback {
        private final List<ClassGroup> oldList;
        private final List<ClassGroup> newList;

        GroupDiffCallback(List<ClassGroup> oldList, List<ClassGroup> newList) {
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
