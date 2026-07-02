package org.dedira.qrnotas.util;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Suggests previously-typed note text as the teacher types, ranked by fuzzy subsequence match
 * (same idea as an editor's "go to file" search) rather than a plain substring filter, so a note
 * like "helped classmate" still surfaces when they type "hlpcls".
 */
public class FuzzyNoteAdapter extends ArrayAdapter<String> {
    private static final int MAX_SUGGESTIONS = 20;

    private final List<String> source;

    public FuzzyNoteAdapter(Context context, List<String> source) {
        super(context, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(source));
        this.source = source;
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
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

            @Override
            @SuppressWarnings("unchecked")
            protected void publishResults(CharSequence constraint, FilterResults results) {
                clear();
                if (results != null && results.values != null) {
                    addAll((List<String>) results.values);
                }
                notifyDataSetChanged();
            }

            @Override
            public CharSequence convertResultToString(Object resultValue) {
                return (String) resultValue;
            }
        };
    }

    private List<String> fuzzyMatch(String query) {
        String lowerQuery = query.toLowerCase(Locale.getDefault());
        List<ScoredNote> scored = new ArrayList<>();
        for (String note : source) {
            int score = fuzzyScore(lowerQuery, note.toLowerCase(Locale.getDefault()));
            if (score != Integer.MIN_VALUE) scored.add(new ScoredNote(note, score));
        }
        Collections.sort(scored, (a, b) -> Integer.compare(b.score, a.score));

        List<String> results = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_SUGGESTIONS, scored.size()); i++) results.add(scored.get(i).note);
        return results;
    }

    /** Subsequence match: every query char must appear in order in candidate. Higher score = better match. */
    private static int fuzzyScore(String query, String candidate) {
        int qi = 0;
        int score = 0;
        int consecutiveBonus = 0;
        for (int ci = 0; ci < candidate.length() && qi < query.length(); ci++) {
            if (candidate.charAt(ci) == query.charAt(qi)) {
                score += 10 + consecutiveBonus;
                consecutiveBonus += 5;
                if (ci == 0 || !Character.isLetterOrDigit(candidate.charAt(ci - 1))) score += 8;
                qi++;
            } else {
                consecutiveBonus = 0;
            }
        }
        if (qi < query.length()) return Integer.MIN_VALUE;
        return score - candidate.length();
    }

    private static final class ScoredNote {
        final String note;
        final int score;

        ScoredNote(String note, int score) {
            this.note = note;
            this.score = score;
        }
    }
}
