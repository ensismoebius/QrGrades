package org.dedira.qrnotas.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class BitmapConverter {

    public static File getPhotoDir(Context context) {
        File dir = new File(context.getFilesDir(), "photos");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static public String saveStudentPhoto(Context context, Bitmap bitmap, String studentId) {
        File file = new File(getPhotoDir(context), studentId + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            return null;
        }
        return file.getAbsolutePath();
    }

    static public Bitmap loadBitmap(String photoPath) {
        if (photoPath == null) return null;
        return BitmapFactory.decodeFile(photoPath);
    }

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
