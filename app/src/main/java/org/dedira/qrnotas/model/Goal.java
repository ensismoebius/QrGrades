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
 * A point target that a teacher sets for a {@link Discipline} within a time window, e.g. "Reach
 * 30 points between 1 February and 30 April". Only the points awarded on the days from
 * {@link #startDate} to {@link #endDate} (both included) count towards it. A student's progress
 * is computed separately at read time (see {@link GoalProgress}) — this class only stores the
 * goal's definition, not any student's current progress.
 */
public class Goal {
    public String id;
    public String disciplineId; // Foreign key: id of the Discipline this goal applies to.
    public String name;
    public Integer targetPoints = 0; // Number of points a student must collect inside the window to achieve this goal.
    public String startDate; // First day that counts, local "yyyy-MM-dd". Null only for goals saved before dates existed.
    public String endDate; // Last day that counts (included), local "yyyy-MM-dd". Null only for goals saved before dates existed.

    /**
     * True when both dates are real calendar days and the goal doesn't end before it starts. A new
     * or edited goal must satisfy this to be saved.
     */
    public boolean hasValidDates() {
        return Attendance.isValidDay(startDate) && Attendance.isValidDay(endDate) && startDate.compareTo(endDate) <= 0;
    }

    /**
     * True for a goal that has a window at all. Goals saved before dates existed don't; they keep
     * counting the whole period's total until the teacher gives them dates.
     */
    public boolean hasDates() {
        return startDate != null && endDate != null;
    }
}
