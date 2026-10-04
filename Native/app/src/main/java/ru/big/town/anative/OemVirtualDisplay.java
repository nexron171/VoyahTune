package ru.big.town.anative;

import android.annotation.SuppressLint;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.view.Surface;

/** Android 11 privileged display contract shared by split, embedded and cluster hosts. */
final class OemVirtualDisplay {
    // Android 11 framework flags omitted from the public SDK @IntDef. Native is a priv-app;
    // ADD_TRUSTED_DISPLAY is installed with its whitelist. Callers retain their untrusted fallback.
    private static final int DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int TRUSTED = 1 << 10;

    private OemVirtualDisplay() { }

    static VirtualDisplay create(DisplayManager manager, String name, int width, int height,
                                 int dpi, Surface surface, boolean trusted) {
        return create(manager, name, width, height, dpi, surface, trusted, null, null);
    }

    @SuppressLint("WrongConstant") // The OEM/Android 11 server accepts the two hidden flags above.
    static VirtualDisplay create(DisplayManager manager, String name, int width, int height,
                                 int dpi, Surface surface, boolean trusted,
                                 VirtualDisplay.Callback callback, Handler handler) {
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | DESTROY_CONTENT_ON_REMOVAL | (trusted ? TRUSTED : 0);
        return manager.createVirtualDisplay(name, width, height, dpi, surface, flags, callback, handler);
    }
}
