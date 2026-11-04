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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One student's attendance record for a single class day. A row only exists once a roll call has
 * been started for that class group and day (everyone enrolled starts out {@link #STATUS_ABSENT})
 * or the student was marked individually, so "no row" means "no class was recorded", not "absent".
 */
public class Attendance {
    public static final int STATUS_ABSENT = 0;
    public static final int STATUS_PRESENT = 1;
    // Absent with a documented reason. Still counted as an absence in the attendance percentage.
    public static final int STATUS_EXCUSED = 2;

    public String id;
    public String classGroupId; // The class group the roll call belongs to, as it was on that day.
    public String studentId;
    public String day; // Local calendar day, "yyyy-MM-dd".
    public int status;
    // When the row was first recorded; never changes, so the record stays in the period it was taken in.
    public long createdAt;
    public long updatedAt;

    public static boolean isValidStatus(int status) {
        return status == STATUS_ABSENT || status == STATUS_PRESENT || status == STATUS_EXCUSED;
    }

    /** The local calendar day containing {@code millis}, in the stored "yyyy-MM-dd" form. */
    public static String dayOf(long millis) {
        // Locale.US keeps the digits ASCII whatever the device language; SimpleDateFormat isn't thread-safe, so one per call.
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }

    public static String today() {
        return dayOf(System.currentTimeMillis());
    }

    /** True for a real calendar day in "yyyy-MM-dd" form (rejects "2026-02-30" as well as free text). */
    public static boolean isValidDay(String day) {
        if (day == null || !day.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        try {
            format.parse(day);
            return true;
        } catch (ParseException e) {
            return false;
        }
    }
}
