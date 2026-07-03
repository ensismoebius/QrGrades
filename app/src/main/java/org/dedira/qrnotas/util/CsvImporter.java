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

import org.dedira.qrnotas.model.CsvStudentRow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses roster CSVs with a header row of name/discipline/class-group columns, in any order. This
 * lets a teacher bulk-import a whole class roster from a spreadsheet export instead of typing each
 * student in one by one. Does not touch the database itself — it only turns raw CSV text into a
 * list of {@link CsvStudentRow} objects for the caller to then insert.
 */
public class CsvImporter {

    /** Thrown when the CSV is missing, empty, or missing one of the required header columns. */
    public static class CsvFormatException extends Exception {
        // Required by Java for any Exception subclass that may be serialized; keeps this class
        // stable across code changes, not something this app actually relies on here.
        private static final long serialVersionUID = 1L;

        public CsvFormatException(String message) {
            super(message);
        }
    }

    // Static-only helper class; never meant to be instantiated.
    private CsvImporter() {
    }

    /**
     * Reads a CSV file/stream and returns one {@link CsvStudentRow} per data row. The header row
     * is required and must contain "name", "discipline" and "classGroup"/"class" columns, but they
     * can appear in any order and are matched case-insensitively, ignoring spaces/underscores
     * (so "Class Group", "class_group" and "classgroup" are all accepted).
     */
    public static List<CsvStudentRow> parse(InputStream in) throws IOException, CsvFormatException {
        List<CsvStudentRow> rows = new ArrayList<>();

        // try-with-resources: the reader (and the underlying stream) is closed automatically once
        // this block exits, even if an exception is thrown while reading.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) throw new CsvFormatException("The file is empty.");

            // Figure out which column index holds each required field by normalizing header
            // labels (lowercase, no spaces/underscores) and matching against known names.
            List<String> header = splitCsvLine(headerLine);
            int nameCol = -1, disciplineCol = -1, classGroupCol = -1;
            for (int i = 0; i < header.size(); i++) {
                String col = header.get(i).trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
                if (col.equals("name")) nameCol = i;
                else if (col.equals("discipline")) disciplineCol = i;
                else if (col.equals("classgroup") || col.equals("class")) classGroupCol = i;
            }

            // -1 means "column not found" (Java arrays/lists are 0-indexed, so -1 can never be a
            // real column position) — if any required column is missing, the file can't be parsed.
            if (nameCol == -1 || disciplineCol == -1 || classGroupCol == -1) {
                throw new CsvFormatException("Header must contain \"name\", \"discipline\" and \"classGroup\" columns.");
            }

            int lineNumber = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) continue; // skip blank lines silently

                List<String> cells = splitCsvLine(line);
                String name = cellAt(cells, nameCol);
                String disciplineName = cellAt(cells, disciplineCol);
                String classGroupName = cellAt(cells, classGroupCol);
                if (name.isEmpty()) continue; // a row without a name is not a usable student row

                rows.add(new CsvStudentRow(lineNumber, name, disciplineName, classGroupName));
            }
        }

        return rows;
    }

    /** Safely reads a cell by index, returning "" if the row has fewer columns than expected (a short/ragged row). */
    private static String cellAt(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index).trim() : "";
    }

    /**
     * Minimal RFC4180 line splitter: handles quoted fields with embedded commas/escaped quotes.
     * A plain {@code line.split(",")} would break on values like {@code "Smith, John"} (a comma
     * inside quotes should not split the field) — this hand-written state machine walks the line
     * character by character to handle that correctly.
     */
    private static List<String> splitCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder(); // the field currently being built
        boolean inQuotes = false; // true while inside a "..." quoted field

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    // Two consecutive quotes inside a quoted field is CSV's escape sequence for a
                    // literal quote character (e.g. "" -> "). Otherwise a single quote closes the field.
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++; // consume the second quote of the pair too
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true; // entering a quoted field, e.g. right after a comma
                } else if (c == ',') {
                    // Comma outside quotes ends the current field and starts a new one.
                    result.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        result.add(current.toString()); // the last field has no trailing comma to trigger a flush
        return result;
    }
}
