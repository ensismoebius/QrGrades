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
 * One bathroom trip for a {@link Student}. A visit is "active" (the student is currently out of
 * the classroom) while {@link #returnedAt} is null and {@link #evaded} is false; it's closed
 * either by the student scanning back in ({@link #returnedAt} gets set) or, if that never
 * happens within the evasion window, by {@link org.dedira.qrnotas.util.Database} marking it
 * {@link #evaded} instead.
 */
public class BathroomVisit {
    public String id;
    public String studentId; // Foreign key: id of the Student who left the classroom.
    public long wentAt; // When the student left, in milliseconds since the Unix epoch.
    public Long returnedAt; // When the student came back, or null while the visit is still active.
    public boolean evaded; // True once the student failed to return within the evasion window.
}
