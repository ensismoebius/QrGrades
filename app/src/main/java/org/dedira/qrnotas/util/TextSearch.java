package org.dedira.qrnotas.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared accent-insensitive search normalization, so "andre" matches "André" everywhere. */
public final class TextSearch {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{Mn}+");

    private TextSearch() {
    }

    public static String normalize(String s) {
        if (s == null) return "";
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        return DIACRITICS.matcher(decomposed).replaceAll("").toLowerCase(Locale.getDefault());
    }
}
