package ru.big.town.restoremode.widgets.media;

/** Approved sizes for the current-player card. */
public final class MediaWidgetLayout {
    public static final String MEDIA = "mediaWidget";

    public static final int DEFAULT_WIDTH = 3;
    public static final int DEFAULT_HEIGHT = 5;

    private static final int MIN_WIDTH = 1;
    private static final int MAX_WIDTH = 4;
    private static final int NARROW_WIDTH = 2;
    private static final int NARROW_MAX_HEIGHT = 2;
    private static final int MAX_HEIGHT = 5;

    private MediaWidgetLayout() {}

    public static boolean isWidget(String id) {
        return MEDIA.equals(id);
    }

    public static int minWidth() {
        return MIN_WIDTH;
    }

    public static int maxWidth() {
        return MAX_WIDTH;
    }

    public static int minHeight(int columns) {
        return 1;
    }

    public static int maxHeight(int columns) {
        if (columns <= MIN_WIDTH) {
            return 1;
        }
        return columns <= NARROW_WIDTH ? NARROW_MAX_HEIGHT : MAX_HEIGHT;
    }

    public static int width(int value) {
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, value));
    }

    public static int height(int columns, int value) {
        int clampedColumns = width(columns);
        return Math.max(minHeight(clampedColumns), Math.min(maxHeight(clampedColumns), value));
    }
}
