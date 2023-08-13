package org.dedira.qrnotas.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.io.ByteArrayOutputStream;

public class BitmapCoverter {
    static public String stringToBitmap(Bitmap btm) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        btm.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }

    static public Bitmap stringToBitmap(String encodedImage) {
        byte[] decodedByteArray = Base64.decode(encodedImage, Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(decodedByteArray, 0, decodedByteArray.length);
    }

    // Helper method to scale a bitmap while maintaining aspect ratio
    static public Bitmap scaleBitmap(Bitmap bitmap, int maxWidth, int maxHeight) {
        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();
        int newWidth, newHeight;

        if (originalWidth > originalHeight) {
            // Landscape image
            newWidth = maxWidth;
            newHeight = (int) ((float) originalHeight / originalWidth * maxWidth);
        } else {
            // Portrait image or square image
            newHeight = maxHeight;
            newWidth = (int) ((float) originalWidth / originalHeight * maxHeight);
        }

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }
}
