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

import java.util.ArrayList;
import java.util.List;

/**
 * Result of matching all rows parsed from an imported roster CSV against the database.
 * After the CSV is parsed, each row is looked up (by student/discipline/class-group name)
 * and either turned into a {@link ResolvedCsvRow} (successfully matched, ready to import)
 * or a {@link CsvRowError} (could not be matched). This plan is shown to the user in a
 * confirmation dialog before anything is actually written to the database.
 */
public class CsvImportPlan {
    // Rows that were successfully matched/prepared and are ready to be imported.
    public final List<ResolvedCsvRow> resolved = new ArrayList<>();
    // Rows that could not be matched or parsed; shown to the user as problems to fix.
    public final List<CsvRowError> errors = new ArrayList<>();

    /** Counts how many resolved rows correspond to a brand-new student (not already in the database). */
    public int newStudentCount() {
        int count = 0;
        for (ResolvedCsvRow row : resolved) if (row.isNewStudent) count++;
        return count;
    }

    /** Counts how many resolved rows correspond to a student that already existed in the database. */
    public int matchedStudentCount() {
        // Everyone who isn't "new" must already have matched an existing student.
        return resolved.size() - newStudentCount();
    }
}
