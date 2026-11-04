package ru.big.town.restoremode.appearance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.widget.FrameLayout;

import ru.big.town.restoremode.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppearanceTest {
    private SharedPreferences preferences;

    @Before
    public void clearPreferences() {
        preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("appearance-test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void defaultsKeepOpaqueWidgetsOnBlackWallpaper() {
        AppearanceSettings settings = AppearanceSettings.load(preferences);
        assertFalse(settings.transparencyEnabled);
        assertEquals(50, settings.transparencyPercent);
        assertEquals(255, settings.surfaceAlpha());
        assertFalse(settings.blurEnabled);
        assertEquals(AppearanceSettings.Wallpaper.BLACK, settings.wallpaper);
        assertEquals(4, AppearanceSettings.spacingDp(preferences));
        assertEquals(AppearanceSettings.DEFAULT_BORDER_DP, settings.borderDp);
        assertEquals(AppearanceSettings.DEFAULT_CORNER_RADIUS_DP, settings.cornerRadiusDp);
    }

    @Test
    public void savedValuesSurviveReloadAndInvalidInputIsBounded() {
        preferences.edit()
                .putBoolean(AppearanceSettings.TRANSPARENCY_ENABLED, true)
                .putInt(AppearanceSettings.TRANSPARENCY_PERCENT, 80)
                .putInt(AppearanceSettings.BACKGROUND_COLOR, 0x00123456)
                .putBoolean(AppearanceSettings.BLUR_ENABLED, true)
                .putInt(AppearanceSettings.BLUR_AMOUNT, 65)
                .putString(AppearanceSettings.WALLPAPER, "blue")
                .putInt(AppearanceSettings.TILE_SPACING, 12)
                .putInt(AppearanceSettings.TILE_BORDER, 6)
                .putInt(AppearanceSettings.TILE_CORNER_RADIUS, 32)
                .commit();
        AppearanceSettings settings = AppearanceSettings.load(preferences);
        assertEquals(51, settings.surfaceAlpha());
        assertEquals(0xff123456, settings.backgroundColor);
        assertTrue(settings.blursWallpaper());
        assertEquals(65, settings.blurAmount);
        assertEquals(AppearanceSettings.Wallpaper.BLUE, settings.wallpaper);
        assertEquals(12, AppearanceSettings.spacingDp(preferences));
        assertEquals(6, settings.borderDp);
        assertEquals(32, settings.cornerRadiusDp);

        preferences.edit()
                .putInt(AppearanceSettings.TRANSPARENCY_PERCENT, 200)
                .putInt(AppearanceSettings.BLUR_AMOUNT, -10)
                .putInt(AppearanceSettings.TILE_SPACING, 99)
                .putInt(AppearanceSettings.TILE_BORDER, 99)
                .putInt(AppearanceSettings.TILE_CORNER_RADIUS, 99)
                .putString(AppearanceSettings.WALLPAPER, "missing-wallpaper")
                .commit();
        settings = AppearanceSettings.load(preferences);
        assertEquals(0, settings.surfaceAlpha());
        assertEquals(0, settings.blurAmount);
        assertFalse(settings.blursWallpaper());
        assertEquals(24, AppearanceSettings.spacingDp(preferences));
        assertEquals(AppearanceSettings.MAX_BORDER_DP, settings.borderDp);
        assertEquals(AppearanceSettings.MAX_CORNER_RADIUS_DP, settings.cornerRadiusDp);
        assertEquals(AppearanceSettings.Wallpaper.BLACK, settings.wallpaper);

        preferences.edit()
                .putInt(AppearanceSettings.TILE_BORDER, -3)
                .putInt(AppearanceSettings.TILE_CORNER_RADIUS, -3)
                .commit();
        settings = AppearanceSettings.load(preferences);
        assertEquals(0, settings.borderDp);
        assertEquals(0, settings.cornerRadiusDp);
    }

    @Test
    public void transparencyChangesOnlySurfaceAndKeepsContentOpaque() {
        preferences.edit().putBoolean(AppearanceSettings.TRANSPARENCY_ENABLED, true).commit();
        View wallpaper = new View(RuntimeEnvironment.getApplication());
        View tile = new View(wallpaper.getContext()) {
            @Override
            protected void onDraw(Canvas canvas) {
                Paint content = new Paint();
                content.setColor(Color.WHITE);
                canvas.drawRect(70, 70, 80, 80, content);
            }
        };
        tile.setBackground(new WidgetSurfaceDrawable(tile, wallpaper,
                AppearanceSettings.load(preferences), null));
        tile.layout(0, 0, 100, 100);
        Bitmap result = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        tile.draw(new Canvas(result));
        assertEquals(128, Color.alpha(result.getPixel(50, 50)));
        assertEquals(Color.WHITE, result.getPixel(75, 75));
        assertEquals(1f, tile.getAlpha(), 0f);
        assertEquals(0, Color.alpha(result.getPixel(0, 0)));

        preferences.edit().putInt(AppearanceSettings.TRANSPARENCY_PERCENT, 100).commit();
        WidgetSurfaceDrawable transparent = new WidgetSurfaceDrawable(tile, wallpaper,
                AppearanceSettings.load(preferences), null);
        transparent.setBounds(0, 0, 100, 100);
        result.eraseColor(Color.TRANSPARENT);
        transparent.draw(new Canvas(result));
        assertEquals(0, Color.alpha(result.getPixel(50, 50)));
        assertEquals(0, Color.alpha(result.getPixel(50, 1)));
    }

    @Test
    public void tileBorderThicknessFollowsAppearanceSetting() {
        preferences.edit().putInt(AppearanceSettings.TILE_BORDER, 8).commit();
        View wallpaper = new View(RuntimeEnvironment.getApplication());
        View tile = new View(wallpaper.getContext());
        WidgetSurfaceDrawable thickBorder = new WidgetSurfaceDrawable(tile, wallpaper,
                AppearanceSettings.load(preferences), null);
        thickBorder.setBounds(0, 0, 100, 100);
        Bitmap result = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        thickBorder.draw(new Canvas(result));
        assertEquals(0xff373f4a, result.getPixel(5, 50));

        preferences.edit().putInt(AppearanceSettings.TILE_BORDER, 0).commit();
        WidgetSurfaceDrawable withoutBorder = new WidgetSurfaceDrawable(tile, wallpaper,
                AppearanceSettings.load(preferences), null);
        withoutBorder.setBounds(0, 0, 100, 100);
        result.eraseColor(Color.TRANSPARENT);
        withoutBorder.draw(new Canvas(result));
        assertEquals(AppearanceSettings.DEFAULT_BACKGROUND_COLOR, result.getPixel(5, 50));
    }

    @Test
    public void tileCornerRadiusShapesTheSurfaceOutline() {
        preferences.edit()
                .putInt(AppearanceSettings.TILE_CORNER_RADIUS, 0)
                .putInt(AppearanceSettings.TILE_BORDER, 0)
                .commit();
        View wallpaper = new View(RuntimeEnvironment.getApplication());
        View tile = new View(wallpaper.getContext());
        Bitmap result = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);

        WidgetSurfaceDrawable square = new WidgetSurfaceDrawable(tile, wallpaper,
                AppearanceSettings.load(preferences), null);
        square.setBounds(0, 0, 100, 100);
        square.draw(new Canvas(result));
        assertEquals(Color.alpha(AppearanceSettings.DEFAULT_BACKGROUND_COLOR), Color.alpha(result.getPixel(1, 1)));

        preferences.edit().putInt(AppearanceSettings.TILE_CORNER_RADIUS, 30).commit();
        WidgetSurfaceDrawable rounded = new WidgetSurfaceDrawable(tile, wallpaper,
                AppearanceSettings.load(preferences), null);
        rounded.setBounds(0, 0, 100, 100);
        result.eraseColor(Color.TRANSPARENT);
        rounded.draw(new Canvas(result));
        assertEquals(0, Color.alpha(result.getPixel(1, 1)));
        assertEquals(Color.alpha(AppearanceSettings.DEFAULT_BACKGROUND_COLOR), Color.alpha(result.getPixel(50, 50)));
    }

    @Test
    public void inactiveCarPlayTileSharesTheTransparentSurface() {
        preferences.edit()
                .putBoolean(AppearanceSettings.TRANSPARENCY_ENABLED, true)
                .putInt(AppearanceSettings.TRANSPARENCY_PERCENT, 100)
                .commit();
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout wallpaper = new FrameLayout(context);
        FrameLayout tiles = new FrameLayout(context);
        FrameLayout tile = new FrameLayout(context);
        FrameLayout placeholder = new FrameLayout(context);
        placeholder.setId(R.id.carPlayWidgetPlaceholder);
        placeholder.setBackgroundColor(Color.BLACK);
        View shortcut = new View(context);
        shortcut.setId(R.id.carPlayWidgetShortcut);
        shortcut.setBackgroundColor(0xff22252f);
        placeholder.addView(shortcut, new FrameLayout.LayoutParams(-1, -1));
        tile.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        tiles.addView(tile, new FrameLayout.LayoutParams(-1, -1));
        wallpaper.addView(tiles, new FrameLayout.LayoutParams(-1, -1));
        int size = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY);
        wallpaper.measure(size, size);
        wallpaper.layout(0, 0, 100, 100);
        try (DashboardAppearance appearance = new DashboardAppearance(wallpaper, preferences)) {
            appearance.apply(tiles);
            Bitmap result = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
            tile.draw(new Canvas(result));
            assertEquals(0, Color.alpha(result.getPixel(50, 50)));
        }
    }

    @Test
    public void blurSoftensEdgesAndPreservesUniformColor() {
        Bitmap solid = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        solid.eraseColor(0xff234567);
        Bitmap blurredSolid = WallpaperBlur.create(solid, 100);
        assertEquals(0xff234567, blurredSolid.getPixel(0, 0));
        assertEquals(0xff234567,
                blurredSolid.getPixel(blurredSolid.getWidth() - 1, blurredSolid.getHeight() - 1));

        Bitmap edge = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 80; x++) {
                edge.setPixel(x, y, x < 40 ? Color.BLACK : Color.WHITE);
            }
        }
        Bitmap blurred = WallpaperBlur.create(edge, 60);
        int middle = Color.red(blurred.getPixel(blurred.getWidth() / 2, blurred.getHeight() / 2));
        assertTrue(middle > 20 && middle < 235);
        assertEquals(Color.WHITE, edge.getPixel(40, 20));
    }
}
