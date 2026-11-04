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

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal top-to-bottom text layout on top of {@link PdfDocument}: A4 pages with margins, word
 * wrapping, automatic page breaks and a page number in the footer. {@link PdfDocument} itself only
 * hands out a blank canvas per page, so anything longer than one screenful (a report card with a
 * term's worth of history) needs the cursor bookkeeping done here rather than at each call site.
 */
final class PdfLayout {

    static final int PAGE_WIDTH = 595;
    static final int PAGE_HEIGHT = 842;
    static final int MARGIN = 40;
    // Room kept at the bottom of every page for the page number.
    private static final int FOOTER_HEIGHT = 24;

    private final PdfDocument document = new PdfDocument();
    private final String pageLabelFormat;
    private final Paint footerPaint = new Paint();
    private PdfDocument.Page page;
    private Canvas canvas;
    private int pageNumber;
    // Baseline of the next line to draw is y + that line's ascent; y is the top of the free area.
    private float y;

    /** @param pageLabelFormat a {@link String#format} pattern taking the page number, e.g. "Página %d" */
    PdfLayout(String pageLabelFormat) {
        this.pageLabelFormat = pageLabelFormat;
        footerPaint.setTextSize(9);
        footerPaint.setColor(0xFF666666);
        startPage();
    }

    float contentWidth() {
        return PAGE_WIDTH - 2f * MARGIN;
    }

    private float bottomLimit() {
        return PAGE_HEIGHT - MARGIN - FOOTER_HEIGHT;
    }

    private void startPage() {
        pageNumber++;
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
        page = document.startPage(info);
        canvas = page.getCanvas();
        y = MARGIN;
    }

    private void finishPage() {
        String label = String.format(Locale.getDefault(), pageLabelFormat, pageNumber);
        canvas.drawText(label, PAGE_WIDTH - MARGIN - footerPaint.measureText(label), PAGE_HEIGHT - MARGIN, footerPaint);
        document.finishPage(page);
    }

    /** Starts a new page if fewer than {@code height} points remain on this one. */
    void ensureSpace(float height) {
        if (y + height > bottomLimit()) {
            finishPage();
            startPage();
        }
    }

    void space(float height) {
        y += height;
    }

    /** Thin horizontal rule across the content width. */
    void rule(Paint paint) {
        ensureSpace(6);
        canvas.drawLine(MARGIN, y + 2, PAGE_WIDTH - MARGIN, y + 2, paint);
        y += 6;
    }

    /** Draws wrapped text at the left margin across the full content width. */
    void text(String text, Paint paint) {
        text(text, paint, MARGIN, contentWidth());
    }

    void text(String text, Paint paint, float x, float width) {
        float lineHeight = lineHeight(paint);
        for (String line : wrap(text, paint, width)) {
            ensureSpace(lineHeight);
            canvas.drawText(line, x, y - paint.ascent(), paint);
            y += lineHeight;
        }
    }

    /**
     * One table row: each cell wraps within its own column and the row is as tall as its tallest
     * cell. A row is never split across pages — if it doesn't fit, it moves to the next one whole.
     *
     * @param offsets column left edges, relative to the left margin
     * @param widths  column widths
     */
    void row(String[] cells, float[] offsets, float[] widths, Paint[] paints) {
        List<List<String>> wrapped = new ArrayList<>();
        int maxLines = 1;
        float lineHeight = 0;
        for (int i = 0; i < cells.length; i++) {
            List<String> lines = wrap(cells[i], paints[i], widths[i]);
            wrapped.add(lines);
            maxLines = Math.max(maxLines, lines.size());
            lineHeight = Math.max(lineHeight, lineHeight(paints[i]));
        }
        ensureSpace(maxLines * lineHeight);
        for (int i = 0; i < cells.length; i++) {
            float lineY = y;
            for (String line : wrapped.get(i)) {
                canvas.drawText(line, MARGIN + offsets[i], lineY - paints[i].ascent(), paints[i]);
                lineY += lineHeight(paints[i]);
            }
        }
        y += maxLines * lineHeight;
    }

    private static float lineHeight(Paint paint) {
        return paint.getFontSpacing() + 2;
    }

    /** Greedy word wrap; a single word wider than the column is broken mid-word rather than overflowing it. */
    static List<String> wrap(String text, Paint paint, float width) {
        List<String> lines = new ArrayList<>();
        if (text == null) text = "";
        for (String paragraph : text.split("\\r?\\n", -1)) {
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (paint.measureText(candidate) <= width) {
                    current.setLength(0);
                    current.append(candidate);
                    continue;
                }
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                String rest = word;
                while (paint.measureText(rest) > width) {
                    int fit = paint.breakText(rest, true, width, null);
                    if (fit <= 0) fit = 1;
                    lines.add(rest.substring(0, fit));
                    rest = rest.substring(fit);
                }
                current.append(rest);
            }
            lines.add(current.toString());
        }
        return lines;
    }

    /** Finishes the last page and writes the document to {@code file}. */
    File write(File file) throws IOException {
        finishPage();
        try (FileOutputStream out = new FileOutputStream(file)) {
            document.writeTo(out);
        } finally {
            document.close();
        }
        return file;
    }
}
