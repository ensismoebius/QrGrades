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

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared accent-insensitive search normalization, so "andre" matches "André" everywhere. Without
 * this, a teacher searching for a student named "André" would have to type the accent exactly,
 * which is a poor search experience. Every screen that filters a student/discipline/class-group
 * list by typed text should normalize both the query and the candidate text with {@link #normalize}
 * before comparing them.
 */
public final class TextSearch {

    // Matches one or more consecutive "combining marks" (Unicode category Mn = "Mark, nonspacing")
    // — these are the accent marks (like the acute accent on "é") once a character has been
    // decomposed into its base letter + accent pieces below.
    private static final Pattern DIACRITICS = Pattern.compile("\\p{Mn}+");

    // Static-only helper class; never meant to be instantiated.
    private TextSearch() {
    }

    /** Lower-cases {@code s} and strips accents/diacritics, so accented and plain text compare equal. */
    public static String normalize(String s) {
        if (s == null) return "";
        // NFD (Normalization Form Canonical Decomposition) splits accented characters into a base
        // character plus separate combining accent mark(s), e.g. "é" becomes "e" + a combining
        // acute accent. That lets the regex below strip just the accent marks, leaving plain "e".
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        return DIACRITICS.matcher(decomposed).replaceAll("").toLowerCase(Locale.getDefault());
    }
}
