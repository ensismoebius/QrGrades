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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import org.dedira.qrnotas.model.Student;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * The printable sheet of QR codes: each student's code with a short name under it, laid out in a
 * grid on A4 pages (the same as the browser page's print view). Names are shortened so a label
 * stays readable on a small sticker: the first name in full, the rest as initials.
 */
public final class QrSheet {

    private static final int PAGE_WIDTH = 595;   // A4 in PDF points
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 36;
    private static final int COLUMNS = 4;
    private static final int ROWS = 5;
    private static final int LABEL_HEIGHT = 22;

    private QrSheet() {
    }

    /** "Maria Aparecida Santos" -> "Maria A. S."; a single name stays as it is. */
    public static String label(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) return "";
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 1) return parts[0];
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            out.append(' ').append(parts[i].substring(0, 1).toUpperCase(Locale.getDefault())).append('.');
        }
        return out.toString();
    }

    /** Writes the sheet for these students into the app's export folder and returns the PDF. */
    public static File writePdf(Context context, List<Student> students) throws IOException {
        PdfDocument document = new PdfDocument();
        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.BLACK);
        labelPaint.setTextSize(11);
        labelPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        Paint bitmapPaint = new Paint();
        bitmapPaint.setFilterBitmap(false); // keep the squares of the code sharp

        float cellWidth = (PAGE_WIDTH - 2f * MARGIN) / COLUMNS;
        float cellHeight = (PAGE_HEIGHT - 2f * MARGIN) / ROWS;
        float qrSize = Math.min(cellWidth - 16, cellHeight - LABEL_HEIGHT - 8);

        int perPage = COLUMNS * ROWS;
        int pageNumber = 0;
        PdfDocument.Page page = null;
        Canvas canvas = null;
        try {
            for (int i = 0; i < students.size(); i++) {
                if (i % perPage == 0) {
                    if (page != null) document.finishPage(page);
                    pageNumber++;
                    page = document.startPage(new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create());
                    canvas = page.getCanvas();
                }
                int slot = i % perPage;
                float left = MARGIN + (slot % COLUMNS) * cellWidth;
                float top = MARGIN + (slot / COLUMNS) * cellHeight;

                Student student = students.get(i);
                Bitmap code = QrCode.generateQRCode(student.qrCode());
                if (code != null) {
                    float x = left + (cellWidth - qrSize) / 2f;
                    canvas.drawBitmap(code, null, new RectF(x, top + 4, x + qrSize, top + 4 + qrSize), bitmapPaint);
                    code.recycle();
                }
                canvas.drawText(fit(label(student.name), labelPaint, cellWidth - 8), left + cellWidth / 2f,
                        top + 4 + qrSize + 15, labelPaint);
            }
            if (page == null) { // nothing to print still makes a valid (blank) document
                document.finishPage(document.startPage(new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()));
            } else {
                document.finishPage(page);
            }

            File file = new File(Exporter.exportDir(context), "codigos_qr_" + System.currentTimeMillis() + ".pdf");
            try (FileOutputStream out = new FileOutputStream(file)) {
                document.writeTo(out);
            }
            return file;
        } finally {
            document.close();
        }
    }

    /** Cuts the text with an ellipsis if it is wider than {@code width}. */
    private static String fit(String text, Paint paint, float width) {
        if (paint.measureText(text) <= width) return text;
        String cut = text;
        while (cut.length() > 1 && paint.measureText(cut + "…") > width) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }
}
