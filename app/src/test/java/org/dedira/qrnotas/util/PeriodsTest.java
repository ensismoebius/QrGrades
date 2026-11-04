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

import static org.junit.Assert.assertEquals;

import org.dedira.qrnotas.model.Period;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PeriodsTest {

    private static Period period(String id, long closedAt, String closedClassGroupId, Long reopenedAt) {
        Period p = new Period();
        p.id = id;
        p.disciplineId = "d";
        p.closedAt = closedAt;
        p.closedClassGroupId = closedClassGroupId;
        p.reopenedAt = reopenedAt;
        return p;
    }

    @Test
    public void bucketOf_placesTimestampsIntoTheFirstCloseAtOrAfterThem() {
        long[] closes = {100, 200, 300};
        assertEquals(0, Periods.bucketOf(closes, 1));
        assertEquals(0, Periods.bucketOf(closes, 100));
        assertEquals(1, Periods.bucketOf(closes, 101));
        assertEquals(2, Periods.bucketOf(closes, 300));
        assertEquals(3, Periods.bucketOf(closes, 301));
    }

    @Test
    public void bucketOf_withNoClosesEverythingIsStillOpen() {
        assertEquals(0, Periods.bucketOf(new long[0], 12345));
    }

    @Test
    public void standingFor_keepsDisciplineWideAndOwnClassCloses_dropsOthersAndReopened() {
        List<Period> all = new ArrayList<>();
        all.add(period("late", 300, null, null));
        all.add(period("own", 200, "turmaA", null));
        all.add(period("other", 150, "turmaB", null));
        all.add(period("undone", 100, null, 999L));
        all.add(period("first", 50, null, null));

        List<Period> standing = Periods.standingFor(all, "turmaA");

        assertEquals(3, standing.size());
        assertEquals("first", standing.get(0).id);
        assertEquals("own", standing.get(1).id);
        assertEquals("late", standing.get(2).id);
    }
}
