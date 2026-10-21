package ru.big.town.anative;

import android.os.Bundle;
import android.view.ViewTreeObserver;

/** Standalone CarPlay with ordinary app insets and visible OEM panels. */
public final class CarPlayWindowedActivity extends SplitHostActivity {
    private final ViewTreeObserver.OnGlobalLayoutListener displayListener =
            this::reportCarPlayHostDisplay;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(displayListener);
    }

    @Override
    protected void onDestroy() {
        getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(displayListener);
        super.onDestroy();
    }
}
