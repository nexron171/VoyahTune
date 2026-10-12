package ru.big.town.restoremode.carplay;

/** Remaining OEM occlusion in a window's local coordinates, in physical pixels. */
final class CarPlaySafeArea {
    private CarPlaySafeArea() {}

    static int[] resolve(int displayId, int x, int y, int dock, int status,
            int left, int top, int right, int bottom) {
        if (displayId == 0 || displayId == 1) {
            left = Math.max(left, Math.max(0, dock - x));
            top = Math.max(top, Math.max(0, status - y));
        }
        return new int[] {left, top, right, bottom};
    }
}
