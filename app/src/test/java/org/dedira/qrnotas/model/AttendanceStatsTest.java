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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AttendanceStatsTest {

    private static Attendance record(int status) {
        Attendance a = new Attendance();
        a.status = status;
        return a;
    }

    @Test
    public void countsEachStatusAndComputesPercentages() {
        List<Attendance> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(record(Attendance.STATUS_PRESENT));
        rows.add(record(Attendance.STATUS_ABSENT));
        rows.add(record(Attendance.STATUS_ABSENT));
        rows.add(record(Attendance.STATUS_EXCUSED));
        rows.add(record(Attendance.STATUS_EXCUSED));

        AttendanceStats stats = AttendanceStats.of(rows);

        assertEquals(6, stats.present);
        assertEquals(2, stats.absent);
        assertEquals(2, stats.excused);
        assertEquals(10, stats.total());
        assertEquals(60.0, stats.presentPercent(), 0.0001);
        // Excused absences still count as missed classes for the limit.
        assertEquals(40.0, stats.absentPercent(), 0.0001);
    }

    @Test
    public void emptyWindowIsNotHeldAgainstTheStudent() {
        AttendanceStats stats = new AttendanceStats();
        assertEquals(100.0, stats.presentPercent(), 0.0001);
        assertEquals(0.0, stats.absentPercent(), 0.0001);
    }

    @Test
    public void isValidDay_acceptsRealCalendarDaysOnly() {
        assertTrue(Attendance.isValidDay("2026-10-21"));
        assertTrue(Attendance.isValidDay("2028-02-29"));
        assertFalse(Attendance.isValidDay("2026-02-30"));
        assertFalse(Attendance.isValidDay("2026-13-01"));
        assertFalse(Attendance.isValidDay("21/10/2026"));
        assertFalse(Attendance.isValidDay("hoje"));
        assertFalse(Attendance.isValidDay(null));
    }

    @Test
    public void isValidStatus_rejectsUnknownValues() {
        assertTrue(Attendance.isValidStatus(Attendance.STATUS_ABSENT));
        assertTrue(Attendance.isValidStatus(Attendance.STATUS_PRESENT));
        assertTrue(Attendance.isValidStatus(Attendance.STATUS_EXCUSED));
        assertFalse(Attendance.isValidStatus(3));
        assertFalse(Attendance.isValidStatus(-1));
    }
}
