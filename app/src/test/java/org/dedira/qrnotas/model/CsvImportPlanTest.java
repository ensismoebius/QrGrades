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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link CsvImportPlan}, the object that summarizes the result of matching a
 * parsed roster CSV against the database before anything is actually imported. A plan holds
 * two lists: {@code resolved} rows (successfully matched, ready to import — each one flagged
 * as either a brand-new student or a student that already existed) and {@code errors} (rows
 * that could not be matched). These tests check that {@link CsvImportPlan#newStudentCount()}
 * and {@link CsvImportPlan#matchedStudentCount()} correctly tally those two categories.
 */
public class CsvImportPlanTest {

    // Verifies that a plan with nothing added to it reports zero new students and zero
    // matched students, i.e. the counts don't default to some other value like -1 or null.
    @Test
    public void emptyPlan_countsAreZero() {
        CsvImportPlan plan = new CsvImportPlan();
        assertEquals(0, plan.newStudentCount());
        assertEquals(0, plan.matchedStudentCount());
    }

    // Verifies that newStudentCount() and matchedStudentCount() correctly split resolved rows
    // by their isNewStudent flag: of the 3 rows added below, 2 are marked "new" (true) and
    // 1 is marked "already existed" (false).
    @Test
    public void countsSplitNewVersusMatchedStudents() {
        CsvImportPlan plan = new CsvImportPlan();
        // The "d1"/"c1" discipline/class-group ids are arbitrary and reused across all three
        // rows on purpose: this test only cares about the isNewStudent flag (the boolean
        // argument), not which discipline/class-group the rows belong to.
        plan.resolved.add(new ResolvedCsvRow("s1", "André", true, "d1", "BDI", false, "c1", "A", false));
        plan.resolved.add(new ResolvedCsvRow("s2", "Beatriz", false, "d1", "BDI", false, "c1", "A", false));
        plan.resolved.add(new ResolvedCsvRow("s3", "Caio", true, "d1", "BDI", false, "c1", "A", false));

        assertEquals(2, plan.newStudentCount());
        assertEquals(1, plan.matchedStudentCount());
    }

    // Verifies that rows added to the "errors" list (rows that failed to match) are not
    // counted as either new or matched students — only the "resolved" list feeds those counts.
    @Test
    public void errorsDoNotAffectStudentCounts() {
        CsvImportPlan plan = new CsvImportPlan();
        plan.resolved.add(new ResolvedCsvRow("s1", "André", true, "d1", "BDI", false, "c1", "A", false));
        // Line number 5 and the reason text below are arbitrary placeholders; only the fact
        // that this row landed in "errors" (rather than "resolved") matters for this test.
        plan.errors.add(new CsvRowError(5, "Missing discipline name"));

        assertEquals(1, plan.newStudentCount());
        assertEquals(0, plan.matchedStudentCount());
        assertEquals(1, plan.errors.size());
    }

    // Verifies newDisciplineCount()/newClassGroupCount() tally distinct ids flagged as new,
    // and don't double-count when several rows share the same brand-new discipline/class group
    // (e.g. every student in the same new class, which is the common case for a roster import).
    @Test
    public void newStructureCountsAreDistinctAndFlagged() {
        CsvImportPlan plan = new CsvImportPlan();
        plan.resolved.add(new ResolvedCsvRow("s1", "André", true, "d1", "DS", true, "c1", "GRUPO A", true));
        plan.resolved.add(new ResolvedCsvRow("s2", "Beatriz", true, "d1", "DS", true, "c1", "GRUPO A", true));
        plan.resolved.add(new ResolvedCsvRow("s3", "Caio", true, "d1", "DS", true, "c2", "GRUPO B", true));
        plan.resolved.add(new ResolvedCsvRow("s4", "Duda", false, "d2", "BDI", false, "c3", "A", false));

        assertEquals(1, plan.newDisciplineCount());
        assertEquals(2, plan.newClassGroupCount());
    }
}
