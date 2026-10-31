package ru.big.town.restoremode.appearance;

import android.graphics.Bitmap;

/** Blur a reduced wallpaper once; widget content never enters the bitmap. */
final class WallpaperBlur {
    private WallpaperBlur() {}

    static Bitmap create(Bitmap source, int amount) {
        if (amount <= 0) {
            return source;
        }
        int width = Math.max(1, source.getWidth() / 4);
        int height = Math.max(1, source.getHeight() / 4);
        Bitmap reduced = Bitmap.createScaledBitmap(source, width, height, true);
        int[] pixels = new int[width * height];
        int[] scratch = new int[pixels.length];
        reduced.getPixels(pixels, 0, width, 0, 0, width, height);
        int radius = Math.max(1, Math.min(20, Math.round(amount / 5f)));
        for (int pass = 0; pass < 3; pass++) {
            blurRows(pixels, scratch, width, height, radius);
            blurRows(scratch, pixels, height, width, radius);
        }
        Bitmap result = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        if (reduced != source) {
            reduced.recycle();
        }
        return result;
    }

    private static void blurRows(int[] source, int[] target, int width, int height, int radius) {
        int samples = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int x = -radius; x <= radius; x++) {
                int color = source[y * width + Math.max(0, Math.min(width - 1, x))];
                red += (color >> 16) & 255;
                green += (color >> 8) & 255;
                blue += color & 255;
            }
            for (int x = 0; x < width; x++) {
                target[x * height + y] = 0xff000000
                        | ((red / samples) << 16)
                        | ((green / samples) << 8)
                        | (blue / samples);
                int leaving = source[y * width + Math.max(0, x - radius)];
                int entering = source[y * width + Math.min(width - 1, x + radius + 1)];
                red += ((entering >> 16) & 255) - ((leaving >> 16) & 255);
                green += ((entering >> 8) & 255) - ((leaving >> 8) & 255);
                blue += (entering & 255) - (leaving & 255);
            }
        }
    }
}
