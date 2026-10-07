package ru.big.town.restoremode.dashboard.tiles;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TileGridPackingTest {
    @Test
    public void largeTilesLeaveHolesThatSmallTilesCanFill() {
        List<boolean[]> occupied = new ArrayList<>();
        assertArrayEquals(new int[] {0, 0}, TileGridPacking.place(occupied, 5, 3, 2));
        assertArrayEquals(new int[] {0, 2}, TileGridPacking.place(occupied, 5, 2, 3));
        assertArrayEquals(new int[] {2, 2}, TileGridPacking.place(occupied, 5, 1, 1));
        assertArrayEquals(new int[] {2, 3}, TileGridPacking.place(occupied, 5, 1, 2));
        assertArrayEquals(new int[] {3, 0}, TileGridPacking.place(occupied, 5, 3, 2));
    }

    @Test
    public void reorderedMixedTilesNeverOverlap() {
        int[][] spans = {{3, 2}, {2, 3}, {1, 1}, {2, 1}, {3, 2}};
        for (int insertion = 0; insertion < spans.length; insertion++) {
            List<int[]> order = new ArrayList<>();
            for (int i = 1; i < spans.length; i++) {
                order.add(spans[i]);
            }
            order.add(insertion, spans[0]);
            List<boolean[]> occupied = new ArrayList<>();
            boolean[][] seen = new boolean[20][5];
            for (int[] span : order) {
                int[] cell = TileGridPacking.place(occupied, 5, span[0], span[1]);
                for (int x = cell[0]; x < cell[0] + span[0]; x++) {
                    for (int y = cell[1]; y < cell[1] + span[1]; y++) {
                        assertFalse(seen[x][y]);
                        seen[x][y] = true;
                    }
                }
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void tooTallTileDoesNotLoopForever() {
        TileGridPacking.place(new ArrayList<>(), 5, 2, 6);
    }
}
