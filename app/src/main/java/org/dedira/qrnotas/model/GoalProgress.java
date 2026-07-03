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
 * A read-only snapshot of how close one specific student is to reaching one {@link Goal}.
 * Unlike {@code Goal} (which just stores the target), this class combines that target with
 * the student's current point total to answer "has this student achieved this goal yet, and
 * if not, how many points do they still need?". It is computed on the fly, not stored in the
 * database.
 */
public class GoalProgress {
    public String goalName;
    public int targetPoints;
    public int remaining; // How many more points the student needs to reach the target; 0 once achieved.
    public boolean achieved; // True once the student's points reached (or passed) targetPoints.

    public GoalProgress(String goalName, int targetPoints, int currentPoints) {
        this.goalName = goalName;
        this.targetPoints = targetPoints;
        // The goal is achieved as soon as the student's current points meet or exceed the target.
        this.achieved = currentPoints >= targetPoints;
        // If already achieved there's nothing left to do; otherwise compute the point gap.
        this.remaining = this.achieved ? 0 : (targetPoints - currentPoints);
    }
}
