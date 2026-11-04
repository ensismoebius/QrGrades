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
 * One student tracked by the app, independent of any particular discipline or class. A
 * student becomes associated with a class group only through an {@link Enrollment}, so the
 * same Student can be enrolled in several class groups at once.
 */
public class Student {
    public String id;
    public String name;
    public String photoPath; // File path to the student's saved photo on device storage; may be null if no photo was taken.
    // Random secret encoded in the student's printed QR code once it has been regenerated (a lost
    // or leaked code is retired by issuing a new one). Null for a student whose code was never
    // regenerated: their original code is simply their id, so existing printed codes keep working.
    public String qrToken;

    /**
     * The exact text this student's QR code must encode right now. Once {@link #qrToken} is set,
     * the id stops being accepted by the scanner, which is what makes regenerating a code
     * actually invalidate the old one.
     */
    public String qrCode() {
        return qrToken != null ? qrToken : id;
    }

    @Override
    public String toString() {
        return name == null ? "" : name;
    }
}
