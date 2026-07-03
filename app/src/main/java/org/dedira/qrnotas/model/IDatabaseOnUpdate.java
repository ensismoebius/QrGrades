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
 * Callback used to receive the result of a database update operation (modifying an existing
 * row). Database work runs on a background thread so it doesn't freeze the UI; once it
 * finishes, the app calls this callback — posted back to the main/UI thread — with whether
 * the update succeeded and the updated object.
 *
 * @param <T> the type of object being updated (e.g. Student, Enrollment).
 */
public interface IDatabaseOnUpdate<T> {
    /**
     * Called once the update finishes.
     *
     * @param success whether the update completed without error.
     * @param object  the updated object.
     */
    void onUpdateComplete(boolean success, T object);
}
