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
 * One entry in the audit trail of point changes for an {@link Enrollment} — e.g. "+10 points,
 * QR code scan" or "-5 points, manual correction". The running total on the Enrollment is the
 * sum of all its PointsHistory entries' deltas; this class lets the app show students/teachers
 * exactly when and why points were awarded or removed.
 */
public class PointsHistory {
    public String id;
    public String enrollmentId; // Foreign key: id of the Enrollment this point change applies to.
    public int pointsDelta; // Change in points for this single event (can be negative); NOT a running total.
    public String note; // Optional human-readable reason for the change (e.g. "QR scan", "bonus").
    public long createdAt; // When this entry was created, in milliseconds since the Unix epoch.

    // Populated only when the entry is loaded for a multi-discipline view, to label which discipline it belongs to.
    public String disciplineName;
}
