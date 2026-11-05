package ru.big.town.restoremode.widgets.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class MediaSourceGridTest {
    @Test
    public void twoSourcesShareOneCentredRow() {
        List<MediaSourceGrid.Cell> cells = MediaSourceGrid.arrange(2, 0f, 0f, 400f, 200f);

        assertEquals(2, cells.size());
        MediaSourceGrid.Cell first = cells.get(0);
        MediaSourceGrid.Cell second = cells.get(1);
        assertEquals(first.right - first.left, second.right - second.left, .01f);
        assertEquals(first.centerY(), second.centerY(), .01f);
        assertTrue(first.right < second.left);
        assertEquals(200f / 2f, first.centerY(), .01f);
        assertTrue(first.left >= 0f && second.right <= 400f);
        assertTrue(first.top >= 0f && first.bottom <= 200f);
    }

    @Test
    public void fiveSourcesWrapIntoRowsThatStayInsideTheArea() {
        List<MediaSourceGrid.Cell> cells = MediaSourceGrid.arrange(5, 10f, 20f, 300f, 300f);

        assertEquals(5, cells.size());
        assertNoOverlap(cells);
        for (MediaSourceGrid.Cell cell : cells) {
            assertTrue(cell.left >= 10f && cell.right <= 310f);
            assertTrue(cell.top >= 20f && cell.bottom <= 320f);
        }
        assertTrue(cells.get(0).centerY() < cells.get(4).centerY());
    }

    @Test
    public void manySourcesShrinkTheIconsUntilEveryRowFits() {
        List<MediaSourceGrid.Cell> cells = MediaSourceGrid.arrange(40, 0f, 0f, 400f, 200f);

        assertEquals(40, cells.size());
        assertNoOverlap(cells);
        float size = cells.get(0).right - cells.get(0).left;
        assertTrue(size < 64f);
        for (MediaSourceGrid.Cell cell : cells) {
            assertTrue(cell.left >= 0f && cell.right <= 400f);
            assertTrue(cell.top >= 0f && cell.bottom <= 200f);
        }
    }

    @Test
    public void emptyOrDegenerateInputProducesNoCells() {
        assertTrue(MediaSourceGrid.arrange(0, 0f, 0f, 200f, 200f).isEmpty());
        assertTrue(MediaSourceGrid.arrange(3, 0f, 0f, 0f, 200f).isEmpty());
        assertTrue(MediaSourceGrid.arrange(3, 0f, 0f, 200f, 0f).isEmpty());
    }

    private static void assertNoOverlap(List<MediaSourceGrid.Cell> cells) {
        for (int first = 0; first < cells.size(); first++) {
            for (int second = first + 1; second < cells.size(); second++) {
                MediaSourceGrid.Cell left = cells.get(first);
                MediaSourceGrid.Cell right = cells.get(second);
                boolean apart =
                        left.right <= right.left
                                || right.right <= left.left
                                || left.bottom <= right.top
                                || right.bottom <= left.top;
                assertTrue(
                        "cells " + first + " and " + second + " overlap",
                        apart);
            }
        }
    }
}
