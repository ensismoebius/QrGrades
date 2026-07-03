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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link TextSearch#normalize(String)}, the helper used to make student-name
 * search accent-insensitive (so typing "andre" finds "André"). It strips diacritics (accent
 * marks) and lowercases the result. These tests check accent stripping, case folding, multiple
 * accented words at once, and the null/empty edge cases.
 */
public class TextSearchTest {

    // Verifies that a single accented character is stripped, turning "André" into "andre".
    @Test
    public void normalize_stripsAccents() {
        assertEquals("andre", TextSearch.normalize("André"));
    }

    // Verifies that uppercase input is converted to lowercase.
    @Test
    public void normalize_lowercases() {
        assertEquals("agatha", TextSearch.normalize("AGATHA"));
    }

    // Verifies that accents are stripped from every accented word in a multi-word name, not
    // just the first one, e.g. "José da Silva Conceição" -> "jose da silva conceicao".
    @Test
    public void normalize_handlesMultipleAccentedWords() {
        assertEquals("jose da silva conceicao", TextSearch.normalize("José da Silva Conceição"));
    }

    // Verifies that passing null does not throw (e.g. NullPointerException) but instead
    // returns an empty string, so callers can normalize without null-checking first.
    @Test
    public void normalize_nullReturnsEmptyString() {
        assertEquals("", TextSearch.normalize(null));
    }

    // Verifies that an already-empty string stays empty after normalization.
    @Test
    public void normalize_emptyStringStaysEmpty() {
        assertEquals("", TextSearch.normalize(""));
    }

    // Verifies that plain ASCII text with no accents is left unchanged apart from lowercasing.
    @Test
    public void normalize_plainAsciiUnchangedApartFromCase() {
        assertEquals("hello world", TextSearch.normalize("Hello World"));
    }
}
