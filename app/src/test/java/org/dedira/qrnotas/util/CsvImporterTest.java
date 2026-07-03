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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.dedira.qrnotas.model.CsvStudentRow;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Unit tests for {@link CsvImporter#parse(InputStream)}, the CSV parser used when a teacher
 * imports a student roster from a CSV file. It turns raw CSV text into a list of
 * {@link CsvStudentRow} objects, tolerating real-world messiness: flexible header column order
 * and naming, blank lines, quoted fields (including embedded commas/quotes), and short rows.
 * No Robolectric/Android dependency is needed here — the parser works on plain streams/strings.
 */
public class CsvImporterTest {

    // Small helper to turn a CSV string literal into the InputStream that CsvImporter.parse
    // expects, so each test can just write CSV as a plain Java string.
    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    // Verifies that a well-formed CSV with a header row and standard column names parses into
    // one CsvStudentRow per data row, with fields mapped correctly and 1-based line numbers
    // (line 1 is the header, so the first data row is line 2, the second is line 3, etc).
    @Test
    public void parse_readsBasicRows() throws Exception {
        String csv = "name,discipline,classGroup\nAndré,BDI,A\nBeatriz,Mobile II,B\n";
        List<CsvStudentRow> rows = CsvImporter.parse(stream(csv));

        assertEquals(2, rows.size());
        assertEquals("André", rows.get(0).name);
        assertEquals("BDI", rows.get(0).disciplineName);
        assertEquals("A", rows.get(0).classGroupName);
        assertEquals(2, rows.get(0).lineNumber);
        assertEquals(3, rows.get(1).lineNumber);
    }

    // Verifies that the parser locates columns by header name, not by fixed position, so a
    // CSV whose columns are reordered (classGroup, name, discipline) still parses correctly.
    @Test
    public void parse_headerColumnsCanBeInAnyOrder() throws Exception {
        String csv = "classGroup,name,discipline\nB,Caio,Mobile II\n";
        List<CsvStudentRow> rows = CsvImporter.parse(stream(csv));

        assertEquals(1, rows.size());
        assertEquals("Caio", rows.get(0).name);
        assertEquals("Mobile II", rows.get(0).disciplineName);
        assertEquals("B", rows.get(0).classGroupName);
    }

    // Verifies that header matching ignores letter case and extra spaces around column names
    // (e.g. "Name", " Class Group ", "DISCIPLINE" all still match their expected fields).
    @Test
    public void parse_headerColumnNamesAreCaseAndSpaceInsensitive() throws Exception {
        String csv = "Name, Class Group , DISCIPLINE\nCaio,B,Mobile II\n";
        List<CsvStudentRow> rows = CsvImporter.parse(stream(csv));

        assertEquals(1, rows.size());
        assertEquals("Caio", rows.get(0).name);
    }

    // Verifies that the header "class" is accepted as an alternate spelling for "classGroup",
    // since users may not know the exact expected column name.
    @Test
    public void parse_acceptsClassAsSynonymForClassGroup() throws Exception {
        String csv = "name,discipline,class\nCaio,Mobile II,B\n";
        List<CsvStudentRow> rows = CsvImporter.parse(stream(csv));

        assertEquals(1, rows.size());
        assertEquals("B", rows.get(0).classGroupName);
    }

    // Verifies that fully blank lines and whitespace-only lines in the middle of the file are
    // skipped rather than being parsed as (invalid) data rows.
    @Test
    public void parse_skipsBlankLines() throws Exception {
        String csv = "name,discipline,classGroup\nAndré,BDI,A\n\n   \nBeatriz,Mobile II,B\n";
        List<CsvStudentRow> rows = CsvImporter.parse(stream(csv));

        assertEquals(2, rows.size());
    }

    // Verifies that a row with an empty "name" field is silently skipped (there's no useful
    // student record without a name), while other valid rows are still parsed.
    @Test
    public void parse_skipsRowsWithEmptyName() throws Exception {
        String csv = "name,discipline,classGroup\n,BDI,A\nBeatriz,Mobile II,B\n";
        List<CsvStudentRow> rows = CsvImporter.parse(stream(csv));

        assertEquals(1, rows.size());
        assertEquals("Beatriz", rows.get(0).name);
    }

    // Verifies proper CSV quoting support: a field wrapped in double quotes can contain a
    // literal comma ("Silva, André") without being split into extra columns.
    @Test
    public void parse_handlesQuotedFieldsWithEmbeddedCommas() throws Exception {
        String csv = "name,discipline,classGroup\n\"Silva, André\",BDI,A\n";
        List<CsvStudentRow> rows = CsvImporter.parse(stream(csv));

        assertEquals(1, rows.size());
        assertEquals("Silva, André", rows.get(0).name);
    }

    // Verifies the CSV escaping rule for quotes-within-quotes: a doubled quote ("") inside a
    // quoted field represents one literal quote character, so "Ann ""Andy"" Smith" becomes
    // Ann "Andy" Smith.
    @Test
    public void parse_handlesEscapedQuotesInsideQuotedField() throws Exception {
        String csv = "name,discipline,classGroup\n\"Ann \"\"Andy\"\" Smith\",BDI,A\n";
        List<CsvStudentRow> rows = CsvImporter.parse(stream(csv));

        assertEquals(1, rows.size());
        assertEquals("Ann \"Andy\" Smith", rows.get(0).name);
    }

    // Verifies that a CSV missing a required column (here "classGroup") fails fast with a
    // CsvFormatException whose message names the missing column, so the user knows what to fix.
    @Test
    public void parse_missingRequiredColumnThrows() {
        String csv = "name,discipline\nAndré,BDI\n";
        CsvImporter.CsvFormatException ex = assertThrows(CsvImporter.CsvFormatException.class,
                () -> CsvImporter.parse(stream(csv)));
        assertTrue(ex.getMessage().contains("classGroup"));
    }

    // Verifies that a completely empty file (no header at all) is rejected with the same
    // CsvFormatException, rather than being treated as zero rows.
    @Test
    public void parse_emptyFileThrows() {
        assertThrows(CsvImporter.CsvFormatException.class, () -> CsvImporter.parse(stream("")));
    }

    // Verifies tolerance for "ragged" rows: a data row with fewer columns than the header
    // (here only "name" is present) still parses, with the missing trailing cells treated as
    // empty strings rather than causing an error.
    @Test
    public void parse_rowShorterThanHeaderTreatsMissingCellsAsEmpty() throws Exception {
        String csv = "name,discipline,classGroup\nAndré\n";
        List<CsvStudentRow> rows = CsvImporter.parse(stream(csv));

        assertEquals(1, rows.size());
        assertEquals("André", rows.get(0).name);
        assertEquals("", rows.get(0).disciplineName);
        assertEquals("", rows.get(0).classGroupName);
    }
}
