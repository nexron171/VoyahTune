package ru.big.town.restoremode.appearance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WallpaperCrossfadeTest {
    @Test
    public void blendsOldAndNewWallpaperWithoutFadingThroughBlack() {
        WallpaperDrawable wallpaper = new WallpaperDrawable(solid(Color.RED));
        wallpaper.transitionTo(solid(Color.BLUE));
        assertEquals(Color.RED, pixel(wallpaper));
        wallpaper.setProgress(.5f);
        int middle = pixel(wallpaper);
        assertTrue(Color.red(middle) >= 120 && Color.red(middle) <= 135);
        assertTrue(Color.blue(middle) >= 120 && Color.blue(middle) <= 135);
        assertEquals(255, Color.alpha(middle));
        wallpaper.setProgress(1f);
        assertEquals(Color.BLUE, pixel(wallpaper));
    }

    @Test
    public void interruptedTransitionStartsFromTheVisibleFrame() {
        WallpaperDrawable wallpaper = new WallpaperDrawable(solid(Color.RED));
        wallpaper.transitionTo(solid(Color.BLUE));
        wallpaper.setProgress(.4f);
        int visible = pixel(wallpaper);
        wallpaper.transitionTo(solid(Color.GREEN));
        assertEquals(visible, pixel(wallpaper));
        wallpaper.setProgress(1f);
        assertEquals(Color.GREEN, pixel(wallpaper));
    }

    @Test
    public void blackBackgroundParticipatesInCrossfade() {
        WallpaperDrawable wallpaper = new WallpaperDrawable(solid(Color.WHITE));
        wallpaper.transitionTo(null);
        wallpaper.setProgress(.5f);
        int middle = pixel(wallpaper);
        assertTrue(Color.red(middle) >= 120 && Color.red(middle) <= 135);
        wallpaper.setProgress(1f);
        assertEquals(Color.BLACK, pixel(wallpaper));
    }

    private static Bitmap solid(int color) {
        Bitmap bitmap = Bitmap.createBitmap(16, 6, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private static int pixel(WallpaperDrawable wallpaper) {
        Bitmap bitmap = Bitmap.createBitmap(16, 6, Bitmap.Config.ARGB_8888);
        wallpaper.setBounds(0, 0, 16, 6);
        wallpaper.draw(new Canvas(bitmap));
        return bitmap.getPixel(8, 3);
    }
}
