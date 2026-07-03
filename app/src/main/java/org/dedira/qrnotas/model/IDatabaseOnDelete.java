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
 * Callback used to receive the result of a database delete operation. Database work (like
 * deleting a row) runs on a background thread so it doesn't freeze the UI; once it finishes,
 * the app calls this callback — posted back to the main/UI thread — with whether the delete
 * succeeded and the object that was deleted (or attempted).
 *
 * @param <T> the type of object being deleted (e.g. Student, Enrollment).
 */
public interface IDatabaseOnDelete<T> {
    /**
     * Called once the delete finishes.
     *
     * @param success whether the delete completed without error.
     * @param object  the object that was (or would have been) deleted.
     */
    void onLoadComplete(boolean success, T object);
}
