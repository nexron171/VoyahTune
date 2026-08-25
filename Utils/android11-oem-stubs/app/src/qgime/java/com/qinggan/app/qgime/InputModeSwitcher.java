package com.qinggan.app.qgime;

import android.content.Context;

/** Synthetic field and method surface shared by both keyboard agents. */
public class InputModeSwitcher {
    public static int MODE_SKB_ENGLISH_LOWER = 1;
    public static int MODE_SKB_ENGLISH_UPPER = 2;
    public static int MODE_SKB_ENGLISH_FIRST = 3;
    public static int MODE_HKB_ENGLISH = 4;
    public static int MODE_SKB_SYMBOL1_EN = 5;
    public static int MODE_SKB_SYMBOL2_EN = 6;

    private static final InputModeSwitcher INSTANCE = new InputModeSwitcher();

    public int mInputMode = MODE_SKB_ENGLISH_FIRST;
    public int mInputIcon = 0;
    public Context mImeService;

    public static InputModeSwitcher getInstance() {
        return INSTANCE;
    }

    public int saveInputMode(int mode) {
        mInputMode = mode;
        return mode;
    }

    public int switchModeForUserKey(int keyCode, boolean fromUser) {
        return mInputIcon;
    }

    public int getTooggleStateForCnCand() {
        return 0;
    }

    public int getToggleStates() {
        return 0;
    }

    public boolean isQwertyFirstMode() {
        return true;
    }
}
