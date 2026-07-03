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
 * Represents one class/section ("turma") within a {@link Discipline}, e.g. "BDI - Turma A".
 * A discipline can have several class groups, and each class group has its own list of
 * enrolled students (see {@link Enrollment}).
 */
public class ClassGroup {
    public String id;
    public String disciplineId; // Foreign key: id of the Discipline this class group belongs to.
    public String name;

    @Override
    public String toString() {
        return name == null ? "" : name;
    }
}
