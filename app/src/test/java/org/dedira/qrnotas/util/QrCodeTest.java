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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link QrCode#generateQRCode(String)}, which encodes a string (typically a
 * student id) into a black-and-white QR code image using the zxing library. Covers the happy
 * path (a normal id produces a valid bitmap) and the failure path (empty input is rejected).
 */
@RunWith(RobolectricTestRunner.class)
public class QrCodeTest {

    // Verifies that encoding a normal, non-empty string produces a real bitmap with positive
    // width and height (i.e. QR generation succeeded and returned actual image data).
    @Test
    public void generateQRCode_returnsNonNullSquareBitmap() {
        Bitmap bitmap = QrCode.generateQRCode("student-id-123");
        assertNotNull(bitmap);
        assertTrue(bitmap.getWidth() > 0);
        assertTrue(bitmap.getHeight() > 0);
    }

    // Verifies that encoding an empty string fails fast with IllegalArgumentException rather
    // than silently returning a blank/invalid bitmap.
    @Test(expected = IllegalArgumentException.class)
    public void generateQRCode_emptyStringIsRejectedByTheEncoder() {
        // zxing's QRCodeWriter throws IllegalArgumentException (not the checked WriterException
        // QrCode.generateQRCode catches) for empty content, so it propagates uncaught here.
        QrCode.generateQRCode("");
    }
}
