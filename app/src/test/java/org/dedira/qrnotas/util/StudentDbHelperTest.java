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

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Unit test for {@link StudentDbHelper}, the {@code SQLiteOpenHelper} that owns the app's
 * database schema (students, disciplines, class groups, goals, enrollments, points history).
 * This test focuses on {@code onUpgrade}, the callback Android calls when the database version
 * number increases, to make sure schema upgrades don't crash and leave the database usable.
 */
@RunWith(RobolectricTestRunner.class)
public class StudentDbHelperTest {

    // Verifies that upgrading across several schema versions at once (from version 1 straight
    // to version 4) runs without throwing, and leaves a working (queryable) students table —
    // freshly recreated and therefore empty.
    @Test
    public void onUpgrade_dropsAndRecreatesAllTablesWithoutError() {
        // Robolectric provides a fake-but-functional Android Context so SQLiteOpenHelper can
        // create/open a real (in-memory/on-disk test) SQLite database without an actual device.
        Context context = RuntimeEnvironment.getApplication();
        StudentDbHelper helper = new StudentDbHelper(context);
        SQLiteDatabase db = helper.getWritableDatabase();

        // Simulate upgrading from an old schema version (1) to the current one (4); this is
        // what onUpgrade would receive when opening a database created by an older app version.
        helper.onUpgrade(db, 1, 4);

        // try-with-resources ensures the cursor is closed even if the assertion fails.
        try (Cursor cursor = db.query(StudentDbHelper.TABLE_STUDENTS, null, null, null, null, null, null)) {
            // The table was dropped and recreated by the upgrade, so it should contain no rows.
            assertEquals(0, cursor.getCount());
        }
    }
}
