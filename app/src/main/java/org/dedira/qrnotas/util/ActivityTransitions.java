package org.dedira.qrnotas.util;

import android.app.Activity;

import com.google.android.material.transition.platform.MaterialSharedAxis;

public class ActivityTransitions {

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
