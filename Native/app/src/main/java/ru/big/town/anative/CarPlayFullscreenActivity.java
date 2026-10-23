package ru.big.town.anative;

import android.os.Bundle;
import android.view.ViewTreeObserver;

/** A distinct physical host lets the OEM launcher hide its dock only for fullscreen CarPlay. */
public final class CarPlayFullscreenActivity extends SplitHostActivity {
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener =
            this::reportCarPlayHostDisplay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BackButtonService.refreshCarPlayNavigation(this);
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    @Override
    protected void onDestroy() {
        getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        super.onDestroy();
    }

    @Override
    protected boolean usesFullPhysicalDisplay() {
        return true;
    }
}
