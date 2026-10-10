package ru.big.town.restoremode.dashboard.tiles;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import ru.big.town.restoremode.dashboard.tiles.TileScrollSnap.Mode;

public class TileScrollSnapTest {

    private static final int PITCH = 200;
    private static final int COLUMNS = 12;
    private static final int PAGE = PITCH * COLUMNS;
    private static final int MAX = 5000;

    /** Общая цель без якоря жеста: режимы OFF/COLUMN/PAGE; TILE центрируется отдельно. */
    private static int target(int scrollX, Mode mode) {
        return TileScrollSnap.target(scrollX, -1, PITCH, COLUMNS, MAX, mode);
    }

    @Test
    public void columnModeRoundsToNearestBoundary() {
        assertEquals(400, target(390, Mode.COLUMN));
        assertEquals(600, target(510, Mode.COLUMN));
        assertEquals(0, target(90, Mode.COLUMN));
    }

    @Test
    public void columnModeKeepsAlignedPosition() {
        assertEquals(600, target(600, Mode.COLUMN));
    }

    @Test
    public void columnModeClampsToMaxScroll() {
        assertEquals(0, target(0, Mode.COLUMN));
        // Округление вверх выходит за предел прокрутки — остаёмся на пределе.
        assertEquals(4990, TileScrollSnap.target(4990, -1, PITCH, COLUMNS, 4990, Mode.COLUMN));
    }

    @Test
    public void offModeDoesNotMove() {
        assertEquals(1234, target(1234, Mode.OFF));
    }

    @Test
    public void tileModeWithoutGeometryDoesNotMove() {
        // Выравнивание по краю плитки требует её координат и считается через tileLeftEdge().
        assertEquals(1234, target(1234, Mode.TILE));
    }

    @Test
    public void tileLeftEdgeAlignsLeftmostTileToLeft() {
        int[] lefts = {10, 610, 1210};
        assertEquals(610, TileScrollSnap.tileLeftEdge(700, lefts, MAX));
        assertEquals(10, TileScrollSnap.tileLeftEdge(100, lefts, MAX));
    }

    @Test
    public void tileLeftEdgeKeepsRestPositionAtGridStart() {
        int[] lefts = {10, 610};
        assertEquals(0, TileScrollSnap.tileLeftEdge(0, lefts, MAX));
        assertEquals(0, TileScrollSnap.tileLeftEdge(5, lefts, MAX));
    }

    @Test
    public void tileLeftEdgeClampsToScrollRange() {
        int[] lefts = {10, 610, 4000};
        assertEquals(3000, TileScrollSnap.tileLeftEdge(5000, lefts, 3000));
    }

    @Test
    public void tileLeftEdgeWithoutTilesStaysAtZero() {
        assertEquals(0, TileScrollSnap.tileLeftEdge(500, new int[0], MAX));
        assertEquals(0, TileScrollSnap.tileLeftEdge(500, null, MAX));
    }

    @Test
    public void pageModeWithoutAnchorRoundsToNearestPage() {
        assertEquals(PAGE, target(1300, Mode.PAGE));
        assertEquals(0, target(1100, Mode.PAGE));
        assertEquals(PAGE * 2, target(3600, Mode.PAGE));
    }

    @Test
    public void pageModeAdvancesOnLightMovement() {
        // Лёгкое движение вправо с выровненного нуля сразу даёт следующую страницу.
        assertEquals(PAGE, TileScrollSnap.target(30, 0, PITCH, COLUMNS, MAX, Mode.PAGE));
        // Обратно — предыдущая страница.
        assertEquals(0, TileScrollSnap.target(2380, PAGE, PITCH, COLUMNS, MAX, Mode.PAGE));
        // Лёгкое движение влево с нуля упирается в начало.
        assertEquals(0, TileScrollSnap.target(-30, 0, PITCH, COLUMNS, MAX, Mode.PAGE));
    }

    @Test
    public void pageModeKeepsNearestPageAfterLongSwipe() {
        assertEquals(PAGE * 2, TileScrollSnap.target(3700, 0, PITCH, COLUMNS, MAX, Mode.PAGE));
        assertEquals(0, TileScrollSnap.target(1000, PAGE * 2, PITCH, COLUMNS, MAX, Mode.PAGE));
    }

    @Test
    public void pageModeClampsToMaxScroll() {
        assertEquals(2000, TileScrollSnap.target(2000, -1, PITCH, COLUMNS, 2000, Mode.PAGE));
    }

    @Test
    public void noScrollableGridStaysAtZero() {
        assertEquals(0, TileScrollSnap.target(0, -1, PITCH, COLUMNS, 0, Mode.COLUMN));
        assertEquals(0, TileScrollSnap.target(0, -1, PITCH, COLUMNS, 0, Mode.PAGE));
        assertEquals(0, TileScrollSnap.tileLeftEdge(0, new int[] {10}, 0));
    }

    @Test
    public void invalidPitchOrModeDoesNotMove() {
        assertEquals(0, TileScrollSnap.target(0, -1, 0, COLUMNS, MAX, Mode.COLUMN));
        assertEquals(250, TileScrollSnap.target(250, -1, PITCH, COLUMNS, MAX, null));
    }

    @Test
    public void unknownKeyFallsBackToDefault() {
        assertEquals(Mode.TILE, TileScrollSnap.fromKey(null));
        assertEquals(Mode.TILE, TileScrollSnap.fromKey("unknown"));
        assertEquals(Mode.OFF, TileScrollSnap.fromKey("off"));
        assertEquals(Mode.COLUMN, TileScrollSnap.fromKey("column"));
        assertEquals(Mode.PAGE, TileScrollSnap.fromKey("page"));
        assertEquals(Mode.TILE, TileScrollSnap.fromKey("tile"));
    }

    @Test
    public void keysLabelsAndModesStayInSync() {
        assertEquals(TileScrollSnap.MODES.length, TileScrollSnap.KEYS.length);
        assertEquals(TileScrollSnap.MODES.length, TileScrollSnap.LABELS.length);
        for (int i = 0; i < TileScrollSnap.MODES.length; i++) {
            assertEquals(TileScrollSnap.MODES[i], TileScrollSnap.fromKey(TileScrollSnap.KEYS[i]));
            assertEquals(i, TileScrollSnap.indexOf(TileScrollSnap.MODES[i]));
            assertEquals(TileScrollSnap.KEYS[i], TileScrollSnap.key(TileScrollSnap.MODES[i]));
        }
        assertEquals(
                TileScrollSnap.indexOf(TileScrollSnap.DEFAULT_MODE),
                TileScrollSnap.indexOf(null));
    }
}
