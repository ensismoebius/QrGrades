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

import java.util.Collection;

/** Tally of one student's attendance records over some window (a period, a class group, a day range). */
public class AttendanceStats {
    public int present;
    public int absent;
    public int excused;

    public void add(Attendance record) {
        if (record.status == Attendance.STATUS_PRESENT) present++;
        else if (record.status == Attendance.STATUS_EXCUSED) excused++;
        else absent++;
    }

    public static AttendanceStats of(Collection<Attendance> records) {
        AttendanceStats stats = new AttendanceStats();
        for (Attendance record : records) stats.add(record);
        return stats;
    }

    /** Class days recorded in the window, whatever the outcome. */
    public int total() {
        return present + absent + excused;
    }

    /** Share of recorded days the student was present (0-100); 100 when nothing was recorded yet, so a student isn't flagged for a class that hasn't happened. */
    public double presentPercent() {
        return total() == 0 ? 100.0 : 100.0 * present / total();
    }

    /**
     * Share of recorded days the student missed (0-100), excused absences included — the figure a
     * minimum-attendance rule (e.g. "at most 25% absences") is checked against. Excused days are
     * kept as their own count so the reason stays visible, but they still count as missed classes.
     */
    public double absentPercent() {
        return total() == 0 ? 0.0 : 100.0 * (absent + excused) / total();
    }
}
