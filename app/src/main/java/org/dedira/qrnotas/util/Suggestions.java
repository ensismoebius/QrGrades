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

package org.dedira.qrnotas.util;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.util.adapters.FuzzyNoteAdapter;

import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fills the "note" and "category" fields of the point forms with what the teacher typed before,
 * so a repeated reason is two taps away. Shared by the single-award dialog and the bulk-award screen.
 */
public final class Suggestions {

    // Maximum number of previously-used notes to fetch for the autocomplete suggestions.
    private static final int NOTE_HISTORY_LIMIT = 200;
    // Same idea for categories: far fewer distinct values are ever in use.
    private static final int TAG_HISTORY_LIMIT = 30;

    private Suggestions() {
    }

    /** Offers recently used notes (fuzzy-matched as the teacher types), even before anything is typed. */
    public static void attachNotes(Context context, Database database, AutoCompleteTextView field) {
        // A threshold of 0 means the dropdown of suggestions can appear even
        // before the user types anything (normally it requires N characters).
        field.setThreshold(0);
        // Show the suggestion dropdown as soon as the field gains focus, not just when typing.
        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showSoon(field);
        });
        // Also show suggestions if the user taps the field while it's already focused.
        field.setOnClickListener(v -> showSoon(field));
        // The (success, notes) callback runs after the query completes, posted back to the main
        // thread by Database, so it is safe to touch views here.
        database.loadRecentNotes(NOTE_HISTORY_LIMIT, (success, notes) -> {
            field.setAdapter(new FuzzyNoteAdapter(context, notes != null ? notes : new ArrayList<>()));
            if (field.hasFocus()) showSoon(field);
        });
    }

    /** Offers the categories used before, then the built-in defaults. */
    public static void attachTags(Context context, Database database, AutoCompleteTextView field) {
        field.setThreshold(0);
        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showSoon(field);
        });
        field.setOnClickListener(v -> showSoon(field));
        database.loadRecentTags(TAG_HISTORY_LIMIT, (success, tags) -> {
            // Recent ones first; a default that differs only by case from one already listed is skipped.
            ArrayList<String> options = new ArrayList<>();
            Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            if (tags != null) {
                for (String tag : tags) if (seen.add(tag)) options.add(tag);
            }
            for (String tag : context.getResources().getStringArray(R.array.default_tags)) {
                if (seen.add(tag)) options.add(tag);
            }
            field.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_dropdown_item_1line, options));
            if (field.hasFocus()) showSoon(field);
        });
    }

    /**
     * Opens the list once the window and the keyboard have settled: asking right as a dialog
     * gains focus is too early (no window token yet, or the keyboard animation closes it again).
     */
    private static void showSoon(AutoCompleteTextView field) {
        field.postDelayed(() -> {
            if (field.isAttachedToWindow() && field.hasFocus() && field.getAdapter() != null
                    && field.getAdapter().getCount() > 0 && !field.isPopupShowing()) {
                field.showDropDown();
            }
        }, 250);
    }
}
