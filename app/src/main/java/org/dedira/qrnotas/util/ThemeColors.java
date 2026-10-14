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
import android.util.TypedValue;

/**
 * Resolves a Material theme attribute (e.g. {@code R.attr.colorTertiary}, {@code
 * R.attr.colorSuccess}) to its actual color value for the app's current theme (light or dark).
 * Needed because these attributes aren't plain color resources — they only resolve to a real
 * color once looked up against the active theme at runtime. Shared by every screen (Activity or
 * Fragment) that needs a theme-aware color, instead of each one duplicating this lookup.
 */
public class ThemeColors {

    private ThemeColors() {
    }

    public static int resolve(Context context, int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }
}
