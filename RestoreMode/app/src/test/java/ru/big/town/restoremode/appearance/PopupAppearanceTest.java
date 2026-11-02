package ru.big.town.restoremode.appearance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PopupAppearanceTest {
    @Test
    public void popupUsesConfiguredColorAndTransparency() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("popup-color", Context.MODE_PRIVATE);
        preferences.edit().clear()
                .putInt(AppearanceSettings.BACKGROUND_COLOR, 0xff2468ac)
                .putBoolean(AppearanceSettings.TRANSPARENCY_ENABLED, true)
                .putInt(AppearanceSettings.TRANSPARENCY_PERCENT, 60).commit();
        View scene = new View(context);
        scene.layout(0, 0, 100, 100);
        try (DashboardAppearance appearance = new DashboardAppearance(scene, preferences)) {
            View popup = new View(context);
            popup.layout(0, 0, 100, 100);
            Runnable release = appearance.stylePopup(popup);
            int pixel = draw(popup).getPixel(50, 50);
            assertEquals(102, Color.alpha(pixel));
            // Premultiplied bitmap channels lose precision at partial alpha.
            assertEquals(0x24, Color.red(pixel), 2);
            assertEquals(0x68, Color.green(pixel), 2);
            assertEquals(0xac, Color.blue(pixel), 2);
            assertEquals(1f, popup.getAlpha(), 0f);
            release.run();
            assertNull(popup.getBackground());

            preferences.edit().putBoolean(AppearanceSettings.TRANSPARENCY_ENABLED, false).commit();
            release = appearance.stylePopup(popup);
            assertEquals(0xff2468ac, draw(popup).getPixel(50, 50));
            release.run();
        }
    }

    @Test
    public void popupBlursSceneContentEvenOnBlackWallpaper() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("popup-blur", Context.MODE_PRIVATE);
        preferences.edit().clear()
                .putBoolean(AppearanceSettings.TRANSPARENCY_ENABLED, true)
                .putInt(AppearanceSettings.TRANSPARENCY_PERCENT, 100)
                .putBoolean(AppearanceSettings.BLUR_ENABLED, true)
                .putInt(AppearanceSettings.BLUR_AMOUNT, 50).commit();
        View scene = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.BLACK);
                Paint paint = new Paint();
                paint.setColor(Color.WHITE);
                canvas.drawRect(50, 0, 100, 100, paint);
            }
        };
        scene.layout(0, 0, 100, 100);
        try (DashboardAppearance appearance = new DashboardAppearance(scene, preferences)) {
            View popup = new View(context);
            popup.layout(0, 0, 100, 100);
            Runnable release = appearance.stylePopup(popup);
            int pixel = draw(popup).getPixel(48, 50);
            assertTrue(Color.red(pixel) > 0 && Color.red(pixel) < 255);
            assertEquals(255, Color.alpha(pixel));
            release.run();

            preferences.edit().putBoolean(AppearanceSettings.BLUR_ENABLED, false).commit();
            release = appearance.stylePopup(popup);
            assertEquals(0, Color.alpha(draw(popup).getPixel(48, 50)));
            release.run();
        }
    }

    private static Bitmap draw(View view) {
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }
}
