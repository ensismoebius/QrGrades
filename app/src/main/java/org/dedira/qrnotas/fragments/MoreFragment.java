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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.StudentPickerDialog;
import org.dedira.qrnotas.util.ThemeColors;

/**
 * Bottom-nav "More" tab: a scrolling hub of cards (the same card pattern as
 * {@link DisciplineDetailFragment}) for everything that doesn't have a bottom-nav slot of its
 * own — the roll call, bulk point awards, the alerts and the weekly summary, a student's full
 * history, the overview charts, the period comparison, the printable QR sheet, and the
 * setup/maintenance tools (backups and the local web server). Each of the first group is the
 * phone's own version of a tab of the browser page, so the teacher needs no computer for any of it.
 */
public class MoreFragment extends BaseFragment {

    public MoreFragment() {
        super(R.layout.fragment_more);
    }

    @Override
    protected boolean showsBackArrow() {
        return false;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        LinearLayout list = view.findViewById(R.id.hubList);
        NavController nav = Navigation.findNavController(view);

        addCard(list, R.drawable.ic_fact_check, R.string.hub_attendance, R.string.hub_attendance_subtitle,
                com.google.android.material.R.attr.colorPrimaryContainer, com.google.android.material.R.attr.colorOnPrimaryContainer,
                v -> nav.navigate(R.id.action_more_to_attendance));
        addCard(list, R.drawable.ic_playlist_add_check, R.string.hub_batch_points, R.string.hub_batch_points_subtitle,
                com.google.android.material.R.attr.colorSecondaryContainer, com.google.android.material.R.attr.colorOnSecondaryContainer,
                v -> nav.navigate(R.id.action_more_to_batchPoints));
        addCard(list, R.drawable.ic_notifications, R.string.hub_alerts, R.string.hub_alerts_subtitle,
                com.google.android.material.R.attr.colorTertiaryContainer, com.google.android.material.R.attr.colorOnTertiaryContainer,
                v -> nav.navigate(R.id.action_more_to_alerts));
        addCard(list, R.drawable.ic_history, R.string.hub_student_history, R.string.hub_student_history_subtitle,
                com.google.android.material.R.attr.colorPrimaryContainer, com.google.android.material.R.attr.colorOnPrimaryContainer,
                v -> pickStudentAndOpenHistory(nav));
        addCard(list, R.drawable.ic_bar_chart, R.string.hub_overview, R.string.hub_overview_subtitle,
                com.google.android.material.R.attr.colorSecondaryContainer, com.google.android.material.R.attr.colorOnSecondaryContainer,
                v -> nav.navigate(R.id.action_more_to_overview));
        addCard(list, R.drawable.ic_compare_arrows, R.string.hub_compare_periods, R.string.hub_compare_periods_subtitle,
                com.google.android.material.R.attr.colorTertiaryContainer, com.google.android.material.R.attr.colorOnTertiaryContainer,
                v -> nav.navigate(R.id.action_more_to_periodCompare));
        addCard(list, R.drawable.ic_qr_code, R.string.hub_qr_sheet, R.string.hub_qr_sheet_subtitle,
                com.google.android.material.R.attr.colorPrimaryContainer, com.google.android.material.R.attr.colorOnPrimaryContainer,
                v -> nav.navigate(R.id.action_more_to_qrSheet));
        addCard(list, R.drawable.ic_backup, R.string.manage_backups, R.string.manage_backups_subtitle,
                com.google.android.material.R.attr.colorSecondaryContainer, com.google.android.material.R.attr.colorOnSecondaryContainer,
                v -> nav.navigate(R.id.action_more_to_backupList));
        addCard(list, R.drawable.ic_web_server, R.string.manage_web_server, R.string.manage_web_server_subtitle,
                com.google.android.material.R.attr.colorTertiaryContainer, com.google.android.material.R.attr.colorOnTertiaryContainer,
                v -> nav.navigate(R.id.action_more_to_webServer));
    }

    private void addCard(LinearLayout list, @DrawableRes int icon, @StringRes int title, @StringRes int subtitle,
                         @AttrRes int background, @AttrRes int foreground, View.OnClickListener onClick) {
        View card = LayoutInflater.from(requireContext()).inflate(R.layout.item_hub_card, list, false);
        ImageView image = card.findViewById(R.id.hubIcon);
        image.setImageResource(icon);
        image.setImageTintList(android.content.res.ColorStateList.valueOf(ThemeColors.resolve(requireContext(), foreground)));
        ViewCompat.setBackgroundTintList(card.findViewById(R.id.hubIconBackground),
                android.content.res.ColorStateList.valueOf(ThemeColors.resolve(requireContext(), background)));
        ((TextView) card.findViewById(R.id.hubTitle)).setText(title);
        ((TextView) card.findViewById(R.id.hubSubtitle)).setText(subtitle);
        card.setOnClickListener(onClick);
        list.addView(card);
    }

    /** The history belongs to one student, so the hub asks which one first. */
    private void pickStudentAndOpenHistory(NavController nav) {
        new StudentPickerDialog(requireContext(), database, student -> {
            if (getView() == null) return;
            Bundle args = new Bundle();
            args.putString("studentId", student.id);
            nav.navigate(R.id.action_more_to_studentProgress, args);
        }).show();
    }
}
