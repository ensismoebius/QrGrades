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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

/**
 * Unit tests for {@link BitmapConverter}, which reads and writes student photos as PNG files
 * in the app's private storage directory (the database itself only stores a file path, never
 * the raw image bytes). Covers creating the photo directory, saving/loading/deleting photo
 * files, and the width/height math used to scale a bitmap down to a target box.
 */
@RunWith(RobolectricTestRunner.class)
public class BitmapConverterTest {

    // Robolectric simulates the Android framework on the JVM (no emulator/device needed), so
    // RuntimeEnvironment.getApplication() gives a real-enough Context (with real file I/O
    // against a temporary directory) that BitmapConverter's file operations can be exercised.
    private final Context context = RuntimeEnvironment.getApplication();

    // Verifies that asking for the photo directory creates it on disk if it doesn't exist yet
    // ("lazily", i.e. only when first requested) rather than requiring it to be pre-created.
    @Test
    public void getPhotoDir_createsDirectoryLazily() {
        File dir = BitmapConverter.getPhotoDir(context);
        assertTrue(dir.exists());
        assertTrue(dir.isDirectory());
    }

    // Verifies that saving a student's photo writes an actual PNG file to disk and returns a
    // path ending in "<studentId>.png", so callers can store that path in the database.
    @Test
    public void saveStudentPhoto_writesPngFileAndReturnsItsPath() {
        // A tiny 4x4 bitmap is enough to exercise the save logic; the pixel content is irrelevant.
        Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        String path = BitmapConverter.saveStudentPhoto(context, bitmap, "student-1");

        assertTrue(path != null && path.endsWith("student-1.png"));
        assertTrue(new File(path).exists());
    }

    // Verifies that loading with a null path is handled gracefully (returns null) instead of
    // throwing, since callers may not have a photo path yet (e.g. a student with no photo).
    @Test
    public void loadBitmap_nullPathReturnsNull() {
        assertNull(BitmapConverter.loadBitmap(null));
    }

    // Verifies the save -> load round trip: a bitmap saved to disk can be loaded back as a
    // non-null bitmap from the path that saveStudentPhoto returned.
    @Test
    public void saveThenLoadBitmap_roundTrips() {
        Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        String path = BitmapConverter.saveStudentPhoto(context, bitmap, "student-2");

        Bitmap loaded = BitmapConverter.loadBitmap(path);
        assertTrue(loaded != null);
    }

    // Verifies that deleting a photo actually removes the underlying file from disk.
    @Test
    public void deletePhoto_removesFile() {
        Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        String path = BitmapConverter.saveStudentPhoto(context, bitmap, "student-3");
        assertTrue(new File(path).exists());

        BitmapConverter.deletePhoto(path);
        assertFalse(new File(path).exists());
    }

    // Verifies that deleting with a null path does nothing and does not throw (no-op), since
    // students without a saved photo have a null path.
    @Test
    public void deletePhoto_nullPathIsNoOp() {
        BitmapConverter.deletePhoto(null);
    }

    // Verifies that deleting a path that doesn't correspond to any file on disk is also a
    // silent no-op rather than throwing (e.g. a stale/already-deleted path).
    @Test
    public void deletePhoto_missingFileIsNoOp() {
        BitmapConverter.deletePhoto("/no/such/file.png");
    }

    // Verifies that scaling a landscape (wider-than-tall) bitmap into a 100x100 box scales it
    // down by matching the target width, so 200x100 becomes 100x50 (aspect ratio preserved).
    @Test
    public void scaleBitmap_landscapeScalesByWidth() {
        Bitmap bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888);
        Bitmap scaled = BitmapConverter.scaleBitmap(bitmap, 100, 100);
        assertEquals(100, scaled.getWidth());
        assertEquals(50, scaled.getHeight());
    }

    // Verifies that scaling a portrait (taller-than-wide) bitmap into a 100x100 box scales it
    // down by matching the target height, so 100x200 becomes 50x100 (aspect ratio preserved).
    @Test
    public void scaleBitmap_portraitScalesByHeight() {
        Bitmap bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
        Bitmap scaled = BitmapConverter.scaleBitmap(bitmap, 100, 100);
        assertEquals(50, scaled.getWidth());
        assertEquals(100, scaled.getHeight());
    }

    // Verifies the tie-breaking behavior for a perfectly square bitmap: since width and height
    // are equal, scaling exercises the "scale by height" code branch and still produces a
    // correctly-sized square result (50x50) rather than a degenerate or mismatched size.
    @Test
    public void scaleBitmap_squareScalesByHeightBranch() {
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Bitmap scaled = BitmapConverter.scaleBitmap(bitmap, 50, 50);
        assertEquals(50, scaled.getWidth());
        assertEquals(50, scaled.getHeight());
    }
}
