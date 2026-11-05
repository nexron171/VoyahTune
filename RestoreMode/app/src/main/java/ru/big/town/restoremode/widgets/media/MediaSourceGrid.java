package ru.big.town.restoremode.widgets.media;

import java.util.ArrayList;
import java.util.List;

/** Square icon cells centred inside the card area, shrinking until every row fits. */
final class MediaSourceGrid {
    private static final float MAX_ICON_SIZE = 64f;
    private static final float MIN_ICON_SIZE = 24f;
    private static final float ICON_STEP = 2f;
    private static final float MIN_GAP = 10f;
    private static final float MAX_GAP = 24f;
    private static final float GAP_RATIO = .28f;
    private static final float MAX_ICON_SHARE = .34f;

    static final class Cell {
        final float left;
        final float top;
        final float right;
        final float bottom;

        Cell(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        float centerX() {
            return (left + right) / 2f;
        }

        float centerY() {
            return (top + bottom) / 2f;
        }

        boolean contains(float x, float y, float margin) {
            return x >= left - margin
                    && x <= right + margin
                    && y >= top - margin
                    && y <= bottom + margin;
        }
    }

    private MediaSourceGrid() {}

    static List<Cell> arrange(int count, float areaLeft, float areaTop, float width, float height) {
        List<Cell> cells = new ArrayList<>();
        if (count <= 0 || width <= 0f || height <= 0f) {
            return cells;
        }
        float size = Math.min(MAX_ICON_SIZE, Math.min(width, height) * MAX_ICON_SHARE);
        while (size > MIN_ICON_SIZE
                && !fits(count, size, gapFor(size), width, height)) {
            size -= ICON_STEP;
        }
        float gap = gapFor(size);
        int columns = Math.min(count, Math.max(1, (int) ((width + gap) / (size + gap))));
        int rows = (count + columns - 1) / columns;
        float gridHeight = rows * size + (rows - 1) * gap;
        float top = areaTop + (height - gridHeight) / 2f;
        for (int row = 0; row < rows; row++) {
            int first = row * columns;
            int inRow = Math.min(columns, count - first);
            float rowWidth = inRow * size + (inRow - 1) * gap;
            float left = areaLeft + (width - rowWidth) / 2f;
            float rowTop = top + row * (size + gap);
            for (int column = 0; column < inRow; column++) {
                float cellLeft = left + column * (size + gap);
                cells.add(new Cell(cellLeft, rowTop, cellLeft + size, rowTop + size));
            }
        }
        return cells;
    }

    private static float gapFor(float size) {
        return Math.max(MIN_GAP, Math.min(MAX_GAP, size * GAP_RATIO));
    }

    private static boolean fits(int count, float size, float gap, float width, float height) {
        int columns = Math.min(count, Math.max(1, (int) ((width + gap) / (size + gap))));
        int rows = (count + columns - 1) / columns;
        return rows * size + (rows - 1) * gap <= height;
    }
}
