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

public class QrCode {


    public static Bitmap generateQRCode(String data) {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        try {
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, 300, 300);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap btmQrcode = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    btmQrcode.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return btmQrcode;
        } catch (WriterException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void share(Context context, Bitmap qrCodeBitmap) {
        if (qrCodeBitmap == null) return;

        try {
            File dir = new File(context.getCacheDir(), "qrcodes");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "qrcode_" + System.currentTimeMillis() + ".png");

            try (FileOutputStream out = new FileOutputStream(file)) {
                qrCodeBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }

            Uri qrCodeUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, qrCodeUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "QR Code Share");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            context.startActivity(Intent.createChooser(shareIntent, "Share QR Code"));
        } catch (IOException e) {
            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show();
        }
    }

}


