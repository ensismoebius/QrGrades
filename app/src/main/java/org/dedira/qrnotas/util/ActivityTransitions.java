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

import android.app.Activity;

// MaterialSharedAxis is a Material Design "shared axis" transition: the outgoing screen slides
// out along one axis (here the Z axis, i.e. it looks like it moves away from the viewer) while
// the incoming screen slides in, giving a sense of forward/backward navigation depth.
import com.google.android.material.transition.platform.MaterialSharedAxis;

/**
 * A tiny helper that wires up matching "shared axis" screen-transition animations between two
 * activities. In Android, each screen is usually its own {@link Activity}; when you navigate from
 * one to another, the OS lets you customize how the old screen exits and the new one enters. This
 * class centralizes that setup so every part of the app gets the same consistent forward/back
 * navigation feel instead of each screen configuring animations by hand.
 * Usage: call {@link #forward(Activity)} on the screen that starts the navigation (right before
 * calling startActivity/finish), and call {@link #enter(Activity)} on the screen being opened
 * (typically in onCreate, before setContentView/super.onCreate depending on the theme setup).
 */
public class ActivityTransitions {

    // Private constructor: this class only has static helper methods, so it is never meant to be
    // instantiated (there is no per-object state to hold).
    private ActivityTransitions() {
    }

    /** Call on the launching activity when it pushes a new screen forward (Z axis). */
    public static void forward(Activity activity) {
        activity.getWindow().setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, true));
        activity.getWindow().setReenterTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, false));
    }

    /** Call on the launched activity to match a forward(Activity) transition on the caller. */
    public static void enter(Activity activity) {
        activity.getWindow().setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, true));
        activity.getWindow().setReturnTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, false));
    }
}
