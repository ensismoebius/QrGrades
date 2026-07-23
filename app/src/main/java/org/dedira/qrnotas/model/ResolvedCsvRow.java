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
 * A CSV row whose discipline/class-group/student have all been matched against the database, or
 * determined to need creating (a student, discipline, or class group named in the CSV but not
 * yet in the database). This is the "ready to import" counterpart of {@link CsvStudentRow}:
 * instead of plain-text names, it carries the resolved database ids needed to actually create
 * the {@link Enrollment} — generating new ids up front for anything that still needs creating, so
 * multiple rows referencing the same new discipline/class-group/student all agree on its id.
 */
public class ResolvedCsvRow {
    public final String studentId; // Id of the matching Student; if isNewStudent is true, this may be a not-yet-saved placeholder id.
    public final String name;
    public final boolean isNewStudent; // True if no existing Student matched this row's name, so a new Student record must be created on import.
    public final String disciplineId; // Id of the Discipline matched (or, if isNewDiscipline, to be created) from the CSV's discipline name.
    public final String disciplineName; // The CSV's discipline name, used to create the Discipline row when isNewDiscipline is true.
    public final boolean isNewDiscipline; // True if no existing Discipline matched this row's discipline name, so a new one must be created on import.
    public final String classGroupId; // Id of the ClassGroup matched (or, if isNewClassGroup, to be created) from the CSV's class-group name.
    public final String classGroupName; // The CSV's class-group name, used to create the ClassGroup row when isNewClassGroup is true.
    public final boolean isNewClassGroup; // True if no existing ClassGroup matched this row's class-group name (within its discipline), so a new one must be created on import.

    public ResolvedCsvRow(String studentId, String name, boolean isNewStudent,
                           String disciplineId, String disciplineName, boolean isNewDiscipline,
                           String classGroupId, String classGroupName, boolean isNewClassGroup) {
        this.studentId = studentId;
        this.name = name;
        this.isNewStudent = isNewStudent;
        this.disciplineId = disciplineId;
        this.disciplineName = disciplineName;
        this.isNewDiscipline = isNewDiscipline;
        this.classGroupId = classGroupId;
        this.classGroupName = classGroupName;
        this.isNewClassGroup = isNewClassGroup;
    }
}
