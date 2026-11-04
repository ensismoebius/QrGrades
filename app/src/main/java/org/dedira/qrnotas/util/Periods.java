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

package org.dedira.qrnotas.util;

import org.dedira.qrnotas.model.Period;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Helpers for placing timestamps into the periods a discipline has been closed into. */
public final class Periods {

    private Periods() {
    }

    /**
     * The closes that actually cut a class group's timeline, oldest first: every discipline-wide
     * close of its discipline, plus a class-level close of that very group. Reopened periods are
     * skipped — reopening undid them, so their records fold back into the next period.
     */
    public static List<Period> standingFor(List<Period> disciplinePeriods, String classGroupId) {
        List<Period> standing = new ArrayList<>();
        if (disciplinePeriods == null) return standing;
        for (Period p : disciplinePeriods) {
            if (p.isReopened()) continue;
            if (p.closedClassGroupId == null || p.closedClassGroupId.equals(classGroupId)) standing.add(p);
        }
        Collections.sort(standing, new Comparator<Period>() {
            @Override
            public int compare(Period a, Period b) {
                return Long.compare(a.closedAt, b.closedAt);
            }
        });
        return standing;
    }

    /**
     * Index of the period a timestamp belongs to, given the close times in ascending order: the
     * first close at or after it, or {@code closes.length} — "still open" — if it's later than all
     * of them. A record made at the very moment of a close counts as part of the period it closed.
     */
    public static int bucketOf(long[] closes, long timestamp) {
        for (int i = 0; i < closes.length; i++) {
            if (timestamp <= closes[i]) return i;
        }
        return closes.length;
    }
}
