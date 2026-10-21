package ru.big.town.restoremode.widgets.headlights;

public final class HeadlightWidgetLayout {
    public static final String ID = "headlightWidget";
    public static final String VISIBLE = "showHeadlightWidget";
    public static final int DEFAULT_WIDTH = 4;
    public static final int DEFAULT_HEIGHT = 1;

    public enum Size {
        SQUARE(2, 2),
        COMPACT_HORIZONTAL(3, 1),
        WIDE_HORIZONTAL(4, 1),
        COMPACT_VERTICAL(1, 3),
        TALL_VERTICAL(1, 4);

        public final int width;
        public final int height;

        Size(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return width + " × " + height;
        }
    }

    private HeadlightWidgetLayout() {}

    public static int width(int value) {
        return Math.max(1, Math.min(4, value));
    }

    public static Size size(int columns, int rows) {
        switch (width(columns)) {
            case 1:
                return rows <= 3 ? Size.COMPACT_VERTICAL : Size.TALL_VERTICAL;
            case 2:
                return Size.SQUARE;
            case 3:
                return Size.COMPACT_HORIZONTAL;
            default:
                return Size.WIDE_HORIZONTAL;
        }
    }
}
