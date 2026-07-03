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
 * Links one {@link Student} to one {@link ClassGroup} — i.e. the fact that this student is
 * enrolled in this class. This is where the student's running point total for that class is
 * stored; a student enrolled in several class groups has one Enrollment (and one point
 * total) per class group.
 */
public class Enrollment {
    public String id;
    public String studentId; // Foreign key: id of the enrolled Student.
    public String classGroupId; // Foreign key: id of the ClassGroup the student is enrolled in.
    public int grades; // Accumulated points earned in this class group so far (NOT a 0-100 grade, despite the name).
}
