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

package org.dedira.qrnotas.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import org.dedira.qrnotas.R;

/**
 * A very small, reusable "please wait" overlay dialog.
 * <p>
 * In Android, a {@link Dialog} is a floating window drawn on top of the current
 * screen. This subclass configures itself to look like a spinner/loading overlay:
 * no title bar, a transparent background (so only whatever is inside the
 * {@code R.layout.dialog_loading} layout is visible, e.g. a progress spinner),
 * and a slight dimming of the screen behind it.
 * <p>
 * Typical usage elsewhere in the app: create an instance, call {@code show()}
 * before starting a slow operation (like a network or database call), and call
 * {@code dismiss()} when it finishes, so the user knows the app is working.
 */
public class LoadingDialog extends Dialog {
    // The Context is kept so it can be used later in onCreate() to inflate the
    // layout and fetch system services (the constructor's Context is not saved
    // automatically by the base Dialog class in a way we can reuse here).
    private final Context mContext;

    public LoadingDialog(Context context) {
        super(context);
        mContext = context;
    }

    /**
     * Called by the Android framework the first time this dialog's window is
     * about to be displayed. This is where we build up the dialog's look:
     * remove the title bar, inflate the XML layout that defines what the
     * dialog shows, and tweak the window's background/animation.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Hide the default dialog title bar since this is just a loading overlay.
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // LayoutInflater turns an XML layout resource (dialog_loading.xml) into
        // real View objects we can display. We ask the system for the shared
        // inflater service rather than constructing one ourselves.
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        // The FrameLayout passed here is only used as a temporary parent so the
        // inflater can resolve layout params correctly; "false" means it is not
        // actually attached to that parent, we attach it to the dialog below.
        View inflateView = inflater.inflate(R.layout.dialog_loading, new FrameLayout(mContext), false);
        setContentView(inflateView);

        Window window = getWindow();
        if (window != null) {
            // Make the window's background fully transparent so only the inflated
            // layout (e.g. a spinner graphic) is visible, not a default white box.
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Slightly darken the rest of the screen behind the dialog (30% dim)
            // to draw attention to the loading indicator.
            window.setDimAmount(0.3f);
            // Apply a custom show/hide animation defined in the app's styles.
            window.getAttributes().windowAnimations = R.style.Animation_QrGrades_LoadingDialog;
        }
    }

}
