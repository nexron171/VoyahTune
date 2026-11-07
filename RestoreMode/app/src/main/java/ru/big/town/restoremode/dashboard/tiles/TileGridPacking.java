package ru.big.town.restoremode.dashboard.tiles;

import java.util.List;

/** Shared column-first packing for the dashboard and its drop previews. */
public final class TileGridPacking {
    private TileGridPacking() {}

    public static int rowsForHeight(int availableHeight, int raisedHeight, int fullRows) {
        if (availableHeight <= 0 || raisedHeight <= 0 || fullRows <= 0) {
            throw new IllegalArgumentException("Invalid grid height");
        }
        return Math.max(
                1,
                Math.min(fullRows, Math.round((float) availableHeight * fullRows / raisedHeight)));
    }

    public static int[] place(List<boolean[]> occupied, int rows, int width, int height) {
        if (width < 1 || height < 1 || height > rows) {
            throw new IllegalArgumentException("Invalid tile span");
        }
        for (int column = 0; ; column++) {
            while (occupied.size() < column + width) {
                occupied.add(new boolean[rows]);
            }
            for (int row = 0; row <= rows - height; row++) {
                boolean free = true;
                for (int x = column; x < column + width && free; x++) {
                    for (int y = row; y < row + height; y++) {
                        if (occupied.get(x)[y]) {
                            free = false;
                            break;
                        }
                    }
                }
                if (!free) {
                    continue;
                }
                for (int x = column; x < column + width; x++) {
                    for (int y = row; y < row + height; y++) {
                        occupied.get(x)[y] = true;
                    }
                }
                return new int[] {column, row};
            }
        }
    }
}
