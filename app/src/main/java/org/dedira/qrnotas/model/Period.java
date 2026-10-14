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
 * One "Fechar período" (whole discipline) or "Fechar período e turma" (one class group) action:
 * every affected enrollment's final point total was snapshotted (see {@link PeriodSnapshot}) and
 * reset to 0 to start a fresh count. Never deletes anything by itself — {@link #closedAt} marks
 * the boundary, and the snapshots are what let a report reconstruct "period 1 vs period 2" later.
 */
public class Period {
    public String id;
    public String disciplineId;
    // Optional teacher-given name (e.g. "Bimestre 1"); null/blank falls back to showing closedAt.
    public String label;
    public long closedAt;
    // Null for a discipline-wide close; set to the class group's id for a class-level close
    // (that class group's ClassGroup#closedAt is set to match this same timestamp).
    public String closedClassGroupId;
    // Null unless closedClassGroupId's students were moved into a brand-new class group rather
    // than just archived in place. Editable afterward (see Database#changePeriodReassignmentTarget)
    // without undoing the point consolidation itself.
    public String reassignedToClassGroupId;
    // Null while the closure stands; set once Database#reopenPeriod has undone it.
    public Long reopenedAt;

    // Filled in by a JOIN when a report loads these rows — not persisted columns on
    // TABLE_PERIODS itself.
    public String disciplineName;
    public String closedClassGroupName;
    public String reassignedToClassGroupName;

    public boolean isReopened() {
        return reopenedAt != null;
    }

    public boolean isClassClose() {
        return closedClassGroupId != null;
    }

    public boolean isReassignment() {
        return reassignedToClassGroupId != null;
    }
}
