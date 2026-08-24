package com.qinggan.app.qgime;

import android.content.Context;

import java.util.Vector;

/** Synthetic keyboard cache surface used by both keyboard agents. */
public class SkbPool {
    private static final SkbPool INSTANCE = new SkbPool();

    public final Vector<SoftKeyboard> mSoftKeyboards = new Vector<>();

    public static SkbPool getInstance() {
        return INSTANCE;
    }

    public SoftKeyboard getSoftKeyboard(
            int cacheId,
            int xmlId,
            int width,
            int height,
            Context context
    ) {
        SoftKeyboard keyboard = new SoftKeyboard();
        keyboard.setCacheId(cacheId);
        return keyboard;
    }

    public SkbTemplate getSkbTemplate(int templateId, Context context) {
        return new SkbTemplate();
    }

    public void resetCachedSkb() {
        mSoftKeyboards.clear();
    }
}
