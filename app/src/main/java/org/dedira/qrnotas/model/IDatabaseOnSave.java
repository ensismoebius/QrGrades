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
 * Callback used to receive the result of a database write (create/insert) operation.
 * Database work (like inserting a new row) runs on a background thread so it doesn't freeze
 * the UI; once it finishes, the app calls this callback — posted back to the main/UI thread —
 * with whether the save succeeded and the object that was saved (often now populated with a
 * generated id).
 *
 * @param <T> the type of object being saved (e.g. Student, Goal).
 */
public interface IDatabaseOnSave<T> {
    /**
     * Called once the save finishes.
     *
     * @param success whether the save completed without error.
     * @param object  the saved object.
     */
    void onSaveComplete(boolean success, T object);
}
