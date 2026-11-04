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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Date;

/**
 * A line chart of a running total over time: one point per entry, joined by a line, with a zero
 * line and the range written at the edges. Drawn by hand because it is a single plain line and
 * needs nothing from a charting library.
 */
public class PointsChartView extends View {

    private long[] times = new long[0];
    private int[] totals = new int[0];
    private String startLabel = "";
    private String endLabel = "";

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path area = new Path();
    private final float density;

    public PointsChartView(Context context) {
        this(context, null);
    }

    public PointsChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        int primary = ThemeColors.resolve(context, com.google.android.material.R.attr.colorPrimary);

        linePaint.setColor(primary);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        areaPaint.setColor((primary & 0x00FFFFFF) | 0x26000000); // the line's colour at 15%
        areaPaint.setStyle(Paint.Style.FILL);

        axisPaint.setColor(ThemeColors.resolve(context, com.google.android.material.R.attr.colorOutline));
        axisPaint.setStrokeWidth(1f * density);

        textPaint.setColor(ThemeColors.resolve(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
        textPaint.setTextSize(11f * density);

        dotPaint.setColor(primary);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    /** {@code times} ascending (ms since the epoch); {@code totals} the running total after each entry. */
    public void setSeries(long[] times, int[] totals) {
        this.times = times;
        this.totals = totals;
        buildLabels();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = Math.round(200 * density);
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = times.length;
        if (n == 0) return;

        float left = 40 * density;
        float right = getWidth() - 12 * density;
        float top = 14 * density;
        float bottom = getHeight() - 24 * density;

        long minT = times[0];
        long maxT = times[n - 1];
        int maxV = 1, minV = 0;
        for (int v : totals) {
            maxV = Math.max(maxV, v);
            minV = Math.min(minV, v);
        }
        float spanT = Math.max(1, maxT - minT);
        float spanV = Math.max(1, maxV - minV);

        float zeroY = bottom - (0 - minV) / spanV * (bottom - top);
        canvas.drawLine(left, zeroY, right, zeroY, axisPaint);
        canvas.drawLine(left, top, left, bottom, axisPaint);

        path.reset();
        area.reset();
        float firstX = 0, lastX = 0, lastY = 0;
        for (int i = 0; i < n; i++) {
            // A single entry has no span of time: centre it.
            float x = n == 1 ? (left + right) / 2f : left + (times[i] - minT) / spanT * (right - left);
            float y = bottom - (totals[i] - minV) / spanV * (bottom - top);
            if (i == 0) {
                path.moveTo(x, y);
                area.moveTo(x, zeroY);
                area.lineTo(x, y);
                firstX = x;
            } else {
                path.lineTo(x, y);
                area.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        area.lineTo(lastX, zeroY);
        area.lineTo(firstX, zeroY);
        area.close();
        canvas.drawPath(area, areaPaint);
        canvas.drawPath(path, linePaint);
        canvas.drawCircle(lastX, lastY, 4 * density, dotPaint);

        // The range, at the edges.
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.valueOf(maxV), left - 6 * density, top + 10 * density, textPaint);
        if (minV < 0) canvas.drawText(String.valueOf(minV), left - 6 * density, bottom, textPaint);
        canvas.drawText("0", left - 6 * density, zeroY + 4 * density, textPaint);

        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(startLabel, left, getHeight() - 6 * density, textPaint);
        if (n > 1) {
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(endLabel, right, getHeight() - 6 * density, textPaint);
        }
    }

    /** The dates under the two ends, worked out once per series rather than on every draw. */
    private void buildLabels() {
        startLabel = "";
        endLabel = "";
        int n = times.length;
        if (n == 0) return;
        java.text.DateFormat date = android.text.format.DateFormat.getMediumDateFormat(getContext());
        java.text.DateFormat clock = android.text.format.DateFormat.getTimeFormat(getContext());
        Date first = new Date(times[0]);
        Date last = new Date(times[n - 1]);
        // Both ends on the same day would read as the same label twice, so add the time of day.
        boolean sameDay = n > 1 && date.format(first).equals(date.format(last));
        startLabel = sameDay ? date.format(first) + " " + clock.format(first) : date.format(first);
        endLabel = sameDay ? date.format(last) + " " + clock.format(last) : date.format(last);
    }
}
