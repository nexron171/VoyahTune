package com.qinggan.app.qgime;

import java.util.ArrayList;
import java.util.List;

/** Synthetic keyboard surface sufficient for hook assignment and reflection smoke tests. */
public class SoftKeyboard {
    public final List<KeyRow> mKeyRows = new ArrayList<>();
    public boolean mIsQwertyUpperCase = false;

    private int cacheId;

    public SoftKeyboard() {
    }

    public SoftKeyboard(int xmlId, SkbTemplate template, int width, int height) {
    }

    public int getCacheId() {
        return cacheId;
    }

    public void setCacheId(int value) {
        cacheId = value;
    }

    public void setFlags(int cacheFlag, boolean sticky, boolean qwerty, boolean uppercase) {
        mIsQwertyUpperCase = uppercase;
    }

    public void setKeyMargins(int horizontal, int vertical) {
        // Stub.
    }

    public void beginNewRow(int rowId, int startY) {
        mKeyRows.add(new KeyRow());
    }

    public boolean addSoftKey(SoftKey key) {
        if (mKeyRows.isEmpty()) {
            mKeyRows.add(new KeyRow());
        }
        return mKeyRows.get(mKeyRows.size() - 1).mSoftKeys.add(key);
    }

    public void disableToggleState(int state, boolean reset) {
        // Stub.
    }

    public void enableToggleStates(int states) {
        // Stub.
    }

    public void setSkbCoreSize(int width, int height) {
        // Stub.
    }

    public void setNewlyLoadedFlag(boolean value) {
        // Stub.
    }

    public void switchQwertyMode(int mode, boolean upperCase) {
        mIsQwertyUpperCase = upperCase;
    }

    public static class KeyRow {
        public final List<SoftKey> mSoftKeys = new ArrayList<>();
    }
}
