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
import android.view.View;
import android.view.inputmethod.InputMethodManager;

/**
 * Autofocuses a screen/dialog's primary text field and opens the keyboard, so the teacher can
 * start typing right away. Android doesn't do this automatically for you — a view has to
 * explicitly request focus, and the soft (on-screen) keyboard has to be explicitly shown via the
 * {@link InputMethodManager} system service.
 */
public final class KeyboardUtils {

    // Static-only helper class; never meant to be instantiated.
    private KeyboardUtils() {
    }

    /** Gives {@code view} keyboard focus and pops up the on-screen keyboard for it. */
    public static void focusAndShowKeyboard(View view) {
        view.requestFocus();
        // view.post() queues the given action to run after the current layout pass finishes.
        // Showing the keyboard immediately (before the view is fully laid out/attached) can
        // silently fail, so this defers it to the next UI frame when the view is definitely ready.
        view.post(() -> {
            InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            // SHOW_IMPLICIT means "show unless the user explicitly hid the keyboard before" —
            // it's the polite, non-forceful way to request the keyboard.
            if (imm != null) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        });
    }
}
