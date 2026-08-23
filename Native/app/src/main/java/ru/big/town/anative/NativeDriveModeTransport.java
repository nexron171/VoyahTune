package ru.big.town.anative;

import android.util.Log;

/** Native-only drive-mode transport serialized with every other libqg_hal write. */
final class NativeDriveModeTransport {
    private static final String TAG = "$$$ NativeDriveModeTransport $$$";

    private NativeDriveModeTransport() {}

    static boolean send(String mode) {
        byte[][] frames = NativeDriveModeFrames.forMode(mode);
        if (frames.length == 0) {
            Log.e(TAG, "Unsupported drive mode: " + mode);
            return false;
        }
        return CanSender.send(1, frames, "drive mode: " + mode);
    }
}
