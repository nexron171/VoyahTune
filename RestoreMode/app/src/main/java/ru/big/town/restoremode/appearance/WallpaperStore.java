package ru.big.town.restoremode.appearance;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class WallpaperStore {
    private static final String CUSTOM_WALLPAPERS = "dashboardCustomWallpapers";
    private static final String CUSTOM_PREFIX = "custom:";
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 720;

    private WallpaperStore() {}

    public static List<AppearanceSettings.Wallpaper> available(SharedPreferences preferences) {
        List<AppearanceSettings.Wallpaper> wallpapers =
                new ArrayList<>(Arrays.asList(AppearanceSettings.Wallpaper.values()));
        JSONArray custom = saved(preferences);
        for (int index = 0; index < custom.length(); index++) {
            JSONObject item = custom.optJSONObject(index);
            if (item != null && validCustomKey(item.optString("key"))) {
                wallpapers.add(new AppearanceSettings.Wallpaper(
                        item.optString("key"), item.optString("title", "Свои обои"), 0));
            }
        }
        return wallpapers;
    }

    public static AppearanceSettings.Wallpaper find(SharedPreferences preferences, String key) {
        for (AppearanceSettings.Wallpaper wallpaper : available(preferences)) {
            if (wallpaper.key.equals(key)) {
                return wallpaper;
            }
        }
        return null;
    }

    public static Bitmap decode(Context context, AppearanceSettings.Wallpaper wallpaper, BitmapFactory.Options options) {
        if (wallpaper.resource != 0) {
            return BitmapFactory.decodeResource(context.getResources(), wallpaper.resource, options);
        }
        return validCustomKey(wallpaper.key)
                ? BitmapFactory.decodeFile(file(context, wallpaper.key).getAbsolutePath(), options) : null;
    }

    public static synchronized AppearanceSettings.Wallpaper importImage(
            Context context, SharedPreferences preferences, Uri uri) throws IOException {
        Bitmap decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), uri),
                (decoder, info, source) -> {
                    int largest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    decoder.setTargetSampleSize(Math.max(1, (largest + 4095) / 4096));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
        Bitmap fitted;
        try {
            fitted = fit(decoded);
        } finally {
            decoded.recycle();
        }
        String key = CUSTOM_PREFIX + UUID.randomUUID();
        File destination = file(context, key);
        File directory = destination.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            fitted.recycle();
            throw new IOException("Cannot create wallpaper directory");
        }
        try (FileOutputStream output = new FileOutputStream(destination)) {
            if (!fitted.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, output)) {
                throw new IOException("Cannot encode wallpaper");
            }
        } catch (IOException failure) {
            destination.delete();
            throw failure;
        } finally {
            fitted.recycle();
        }
        String title = title(context, uri);
        try {
            JSONArray custom = saved(preferences);
            custom.put(new JSONObject().put("key", key).put("title", title));
            if (!preferences.edit().putString(CUSTOM_WALLPAPERS, custom.toString())
                    .putString(AppearanceSettings.WALLPAPER, key).commit()) {
                throw new IOException("Cannot save wallpaper selection");
            }
        } catch (JSONException failure) {
            destination.delete();
            throw new IOException(failure);
        } catch (IOException failure) {
            destination.delete();
            throw failure;
        }
        return new AppearanceSettings.Wallpaper(key, title, 0);
    }

    static Bitmap fit(Bitmap source) {
        Bitmap result = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(android.graphics.Color.BLACK);
        WallpaperDrawable.drawCover(canvas, source, WIDTH, HEIGHT,
                new Paint(Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    private static JSONArray saved(SharedPreferences preferences) {
        try {
            return new JSONArray(preferences.getString(CUSTOM_WALLPAPERS, "[]"));
        } catch (JSONException failure) {
            return new JSONArray();
        }
    }

    private static boolean validCustomKey(String key) {
        return key != null && key.matches("custom:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static File file(Context context, String key) {
        return new File(new File(context.getFilesDir(), "wallpapers"), key.substring(CUSTOM_PREFIX.length()) + ".webp");
    }

    private static String title(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return name.substring(0, Math.min(40, name.length()));
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "Свои обои";
    }
}
