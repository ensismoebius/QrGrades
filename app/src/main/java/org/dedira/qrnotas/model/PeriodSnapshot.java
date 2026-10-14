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
 * One enrollment's final point total at the moment a {@link Period} was closed. Ids (not names)
 * are what's actually stored, so {@link org.dedira.qrnotas.util.Database#reopenPeriod} can
 * restore the exact enrollment; {@link #studentName} is filled in by a JOIN when a report loads
 * these rows, not persisted here.
 */
public class PeriodSnapshot {
    public String id;
    public String periodId;
    public String enrollmentId;
    public String studentId;
    public String studentName;
    public String classGroupId;
    public int points;
}
