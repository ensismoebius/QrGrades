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
 * One indiscipline record registered against a {@link Student} — e.g. talking back, disrupting
 * class. Purely a log entry (unlike {@link PointsHistory}, it never affects any point total);
 * {@link #disciplineId} records which class the teacher had selected when it was registered, if any.
 */
public class IndisciplineEvent {
    public String id;
    public String studentId; // Foreign key: id of the Student the record is about.
    public String disciplineId; // Foreign key: id of the Discipline selected at the time, if any.
    public String note; // Optional human-readable description of what happened.
    public long createdAt; // When this entry was created, in milliseconds since the Unix epoch.

    // Populated only when loaded for a view that needs to show it, to label which discipline it belongs to.
    public String disciplineName;
}
