package com.pateo.material.dialog;

import android.content.Context;

/** Inert toast facade required for keyboard_ru.js hook installation. */
public class QGToast {
    public static QGToast makeText(Context context, CharSequence text, int duration) {
        return new QGToast();
    }

    public void show() {
        // Stub.
    }
}
