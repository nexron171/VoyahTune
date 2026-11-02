package ru.big.town.restoremode.appearance;

import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.widgets.brightness.BrightnessWidgetView;
import ru.big.town.restoremode.widgets.energy.VehicleWidgetView;
import ru.big.town.restoremode.widgets.headlights.HeadlightWidgetView;
import ru.big.town.restoremode.widgets.radio.RadioWidgetView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class DashboardAppearance implements AutoCloseable {
    private final View wallpaperView;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final WallpaperDrawable wallpaperLayer = new WallpaperDrawable(null);
    private final WallpaperDrawable blurredLayer = new WallpaperDrawable(null);
    private final Rect visibleTileBounds = new Rect();
    private final java.util.WeakHashMap<View, Boolean> styledTiles = new java.util.WeakHashMap<>();
    private final ValueAnimator crossfade = ValueAnimator.ofFloat(0f, 1f);
    private final SharedPreferences.OnSharedPreferenceChangeListener wallpaperListener;
    private ViewGroup tiles;
    private AppearanceSettings settings;
    private AppearanceSettings.Wallpaper loadedWallpaper;
    private AppearanceSettings.Wallpaper displayedWallpaper;
    private int loadedBlur = -1;
    private Bitmap wallpaper;
    private Future<?> loading;
    private int generation;
    private boolean closed;

    public DashboardAppearance(View wallpaperView, SharedPreferences preferences) {
        this.wallpaperView = wallpaperView;
        this.preferences = preferences;
        settings = AppearanceSettings.load(preferences);
        wallpaperView.setBackground(wallpaperLayer);
        crossfade.setDuration(400);
        crossfade.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            wallpaperLayer.setProgress(progress);
            blurredLayer.setProgress(progress);
            invalidateTiles();
        });
        wallpaperListener = (saved, key) -> {
            if (AppearanceSettings.WALLPAPER.equals(key)
                    || AppearanceSettings.TRANSPARENCY_ENABLED.equals(key)
                    || AppearanceSettings.TRANSPARENCY_PERCENT.equals(key)
                    || AppearanceSettings.BACKGROUND_COLOR.equals(key)
                    || AppearanceSettings.BLUR_ENABLED.equals(key)
                    || AppearanceSettings.BLUR_AMOUNT.equals(key)) {
                wallpaperView.post(() -> {
                    if (!closed && tiles != null) {
                        apply(tiles);
                    }
                });
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(wallpaperListener);
    }

    public void apply(ViewGroup tiles) {
        AppearanceSettings updated = AppearanceSettings.load(preferences);
        boolean restyle = this.tiles != tiles
                || settings.backgroundColor != updated.backgroundColor
                || settings.transparencyEnabled != updated.transparencyEnabled
                || settings.transparencyPercent != updated.transparencyPercent
                || settings.blurEnabled != updated.blurEnabled
                || settings.blurAmount != updated.blurAmount;
        this.tiles = tiles;
        settings = updated;
        int blurAmount = settings.blursWallpaper() ? settings.blurAmount : 0;
        if (!java.util.Objects.equals(loadedWallpaper, settings.wallpaper) || loadedBlur != blurAmount) {
            loadWallpaper(settings.wallpaper, blurAmount);
        }
        styleTiles(restyle);
        if (!restyle) {
            invalidateTiles();
        }
    }

    private void loadWallpaper(AppearanceSettings.Wallpaper selected, int blurAmount) {
        int request = ++generation;
        if (loading != null) {
            loading.cancel(false);
        }
        boolean sameWallpaper = java.util.Objects.equals(loadedWallpaper, selected);
        Bitmap previous = sameWallpaper ? wallpaper : null;
        if (!sameWallpaper) {
            wallpaper = null;
        }
        loadedWallpaper = selected;
        loadedBlur = blurAmount;
        if (selected == AppearanceSettings.Wallpaper.BLACK) {
            showWallpaper(selected, null, null);
            return;
        }
        loading = worker.submit(() -> {
            Bitmap image = previous != null ? previous
                    : WallpaperStore.decode(wallpaperView.getContext(), selected, null);
            if (image == null) {
                wallpaperView.post(() -> {
                    if (!closed && generation == request) {
                        loadedWallpaper = null;
                        android.widget.Toast.makeText(wallpaperView.getContext(),
                                "Не удалось загрузить обои", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            Bitmap blurred = blurAmount > 0 ? WallpaperBlur.create(image, blurAmount) : null;
            wallpaperView.post(() -> {
                if (closed || generation != request) {
                    return;
                }
                showWallpaper(selected, image, blurred);
            });
        });
    }

    private void showWallpaper(AppearanceSettings.Wallpaper selected, Bitmap image, Bitmap blurred) {
        wallpaper = image;
        boolean animate = displayedWallpaper != null && !displayedWallpaper.equals(selected);
        displayedWallpaper = selected;
        crossfade.cancel();
        if (animate) {
            wallpaperLayer.transitionTo(image);
            blurredLayer.transitionTo(blurred != null ? blurred : image);
            crossfade.start();
        } else {
            wallpaperLayer.show(image);
            blurredLayer.show(blurred != null ? blurred : image);
        }
        invalidateTiles();
    }

    public void onViewportChanged() {
        // Card backgrounds sample the stationary wallpaper in screen coordinates.
        invalidateTiles();
    }

    private void invalidateTiles() {
        if (tiles != null) {
            for (int index = 0; index < tiles.getChildCount(); index++) {
                View tile = tiles.getChildAt(index);
                if (tile.isShown() && tile.getAlpha() > 0f
                        && tile.getGlobalVisibleRect(visibleTileBounds)) {
                    tile.invalidate();
                }
            }
        }
    }

    private void styleTiles(boolean restyle) {
        if (tiles == null) {
            return;
        }
        for (int index = 0; index < tiles.getChildCount(); index++) {
            View tile = tiles.getChildAt(index);
            if (restyle || !styledTiles.containsKey(tile)) {
                styleTile(tile);
            }
        }
    }

    public void styleTile(View tile) {
        clearNestedSurfaces(tile);
        if (settings.customBackground()) {
            clearBackground(tile.findViewById(R.id.carPlayWidgetPlaceholder));
            clearBackground(tile.findViewById(R.id.carPlayWidgetShortcut));
        }
        if (tile instanceof RadioWidgetView) {
            ((RadioWidgetView) tile).setCustomBackground(settings.customBackground());
        }
        if (tile instanceof HeadlightWidgetView) {
            ((HeadlightWidgetView) tile).setSurfaceAlpha(settings.surfaceAlpha());
        }
        if (tile instanceof BrightnessWidgetView) {
            ((BrightnessWidgetView) tile).setSurfaceAlpha(settings.surfaceAlpha());
        }
        int left = tile.getPaddingLeft();
        int top = tile.getPaddingTop();
        int right = tile.getPaddingRight();
        int bottom = tile.getPaddingBottom();
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xffffffff);
        mask.setCornerRadius(20 * tile.getResources().getDisplayMetrics().density);
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff),
                new WidgetSurfaceDrawable(tile, wallpaperView, settings, blurredLayer), mask));
        tile.setPadding(left, top, right, bottom);
        styledTiles.put(tile, Boolean.TRUE);
    }

    public Runnable stylePopup(View popup) {
        AppearanceSettings popupSettings = AppearanceSettings.load(preferences);
        View scene = wallpaperView.getRootView();
        Bitmap blurred = null;
        if (popupSettings.transparencyEnabled && popupSettings.blurEnabled
                && popupSettings.blurAmount > 0 && scene.getWidth() > 0 && scene.getHeight() > 0) {
            // Capture before attaching the popup so its own content stays sharp.
            Bitmap snapshot = Bitmap.createBitmap(
                    scene.getWidth(), scene.getHeight(), Bitmap.Config.ARGB_8888);
            scene.draw(new Canvas(snapshot));
            blurred = WallpaperBlur.create(snapshot, popupSettings.blurAmount);
            snapshot.recycle();
        }
        Bitmap backdrop = blurred;
        popup.setBackground(new WidgetSurfaceDrawable(
                popup, scene, popupSettings, backdrop == null ? null : new WallpaperDrawable(backdrop)));
        popup.setClipToOutline(true);
        return () -> {
            popup.setBackground(null);
            if (backdrop != null) {
                backdrop.recycle();
            }
        };
    }

    private static void clearBackground(View view) {
        if (view != null) {
            view.setBackground(null);
        }
    }

    private static void clearNestedSurfaces(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                if (child instanceof VehicleWidgetView) {
                    child.setBackground(null);
                }
                clearNestedSurfaces(child);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        preferences.unregisterOnSharedPreferenceChangeListener(wallpaperListener);
        crossfade.cancel();
        ++generation;
        tiles = null;
        styledTiles.clear();
        worker.shutdownNow();
    }
}
