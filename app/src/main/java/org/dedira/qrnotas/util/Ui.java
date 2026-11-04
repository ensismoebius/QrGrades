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
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.button.MaterialButton;

import org.dedira.qrnotas.R;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Small builders for the cards, tiles and rows that several of the phone's report screens share
 * (alerts, overview, comparison, history), plus the number and date wording they have in common.
 * Keeping them here means a "student row" or a "statistic tile" looks the same on every screen.
 */
public final class Ui {

    /** How many rows a long list shows before its "show all" button. */
    public static final int COLLAPSED_ROWS = 10;

    private Ui() {
    }

    /* ------------------------------ wording ------------------------------ */

    /** +5 / −3 / 0, with the real minus sign. */
    public static String signed(int n) {
        if (n > 0) return "+" + n;
        if (n < 0) return "−" + (-n);
        return "0";
    }

    /** "1 falta" / "3 faltas": the {@code one} string as it is for exactly one, otherwise {@code many} with the count as %1$d. */
    public static String plural(Context context, int n, @StringRes int one, @StringRes int many) {
        return n == 1 ? context.getString(one) : context.getString(many, n);
    }

    /** 83.333 -> "83,3%" (in the phone's language); whole numbers stay whole: "100%". */
    public static String percent(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        if (rounded == Math.rint(rounded)) return String.format(Locale.getDefault(), "%d%%", (long) rounded);
        return String.format(Locale.getDefault(), "%.1f%%", rounded);
    }

    /** 14.25 -> "14,3"; whole numbers stay whole. */
    public static String decimal(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        if (rounded == Math.rint(rounded)) return String.format(Locale.getDefault(), "%d", (long) rounded);
        return String.format(Locale.getDefault(), "%.1f", rounded);
    }

    /** A moment as a date in the user's format. */
    public static String date(Context context, long millis) {
        return android.text.format.DateFormat.getDateFormat(context).format(new Date(millis));
    }

    /** A moment as date and time in the user's format. */
    public static String dateTime(Context context, long millis) {
        Date date = new Date(millis);
        return android.text.format.DateFormat.getDateFormat(context).format(date) + " "
                + android.text.format.DateFormat.getTimeFormat(context).format(date);
    }

    /** A stored "yyyy-MM-dd" day in the user's format; unreadable input comes back unchanged. */
    public static String day(Context context, String day) {
        return GoalViews.day(context, day);
    }

    /** A stored "yyyy-MM-dd" day with its weekday, e.g. "seg., 03/11/2026". */
    public static String dayWithWeekday(Context context, String day) {
        try {
            Date parsed = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day);
            DateFormat weekday = new SimpleDateFormat("EEE", Locale.getDefault());
            return weekday.format(parsed) + ", " + android.text.format.DateFormat.getDateFormat(context).format(parsed);
        } catch (ParseException e) {
            return day;
        }
    }

    /** Shortens a name for a label: "Maria Aparecida da Silva Santos" -> "Maria A. Santos"-style stays readable at a glance. */
    public static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }

    /* ------------------------------ sections ------------------------------ */

    /** A card made by {@link #section}; fill {@link #body} with rows. */
    public static final class Section {
        public final View root;
        public final TextView title;
        public final TextView badge;
        public final TextView description;
        /** Controls that stay put while {@link #body} is rebuilt. */
        public final LinearLayout header;
        public final LinearLayout body;
        public final MaterialButton more;

        Section(View root) {
            this.root = root;
            this.title = root.findViewById(R.id.sectionTitle);
            this.badge = root.findViewById(R.id.sectionBadge);
            this.description = root.findViewById(R.id.sectionDescription);
            this.header = root.findViewById(R.id.sectionHeader);
            this.body = root.findViewById(R.id.sectionBody);
            this.more = root.findViewById(R.id.sectionMore);
        }

        public Section count(int n) {
            badge.setText(String.valueOf(n));
            badge.setVisibility(View.VISIBLE);
            return this;
        }

        public Section describe(@Nullable CharSequence text) {
            description.setText(text);
            description.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
            return this;
        }

        /** A plain paragraph in the header, among the controls. */
        public TextView headerMessage(CharSequence text) {
            TextView view = paragraph(text);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = root.getResources().getDimensionPixelSize(R.dimen.spacing_sm);
            header.addView(view, lp);
            return view;
        }

        private TextView paragraph(CharSequence text) {
            TextView view = new TextView(root.getContext());
            TextViewCompat.setTextAppearance(view, R.style.TextAppearance_QrGrades_BodyMedium);
            view.setTextColor(ThemeColors.resolve(root.getContext(), com.google.android.material.R.attr.colorOnSurface));
            view.setText(text);
            return view;
        }

        /** A plain paragraph in the body (an empty-state or explanation). */
        public TextView message(CharSequence text) {
            TextView view = paragraph(text);
            body.addView(view);
            return view;
        }
    }

    /** Adds an empty titled card to {@code parent}. */
    public static Section section(Context context, ViewGroup parent, CharSequence title) {
        View card = LayoutInflater.from(context).inflate(R.layout.item_section_card, parent, false);
        Section section = new Section(card);
        section.title.setText(title);
        parent.addView(card);
        return section;
    }

    /** Builds one row for a list item. */
    public interface RowFactory<T> {
        View create(T item, ViewGroup parent);
    }

    /**
     * Fills a section with the first {@link #COLLAPSED_ROWS} rows of {@code items}, or all of them
     * when {@code expanded}, and wires the button that flips between the two through {@code onToggle}.
     */
    public static <T> void fillCollapsible(Section section, List<T> items, boolean expanded,
                                           RowFactory<T> factory, Runnable onToggle) {
        section.body.removeAllViews();
        int shown = expanded ? items.size() : Math.min(items.size(), COLLAPSED_ROWS);
        for (int i = 0; i < shown; i++) section.body.addView(factory.create(items.get(i), section.body));
        if (items.size() > COLLAPSED_ROWS) {
            Context context = section.root.getContext();
            section.more.setText(expanded ? context.getString(R.string.show_less)
                    : context.getString(R.string.show_all_count, items.size()));
            section.more.setOnClickListener(v -> onToggle.run());
            section.more.setVisibility(View.VISIBLE);
        } else {
            section.more.setVisibility(View.GONE);
        }
    }

    /* ------------------------------ rows and tiles ------------------------------ */

    /**
     * One row of a list: a name, up to two lines under it, an optional figure at the end (coloured
     * by theme attribute {@code valueColor}, or the default text colour when 0), and an optional
     * click handler.
     */
    public static View row(Context context, ViewGroup parent, CharSequence title, @Nullable CharSequence subtitle,
                           @Nullable CharSequence detail, @Nullable CharSequence value, @AttrRes int valueColor,
                           @Nullable View.OnClickListener onClick) {
        View row = LayoutInflater.from(context).inflate(R.layout.item_info_row, parent, false);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(title);
        setOptional(row.findViewById(R.id.rowSubtitle), subtitle);
        setOptional(row.findViewById(R.id.rowDetail), detail);
        TextView valueView = row.findViewById(R.id.rowValue);
        setOptional(valueView, value);
        if (valueColor != 0) valueView.setTextColor(ThemeColors.resolve(context, valueColor));
        if (onClick != null) {
            row.setOnClickListener(onClick);
        } else {
            row.setBackground(null);
            row.setClickable(false);
        }
        return row;
    }

    private static void setOptional(TextView view, @Nullable CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(text);
            view.setVisibility(View.VISIBLE);
        }
    }

    /** One statistic: a figure and what it measures. {@code color} is a theme attribute, or 0 for the default text colour. */
    public static final class Tile {
        final CharSequence value;
        final CharSequence label;
        @AttrRes final int color;

        public Tile(CharSequence value, CharSequence label, @AttrRes int color) {
            this.value = value;
            this.label = label;
            this.color = color;
        }
    }

    /** Lays the tiles out two to a row. */
    public static void tiles(Context context, LinearLayout container, List<Tile> tiles) {
        LayoutInflater inflater = LayoutInflater.from(context);
        LinearLayout line = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % 2 == 0) {
                line = new LinearLayout(context);
                line.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = i == 0 ? 0 : context.getResources().getDimensionPixelSize(R.dimen.spacing_sm);
                container.addView(line, lp);
            }
            Tile tile = tiles.get(i);
            View view = inflater.inflate(R.layout.item_stat_tile, line, false);
            TextView value = view.findViewById(R.id.tileValue);
            value.setText(tile.value);
            if (tile.color != 0) value.setTextColor(ThemeColors.resolve(context, tile.color));
            ((TextView) view.findViewById(R.id.tileLabel)).setText(tile.label);
            if (i % 2 == 1) ((LinearLayout.LayoutParams) view.getLayoutParams()).setMarginEnd(0);
            line.addView(view);
        }
        // A last tile on its own keeps half a row, not the full width.
        if (tiles.size() % 2 == 1 && line != null) {
            View filler = new View(context);
            line.addView(filler, new LinearLayout.LayoutParams(0, 0, 1f));
        }
    }

    /* ------------------------------ dropdowns ------------------------------ */

    /** Adds a read-only "pick one" field to {@code parent} and returns its text view. */
    public static AutoCompleteTextView dropdown(Context context, ViewGroup parent, CharSequence hint) {
        com.google.android.material.textfield.TextInputLayout layout = (com.google.android.material.textfield.TextInputLayout)
                LayoutInflater.from(context).inflate(R.layout.item_dropdown, parent, false);
        layout.setHint(hint);
        parent.addView(layout);
        return layout.findViewById(R.id.dropdownField);
    }

    /** Adds a search box to {@code parent} and returns its edit text. */
    public static com.google.android.material.textfield.TextInputEditText searchField(Context context, ViewGroup parent,
                                                                                    CharSequence hint, CharSequence text) {
        com.google.android.material.textfield.TextInputLayout layout = (com.google.android.material.textfield.TextInputLayout)
                LayoutInflater.from(context).inflate(R.layout.item_search_field, parent, false);
        layout.setHint(hint);
        parent.addView(layout);
        com.google.android.material.textfield.TextInputEditText field = layout.findViewById(R.id.searchField);
        field.setText(text);
        return field;
    }

    /** Adds a whole-number field to {@code parent} and returns its edit text. */
    public static com.google.android.material.textfield.TextInputEditText numberField(Context context, ViewGroup parent,
                                                                                    CharSequence hint, int value) {
        com.google.android.material.textfield.TextInputLayout layout = (com.google.android.material.textfield.TextInputLayout)
                LayoutInflater.from(context).inflate(R.layout.item_number_field, parent, false);
        layout.setHint(hint);
        parent.addView(layout);
        com.google.android.material.textfield.TextInputEditText field = layout.findViewById(R.id.numberField);
        field.setText(String.valueOf(value));
        return field;
    }

    /** Puts {@code labels} into an exposed-dropdown field and selects {@code selected} without triggering a filter. */
    public static void setDropdown(AutoCompleteTextView field, List<String> labels, int selected) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(field.getContext(), android.R.layout.simple_list_item_1, labels);
        field.setAdapter(adapter);
        if (selected >= 0 && selected < labels.size()) field.setText(labels.get(selected), false);
        else field.setText("", false);
    }
}
