package org.dedira.qrnotas.util;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

/** Autofocuses a screen/dialog's primary text field and opens the keyboard, so the teacher can start typing right away. */
public final class KeyboardUtils {

    private KeyboardUtils() {
    }

    public static void focusAndShowKeyboard(View view) {
        view.requestFocus();
        view.post(() -> {
            InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        });
    }
}
