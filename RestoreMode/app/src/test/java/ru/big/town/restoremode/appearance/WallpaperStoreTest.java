package ru.big.town.restoremode.appearance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import ru.big.town.common.WallpaperProtocol;
import ru.big.town.restoremode.integration.config.SettingsContentProvider;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WallpaperStoreTest {
    private Context context;
    private SharedPreferences preferences;

    @Before
    public void prepare() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(WallpaperProtocol.PERMISSION);
    }

    @Test
    public void fitFillsTheCanvasAndCropsInsteadOfDistortingTheImage() {
        Bitmap square = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888);
        square.eraseColor(Color.BLUE);
        Paint paint = new Paint();
        Canvas source = new Canvas(square);
        paint.setColor(Color.RED);
        source.drawRect(0, 0, 400, 100, paint);
        paint.setColor(Color.GREEN);
        source.drawRect(0, 300, 400, 400, paint);
        Bitmap fitted = WallpaperStore.fit(square);
        assertEquals(1920, fitted.getWidth());
        assertEquals(720, fitted.getHeight());
        assertEquals(Color.BLUE, fitted.getPixel(960, 1));
        assertEquals(Color.BLUE, fitted.getPixel(960, 718));
        assertEquals(Color.RED, square.getPixel(200, 1));
        assertEquals(Color.GREEN, square.getPixel(200, 398));
    }

    @Test
    public void importsAnIndependentCopyAndRestoresTheCustomSelection() throws Exception {
        File original = new File(context.getCacheDir(), "custom-source.png");
        Bitmap image = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.BLUE);
        try (FileOutputStream output = new FileOutputStream(original)) {
            image.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        AppearanceSettings.Wallpaper imported = WallpaperStore.importImage(context, preferences, Uri.fromFile(original));
        assertTrue(original.delete());
        assertNotNull(WallpaperStore.find(preferences, imported.key));
        assertEquals(imported, AppearanceSettings.load(preferences).wallpaper);
        Bitmap restored = WallpaperStore.decode(context, imported, null);
        assertNotNull(restored);
        assertEquals(1920, restored.getWidth());
        assertEquals(720, restored.getHeight());
        assertTrue(Color.blue(restored.getPixel(960, 360)) > 240);
        assertEquals(10, WallpaperStore.available(preferences).size());
    }

    @Test
    public void providerRejectsUnknownWallpaperAndPersistsKnownChoice() {
        SettingsContentProvider provider = Robolectric.buildContentProvider(SettingsContentProvider.class).create().get();
        assertTrue(provider.call(WallpaperProtocol.METHOD_SET, "purple", null)
                .getBoolean(WallpaperProtocol.RESULT_APPLIED));
        assertEquals(AppearanceSettings.Wallpaper.PURPLE, AppearanceSettings.load(preferences).wallpaper);
        assertFalse(provider.call(WallpaperProtocol.METHOD_SET, "missing", null)
                .getBoolean(WallpaperProtocol.RESULT_APPLIED));
        assertEquals(AppearanceSettings.Wallpaper.PURPLE, AppearanceSettings.load(preferences).wallpaper);
    }

    @Test(expected = SecurityException.class)
    public void providerRequiresTheExistingSignaturePermission() {
        SettingsContentProvider provider = Robolectric.buildContentProvider(SettingsContentProvider.class).create().get();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(WallpaperProtocol.PERMISSION);
        provider.call(WallpaperProtocol.METHOD_SET, "red", null);
    }
}
