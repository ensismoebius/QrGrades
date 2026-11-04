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
 * The short name printed under each QR code. It has to match what the browser's print view shows,
 * so a sheet printed from the phone and one printed from the computer carry the same labels.
 * (The PDF itself is not unit-tested: Robolectric's PdfDocument shadow can't be written to a file.)
 */
public class QrSheetTest {

    @Test
    public void label_keepsTheFirstNameAndAbbreviatesTheRest() {
        assertEquals("Maria A. S.", QrSheet.label("Maria Aparecida Santos"));
        assertEquals("AGATHA D. P. F.", QrSheet.label("AGATHA DE PAULA FERNANDES"));
    }

    @Test
    public void label_aSingleNameStaysAsItIs() {
        assertEquals("Ana", QrSheet.label("Ana"));
    }

    @Test
    public void label_upperCasesInitialsButLeavesTheFirstNameAlone() {
        assertEquals("ana C. S.", QrSheet.label("ana clara souza"));
    }

    @Test
    public void label_ignoresExtraWhitespace() {
        assertEquals("Ana C.", QrSheet.label("  Ana    Clara  "));
    }

    @Test
    public void label_emptyOrMissingNamesGiveAnEmptyLabel() {
        assertEquals("", QrSheet.label(null));
        assertEquals("", QrSheet.label(""));
        assertEquals("", QrSheet.label("   "));
    }
}
