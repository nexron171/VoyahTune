package com.qinggan.app.qgime;

/** Synthetic IME surface for keyboard_ru.js hook assignment. */
public class QingganIME {
    public final SkbContainer mSkbContainer = new SkbContainer();
    public final InputModeSwitcher mInputModeSwitcher = InputModeSwitcher.getInstance();

    public void responseSoftKeyEvent(SoftKey softKey) {
        // Stub.
    }

    public void resetToIdleState(boolean resetCandidates) {
        // Stub.
    }
}
