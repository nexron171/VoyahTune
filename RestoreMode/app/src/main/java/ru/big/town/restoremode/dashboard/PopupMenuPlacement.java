package ru.big.town.restoremode.dashboard;

import android.graphics.Rect;

/** Fits a popup into the visible window, preferring space below its anchor. */
final class PopupMenuPlacement {
    private PopupMenuPlacement() {}

    static Rect fit(Rect available, Rect anchor, int desiredWidth, int desiredHeight, int gap) {
        int width = Math.min(desiredWidth, Math.max(0, available.width()));
        int height = Math.min(desiredHeight, Math.max(0, available.height()));
        int left = clamp(anchor.left, available.left, available.right - width);
        int top;
        if (anchor.bottom + gap + height <= available.bottom) {
            top = Math.max(available.top, anchor.bottom + gap);
        } else if (anchor.top - gap - height >= available.top) {
            top = anchor.top - gap - height;
        } else {
            top = clamp(anchor.bottom + gap, available.top, available.bottom - height);
        }
        top = clamp(top, available.top, available.bottom - height);
        return new Rect(left, top, left + width, top + height);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
