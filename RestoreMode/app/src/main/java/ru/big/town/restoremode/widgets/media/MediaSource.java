package ru.big.town.restoremode.widgets.media;

import android.graphics.Bitmap;

/** Launchable media application offered by the card's in-place source picker. */
final class MediaSource {
    final String packageName;
    final String label;
    final Bitmap icon;

    MediaSource(String packageName, String label, Bitmap icon) {
        this.packageName = packageName == null ? "" : packageName;
        this.label = label == null ? "" : label;
        this.icon = icon;
    }
}
