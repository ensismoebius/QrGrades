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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Handles reading and writing student photos as PNG files on local storage. Android apps don't
 * normally store images directly in the database; instead the database keeps a file path (see
 * {@link StudentDbHelper#COL_PHOTO_PATH}) and the actual image bytes live in the app's private
 * storage directory, which is exactly what this class manages.
 */
public class BitmapConverter {

    /** Returns (creating if needed) the private app folder where all student photos are kept. */
    public static File getPhotoDir(Context context) {
        // context.getFilesDir() is the app's private internal storage directory — other apps
        // cannot read it, and it is removed automatically when the app is uninstalled.
        File dir = new File(context.getFilesDir(), "photos");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Saves {@code bitmap} to disk as "<studentId>.png" and returns the resulting file path, or null on failure. */
    static public String saveStudentPhoto(Context context, Bitmap bitmap, String studentId) {
        File file = new File(getPhotoDir(context), studentId + ".png");
        // try-with-resources: FileOutputStream is auto-closed even if compress() throws.
        try (FileOutputStream out = new FileOutputStream(file)) {
            // Bitmap.compress encodes the in-memory image into PNG bytes and writes them to the
            // stream. The quality argument (100) is ignored for PNG, which is always lossless.
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            return null;
        }
        return file.getAbsolutePath();
    }

    /** Loads a previously saved photo from disk into a Bitmap, or null if there is no path/file. */
    static public Bitmap loadBitmap(String photoPath) {
        if (photoPath == null) return null;
        // decodeFile reads and decodes an image file straight into memory; returns null itself
        // if the file is missing or not a valid image, so callers should still null-check.
        return BitmapFactory.decodeFile(photoPath);
    }

    /** Deletes a previously saved photo file, if it exists. Safe to call with a null path. */
    static public void deletePhoto(String photoPath) {
        if (photoPath == null) return;
        File file = new File(photoPath);
        if (file.exists()) file.delete();
    }

    // Helper method to scale a bitmap while maintaining aspect ratio
    static public Bitmap scaleBitmap(Bitmap bitmap, int maxWidth, int maxHeight) {
        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();
        int newWidth, newHeight;

        if (originalWidth > originalHeight) {
            // Landscape image: constrain the wider dimension (width) to maxWidth, and scale
            // height down by the same ratio so the aspect ratio (shape) doesn't get distorted.
            newWidth = maxWidth;
            newHeight = (int) ((float) originalHeight / originalWidth * maxWidth);
        } else {
            // Portrait image or square image: constrain height instead, scale width to match.
            newHeight = maxHeight;
            newWidth = (int) ((float) originalWidth / originalHeight * maxHeight);
        }

        // The final "true" argument requests bilinear filtering for smoother-looking results
        // than a naive nearest-neighbor resize.
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }
}
