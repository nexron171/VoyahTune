package com.qinggan.app.qgime;

import android.graphics.drawable.Drawable;

/** Minimal synthetic key object; behavior is intentionally inert. */
public class SoftKey {
    public int mKeyCode;
    public CharSequence mKeyLabel;

    public SoftKey() {
    }

    public int getKeyCode() {
        return mKeyCode;
    }

    public void changeCase(boolean upperCase) {
        // Stub.
    }

    public void setKeyAttribute(int code, CharSequence label, boolean repeat, boolean balloon) {
        mKeyCode = code;
        mKeyLabel = label;
    }

    public void setPopupSkbId(int id) {
        // Stub.
    }

    public void setKeyType(int type, Drawable icon, Drawable popupIcon) {
        // Stub.
    }

    public void setKeyDimensions(int left, int top, int right, int bottom) {
        // Stub.
    }

    public void setSkbCoreSize(int width, int height) {
        // Stub.
    }
}
