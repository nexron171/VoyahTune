package ru.big.town.anative;

/** OEM dimmer modes use screen flags, independently of brightness percentages. */
final class ScreenBrightnessModeController {
    static final String RESTORE_PENDING = "screenBrightnessRestoreAutoPending";
    static final int DAY = 1;
    static final int AUTO = 2;

    interface Modes {
        int readMode(int screen) throws Exception;

        boolean writeMode(int screen, int mode) throws Exception;
    }

    static boolean apply(Modes modes, int mode, java.util.function.BooleanSupplier current)
            throws Exception {
        boolean confirmed = true;
        for (int screen : new int[] {1, 2, 16}) {
            if (!current.getAsBoolean()) return false;
            if (modes.readMode(screen) != mode) {
                if (!current.getAsBoolean()) return false;
                if (!modes.writeMode(screen, mode)) confirmed = false;
            }
            if (!current.getAsBoolean()) return false;
            if (modes.readMode(screen) != mode) confirmed = false;
        }
        return confirmed;
    }
}
