package ru.big.town.anative;

import android.os.Bundle;

/** A distinct physical host lets the OEM launcher hide its dock only for fullscreen CarPlay. */
public final class CarPlayFullscreenActivity extends SplitHostActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow()
                .getDecorView()
                .getViewTreeObserver()
                .addOnGlobalLayoutListener(this::reportCarPlayHostDisplay);
    }

    @Override
    protected boolean usesFullPhysicalDisplay() {
        return true;
    }
}
