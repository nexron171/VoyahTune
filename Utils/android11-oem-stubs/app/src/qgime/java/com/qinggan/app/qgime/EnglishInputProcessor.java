package com.qinggan.app.qgime;

import android.view.inputmethod.InputConnection;

/** Synthetic input processor surface for keyboard_ru.js hook assignment. */
public class EnglishInputProcessor {
    public int mLastKeyCode;

    public boolean processKey(
            InputConnection connection,
            SoftKey key,
            boolean shift,
            boolean commit
    ) {
        mLastKeyCode = key == null ? 0 : key.getKeyCode();
        return false;
    }
}
