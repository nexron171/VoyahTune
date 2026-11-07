package ru.big.town.restoremode.widgets.fragrance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.drawable.AnimationDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

final class FragranceTileGraphics {
    private static final Executor IO = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final FragranceWidgetLayout layout;
    private final ImageView mist;
    private final Runnable render;
    private FragranceArtwork artwork;
    private boolean active;
    private boolean namesObserved;
    private int width;
    private int height;
    private volatile int generation;
    private volatile int animationGeneration;
    private boolean animationRequested;
    private AnimationDrawable animation;

    private final BroadcastReceiver themeChanges =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    reload();
                }
            };
    private final ContentObserver nameChanges =
            new ContentObserver(main) {
                @Override
                public void onChange(boolean selfChange) {
                    reload();
                }
            };

    FragranceTileGraphics(
            Context context, FragranceWidgetLayout layout, ImageView mist, Runnable render) {
        this.context = context.getApplicationContext();
        this.layout = layout;
        this.mist = mist;
        this.render = render;
    }

    FragranceArtwork artwork() {
        return artwork;
    }

    void setVisible(boolean visible, int width, int height) {
        boolean resized = width != this.width || height != this.height;
        this.width = width;
        this.height = height;
        if (active == visible) {
            if (active && resized) reload();
            return;
        }
        active = visible;
        if (visible) {
            IntentFilter filter = new IntentFilter("action.qg.theme.changed");
            filter.addAction("action.qg.theme.skin.changed");
            ContextCompat.registerReceiver(
                    context, themeChanges, filter, ContextCompat.RECEIVER_EXPORTED);
            try {
                for (int scent = 1; scent <= 3; scent++) {
                    Uri name = Uri.withAppendedPath(FragranceArtwork.SETTINGS, "fg_name_0" + scent);
                    context.getContentResolver().registerContentObserver(name, false, nameChanges);
                    namesObserved = true;
                }
            } catch (SecurityException | IllegalArgumentException unavailable) {
                // OEM names can still be read when the provider disallows observers.
            }
        } else {
            context.unregisterReceiver(themeChanges);
            if (namesObserved) context.getContentResolver().unregisterContentObserver(nameChanges);
            namesObserved = false;
        }
        reload();
    }

    private void reload() {
        int token = ++generation;
        stopAnimation();
        artwork = null;
        render.run();
        if (!active || width <= 0 || height <= 0) return;
        int tileWidth = width;
        int tileHeight = height;
        IO.execute(
                () -> {
                    if (generation != token) return;
                    FragranceArtwork loaded =
                            FragranceArtwork.load(
                                    context,
                                    layout,
                                    tileWidth,
                                    tileHeight,
                                    () -> generation == token);
                    main.post(
                            () -> {
                                if (generation != token) return;
                                artwork = loaded;
                                render.run();
                            });
                });
    }

    void animate(boolean enabled) {
        if (!enabled || !active || artwork == null) {
            if (animationRequested) stopAnimation();
            return;
        }
        if (animationRequested) return;
        animationRequested = true;
        int token = ++animationGeneration;
        FragranceArtwork source = artwork;
        int frameWidth = Math.max(1, mist.getMeasuredWidth());
        int frameHeight = Math.max(1, mist.getMeasuredHeight());
        IO.execute(
                () -> {
                    if (animationGeneration != token) return;
                    AnimationDrawable frames =
                            source.animation(
                                    context.getResources(),
                                    layout,
                                    frameWidth,
                                    frameHeight,
                                    () -> animationGeneration == token);
                    main.post(
                            () -> {
                                if (animationGeneration != token || frames == null) return;
                                animation = frames;
                                mist.setImageDrawable(frames);
                                frames.start();
                            });
                });
    }

    private void stopAnimation() {
        animationGeneration++;
        animationRequested = false;
        if (animation != null) animation.stop();
        animation = null;
        mist.setImageDrawable(null);
    }
}
