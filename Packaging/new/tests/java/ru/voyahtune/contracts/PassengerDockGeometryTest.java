package ru.voyahtune.contracts;

import ru.voyahtune.agent.policy.PassengerDockGeometry;

import java.util.Arrays;

public final class PassengerDockGeometryTest {
    public static void main(String[] arguments) {
        int[] heights = {90, 90, 90, 90, 90, 90};
        expect(720, 720, heights, true, true, false, 26, 141, 257, 373, 489, 604);
        expect(720, 720, heights, true, false, false, 84, 199, -1, 315, 431, 546);
        expect(720, 720, heights, false, true, false, 84, -1, 199, 315, 431, 546);
        expect(720, 720, heights, false, false, false, 141, -1, -1, 257, 373, 489);
        for (boolean first : new boolean[] {false, true}) {
            for (boolean second : new boolean[] {false, true}) {
                expect(720, 560, heights, first, second, true, 61, -1, -1, 177, 293, 409);
            }
        }
        expect(720, 720, heights, true, true, false, 26, 141, 257, 373, 489, 604);
        expect(
                1440,
                1120,
                new int[] {180, 180, 180, 180, 180, 180},
                true,
                true,
                true,
                123,
                -1,
                -1,
                354,
                586,
                817);
        expect(
                720,
                720,
                new int[] {80, 100, 90, 80, 100, 90},
                true,
                true,
                false,
                26,
                131,
                257,
                373,
                479,
                604);
        expect(540, 540, heights, true, true, false, 0, 90, 180, 270, 360, 450);
        rejects(() -> PassengerDockGeometry.positions(0, 560, heights, true, true, true));
        rejects(() -> PassengerDockGeometry.positions(720, 0, heights, true, true, true));
        rejects(() -> PassengerDockGeometry.positions(500, 500, heights, true, true, false));
        rejects(() -> PassengerDockGeometry.positions(720, 300, heights, true, true, true));
        rejects(() -> PassengerDockGeometry.positions(720, 720, new int[6], true, true, false));
        rejects(() -> PassengerDockGeometry.positions(720, 720, new int[] {90}, true, true, false));
        System.out.println("PassengerDockGeometryTest passed");
    }

    private static void expect(
            int raisedHeight,
            int availableHeight,
            int[] heights,
            boolean first,
            boolean second,
            boolean lowered,
            int... expected) {
        int[] actual =
                PassengerDockGeometry.positions(
                        raisedHeight, availableHeight, heights, first, second, lowered);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(Arrays.toString(actual) + " != " + Arrays.toString(expected));
        }
        for (int index = 0; index < actual.length; index++) {
            if (actual[index] >= 0 && actual[index] + heights[index] > availableHeight) {
                throw new AssertionError("Button exceeds the available panel height");
            }
        }
        if (Math.abs(actual[0] - (availableHeight - actual[5] - heights[5])) > 1) {
            throw new AssertionError("Visible buttons are not vertically centered");
        }
    }

    private static void rejects(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Invalid dimensions were accepted");
        } catch (IllegalArgumentException expected) {
            // Unmeasured or unsupported OEM dimensions cannot produce a valid placement.
        }
    }
}
