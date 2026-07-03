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

package org.dedira.qrnotas.model;

/**
 * One parsed row from a roster CSV, before matching against the database. This is the raw,
 * still-textual data straight out of the file — names here are plain strings, not yet
 * resolved to database ids (that resolution produces a {@link ResolvedCsvRow} or a
 * {@link CsvRowError}).
 */
public class CsvStudentRow {
    public final int lineNumber; // 1-based line number in the original CSV file, for error messages.
    public final String name; // Student's full name as written in the CSV.
    public final String disciplineName; // Discipline name as written in the CSV (e.g. "BDI"), to be looked up in the database.
    public final String classGroupName; // Class group ("turma") name as written in the CSV, to be looked up in the database.

    public CsvStudentRow(int lineNumber, String name, String disciplineName, String classGroupName) {
        this.lineNumber = lineNumber;
        this.name = name;
        this.disciplineName = disciplineName;
        this.classGroupName = classGroupName;
    }
}
