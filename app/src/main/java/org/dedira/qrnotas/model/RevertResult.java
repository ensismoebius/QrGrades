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
 * Outcome of cancelling (and optionally replacing) a points-history entry. On failure
 * {@link #error} holds a stable code the caller can map to a message: NOT_FOUND,
 * ALREADY_REVERTED, IS_REVERSAL or CLOSED_PERIOD.
 */
public class RevertResult {
    public String error;
    public PointsAward reversal; // The compensating entry that cancels the original.
    public PointsAward replacement; // The corrected entry, only when a correction (not a plain revert) was requested.
}
