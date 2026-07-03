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
import android.widget.ArrayAdapter;
import android.widget.Filter;

import org.dedira.qrnotas.util.TextSearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Suggests previously-typed note text as the teacher types, ranked by fuzzy subsequence match
 * (same idea as an editor's "go to file" search) rather than a plain substring filter, so a note
 * like "helped classmate" still surfaces when they type "hlpcls".
 * <p>
 * Unlike the other classes in this package, this is not a {@link androidx.recyclerview.widget.RecyclerView.Adapter}
 * — it's an {@link ArrayAdapter}, the older/simpler Android adapter type used to power an
 * AutoCompleteTextView's dropdown suggestion list. It has its own list-refresh mechanism
 * ({@link #getFilter()}) instead of RecyclerView's bind/scroll model.
 */
public class FuzzyNoteAdapter extends ArrayAdapter<String> {
    // Cap on how many suggestions are ever shown at once, so the dropdown doesn't get
    // overwhelmingly long even if the teacher has hundreds of past notes.
    private static final int MAX_SUGGESTIONS = 20;

    private final List<String> source;

    public FuzzyNoteAdapter(Context context, List<String> source) {
        super(context, android.R.layout.simple_dropdown_item_1line,
                new ArrayList<>(source.subList(0, Math.min(MAX_SUGGESTIONS, source.size()))));
        this.source = source;
    }

    /**
     * Returns the {@link Filter} that Android's AutoCompleteTextView calls every time the
     * user types a character, to decide what to show in the dropdown. This is the standard
     * extension point for {@link ArrayAdapter} filtering — overriding it lets us swap in the
     * fuzzy-match ranking below instead of the default plain "starts with" filter.
     */
    @Override
    public Filter getFilter() {
        return new Filter() {
            // Runs off the main thread: computes the new suggestion list for the given typed
            // text, but must NOT touch the adapter's data directly (that happens below in
            // publishResults, which runs back on the main/UI thread).
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                List<String> results = (constraint == null || constraint.length() == 0)
                        ? source.subList(0, Math.min(MAX_SUGGESTIONS, source.size()))
                        : fuzzyMatch(constraint.toString());

                FilterResults filterResults = new FilterResults();
                filterResults.values = results;
                filterResults.count = results.size();
                return filterResults;
            }

            // Runs back on the main/UI thread once performFiltering() finishes: swaps the
            // adapter's visible list for the filtered results and asks the dropdown to redraw.
            @Override
            @SuppressWarnings("unchecked")
            protected void publishResults(CharSequence constraint, FilterResults results) {
                clear();
                if (results != null && results.values != null) {
                    addAll((List<String>) results.values);
                }
                // The whole suggestion list is being replaced (not incrementally patched), so
                // a full refresh is the right call here — this is ArrayAdapter's own dataset
                // notification, not RecyclerView's, so there is no DiffUtil equivalent to use.
                notifyDataSetChanged();
            }

            // Tells the AutoCompleteTextView what text to fill in when the user taps a
            // suggestion — here each suggestion already is the plain String to insert.
            @Override
            public CharSequence convertResultToString(Object resultValue) {
                return (String) resultValue;
            }
        };
    }

    /**
     * Scores every stored note against the typed query using fuzzy subsequence matching,
     * keeps only the notes that actually match (in order), sorts best-match-first, and
     * returns at most {@link #MAX_SUGGESTIONS} of them.
     */
    private List<String> fuzzyMatch(String query) {
        String lowerQuery = TextSearch.normalize(query);
        List<ScoredNote> scored = new ArrayList<>();
        for (String note : source) {
            int score = fuzzyScore(lowerQuery, TextSearch.normalize(note));
            // Integer.MIN_VALUE is the "no match at all" sentinel returned by fuzzyScore();
            // such notes are excluded entirely rather than ranked last.
            if (score != Integer.MIN_VALUE) scored.add(new ScoredNote(note, score));
        }
        // Highest score first, so the best-matching notes appear at the top of the dropdown.
        Collections.sort(scored, (a, b) -> Integer.compare(b.score, a.score));

        List<String> results = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_SUGGESTIONS, scored.size()); i++) results.add(scored.get(i).note);
        return results;
    }

    /**
     * Subsequence match: every query char must appear in order in candidate. Higher score = better match.
     * The candidate does not need the query as a contiguous substring — e.g. query "hlp" matches
     * candidate "helped" because h, l, p appear in that order (with other letters in between).
     */
    private static int fuzzyScore(String query, String candidate) {
        int qi = 0; // index into the query — how many query chars have been matched so far
        int score = 0;
        // Rewards runs of consecutive matching characters more than scattered ones, since a
        // contiguous match (e.g. typing "help" and matching "help" in "helped") feels more
        // relevant than characters matched far apart.
        int consecutiveBonus = 0;
        for (int ci = 0; ci < candidate.length() && qi < query.length(); ci++) {
            if (candidate.charAt(ci) == query.charAt(qi)) {
                score += 10 + consecutiveBonus;
                consecutiveBonus += 5;
                // Extra points if this match starts a new "word" (is the first character, or
                // follows a non-letter/digit like a space) — matching the start of a word is a
                // stronger signal of relevance than matching mid-word.
                if (ci == 0 || !Character.isLetterOrDigit(candidate.charAt(ci - 1))) score += 8;
                qi++;
            } else {
                // A non-matching character breaks the streak, so the next match won't get the
                // "consecutive" bonus until a fresh run of matches starts.
                consecutiveBonus = 0;
            }
        }
        // If not every query character was found in order, this candidate doesn't qualify as
        // a fuzzy match at all — signal that with the sentinel value used by fuzzyMatch() above.
        if (qi < query.length()) return Integer.MIN_VALUE;
        // Subtracting the candidate's length slightly favors shorter, more precise matches
        // over longer notes that happen to also contain the same subsequence.
        return score - candidate.length();
    }

    // Small holder pairing a note's text with its computed fuzzy-match score, used only
    // while sorting candidates by relevance in fuzzyMatch().
    private static final class ScoredNote {
        final String note;
        final int score;

        ScoredNote(String note, int score) {
            this.note = note;
            this.score = score;
        }
    }
}
