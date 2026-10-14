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
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;

/**
 * QrGrades' rule for which feedback mechanism to use, driven by reversibility and whether the
 * user has something to act on — not by habit or whatever was closest at hand when a screen was
 * written. Pick one of the three below; don't invent a fourth.
 * <p>
 * <b>Toast</b> ({@link #info}) — informational, no action possible or needed, auto-dismisses.
 * "Points not saved", "Student not found", "Export failed". The overwhelming majority of the
 * app's status/failure notices fit here: there's nothing for the teacher to *do* in response
 * beyond noting it.
 * <p>
 * <b>Snackbar</b> ({@link #actionable}) — reserved <i>exclusively</i> for feedback that offers a
 * genuine follow-up action (undo, "select manually", "cancel"). A Snackbar's whole reason to
 * exist over a Toast is its action button; if there's no action, it's a Toast instead. Don't
 * reach for Snackbar just because a screen happens to have a convenient anchor view.
 * <p>
 * <b>AlertDialog / a dedicated custom-layout dialog</b> (not wrapped here — too varied to usefully
 * wrap behind one call) — destructive, irreversible, or requires an explicit decision before
 * proceeding: delete confirmations, the close-period archive/reassign choice, import summaries,
 * the not-enrolled enrollment prompt. {@code dialog_close_class_period.xml} is the template for
 * what a dialog needing more than title+message+2 buttons should look like.
 */
public class Feedback {

    private Feedback() {
    }

    public static void info(Context context, int messageRes) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show();
    }

    public static void info(Context context, CharSequence message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static void actionable(View anchor, int messageRes, int actionRes, Runnable action) {
        Snackbar.make(anchor, messageRes, Snackbar.LENGTH_LONG)
                .setAction(actionRes, v -> action.run())
                .show();
    }
}
