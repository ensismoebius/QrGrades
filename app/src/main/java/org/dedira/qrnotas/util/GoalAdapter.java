package org.dedira.qrnotas.util;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Goal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class GoalAdapter extends RecyclerView.Adapter<GoalAdapter.ViewHolder> {

    private final Context context;
    private final Database database;
    private final String disciplineId;
    private final List<Goal> goals = new ArrayList<>();

    public GoalAdapter(Context context, Database database, String disciplineId) {
        this.context = context;
        this.database = database;
        this.disciplineId = disciplineId;
    }

    public void submitList(List<Goal> newList) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new GoalDiffCallback(goals, newList));
        goals.clear();
        goals.addAll(newList);
        result.dispatchUpdatesTo(this);
    }

    public void showAddDialog() {
        showEditDialog(null);
    }

    private void showEditDialog(Goal existing) {
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);

        EditText nameInput = new EditText(context);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameInput.setHint(R.string.goal_name_hint);
        if (existing != null) nameInput.setText(existing.name);
        layout.addView(nameInput);

        EditText targetInput = new EditText(context);
        targetInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        targetInput.setHint(R.string.goal_target_hint);
        if (existing != null) targetInput.setText(String.valueOf(existing.targetPoints));
        layout.addView(targetInput);

        new AlertDialog.Builder(context)
                .setTitle(existing == null ? R.string.add_goal : R.string.edit_goal)
                .setView(layout)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String targetText = targetInput.getText().toString().trim();
                    if (name.isEmpty() || targetText.isEmpty()) return;

                    int target;
                    try {
                        target = Integer.parseInt(targetText);
                    } catch (NumberFormatException e) {
                        return;
                    }

                    Goal g = existing != null ? existing : new Goal();
                    g.name = name;
                    g.targetPoints = target;
                    g.disciplineId = disciplineId;
                    database.saveGoal(g, (success, saved) -> {
                        if (!success) {
                            Toast.makeText(context, R.string.save_failed, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        List<Goal> updated = new ArrayList<>(goals);
                        if (existing == null) updated.add(saved);
                        Collections.sort(updated, (a, b) -> a.targetPoints - b.targetPoints);
                        submitList(updated);
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void requestDelete(int position) {
        Goal g = goals.get(position);
        new AlertDialog.Builder(context)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(context.getString(R.string.confirm_delete_message, g.name))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setOnCancelListener(dialog -> notifyItemChanged(position))
                .setPositiveButton(R.string.delete_confirm_action, (dialog, which) -> database.deleteGoal(g.id, (success, object) -> {
                    if (success) {
                        List<Goal> updated = new ArrayList<>(goals);
                        updated.remove(g);
                        submitList(updated);
                        Toast.makeText(context, R.string.entity_deleted, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show();
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
        Goal g = goals.get(position);
        holder.txtName.setText(g.name);
        holder.subtitle.setVisibility(View.VISIBLE);
        holder.subtitle.setText(context.getString(R.string.goal_target_points, g.targetPoints));
        holder.itemView.setOnClickListener(null);
        holder.itemView.setClickable(false);

        holder.btnEdit.setOnClickListener(v -> showEditDialog(g));
        holder.btnDelete.setOnClickListener(v -> requestDelete(holder.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return goals.size();
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

    private static class GoalDiffCallback extends DiffUtil.Callback {
        private final List<Goal> oldList;
        private final List<Goal> newList;

        GoalDiffCallback(List<Goal> oldList, List<Goal> newList) {
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
            Goal a = oldList.get(oldItemPosition);
            Goal b = newList.get(newItemPosition);
            return Objects.equals(a.name, b.name) && Objects.equals(a.targetPoints, b.targetPoints);
        }
    }
}
