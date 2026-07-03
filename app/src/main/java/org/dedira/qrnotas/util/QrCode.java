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
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.dedira.qrnotas.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Generates a QR code image for a student (so it can be printed/displayed and later scanned to
 * award points) and lets the user share that image via Android's standard share sheet. The actual
 * QR encoding is done by the ZXing ("Zebra Crossing") library; this class just turns its raw
 * output into an Android {@link Bitmap} and wires up the sharing flow.
 */
public class QrCode {


    /** Encodes {@code data} (typically a student id) as a 300x300 black-and-white QR code bitmap, or null on failure. */
    public static Bitmap generateQRCode(String data) {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        try {
            // encode() returns a BitMatrix: a grid of booleans, one per QR "module" (the black/white
            // squares that make up a QR code), not an image yet.
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, 300, 300);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            // RGB_565 is a memory-efficient pixel format (16 bits/pixel) — plenty for a pure
            // black/white QR image, no need for a full 32-bit-per-pixel format with alpha.
            Bitmap btmQrcode = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            // Walk every pixel and paint it black or white according to the BitMatrix value.
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    btmQrcode.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return btmQrcode;
        } catch (WriterException e) {
            // Encoding can fail if the data is too long/invalid for a QR code; log and signal
            // failure by returning null rather than crashing the app.
            e.printStackTrace();
        }
        return null;
    }

    /** Saves the QR bitmap to a temp file and opens Android's share sheet so the user can send/print it. */
    public static void share(Context context, Bitmap qrCodeBitmap) {
        if (qrCodeBitmap == null) return;

        try {
            // getCacheDir() is private, OS-cleanable storage — fine for a throwaway file that only
            // needs to live long enough to be picked up by the share target app.
            File dir = new File(context.getCacheDir(), "qrcodes");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "qrcode_" + System.currentTimeMillis() + ".png");

            try (FileOutputStream out = new FileOutputStream(file)) {
                qrCodeBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }

            // Other apps can't directly read files from this app's private storage. FileProvider
            // creates a special "content://" URI that grants temporary, scoped read access to just
            // this one file to whichever app the user picks to share with.
            Uri qrCodeUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);

            // Build a standard Android "send" intent carrying the image, then let the user choose
            // which app (messaging, email, print, etc.) to hand it to.
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, qrCodeUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "QR Code Share");
            // Without this flag, the receiving app would not be allowed to open the content:// URI.
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            context.startActivity(Intent.createChooser(shareIntent, "Share QR Code"));
        } catch (IOException e) {
            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show();
        }
    }

}


