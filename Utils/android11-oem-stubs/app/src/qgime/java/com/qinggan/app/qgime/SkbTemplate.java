package com.qinggan.app.qgime;

import android.graphics.drawable.Drawable;

/** Inert template returned by the synthetic keyboard pool. */
public class SkbTemplate {
    public SoftKey getDefaultKey(int id) {
        return new SoftKey();
    }

    public int getKeyType(int id) {
        return id;
    }

    public Drawable getDefaultKeyIcon(int keyCode) {
        return null;
    }

    public Drawable getDefaultKeyIconPopup(int keyCode) {
        return null;
    }
}
