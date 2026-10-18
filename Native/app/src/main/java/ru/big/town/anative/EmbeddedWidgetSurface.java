package ru.big.town.anative;

import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

/** Keeps an embedded app VD online and draining frames while its Dashboard TextureView is absent. */
final class EmbeddedWidgetSurface implements AutoCloseable {
    private static final Handler FRAMES;

    static {
        HandlerThread thread = new HandlerThread("EmbeddedWidgetFrames");
        thread.start();
        FRAMES = new Handler(thread.getLooper());
    }

    private final ImageReader reader;

    EmbeddedWidgetSurface(int width, int height) {
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(source -> {
            try (Image ignored = source.acquireLatestImage()) {
                // Consume without copying pixels; the VD and its Activity keep the same size.
            } catch (IllegalStateException closed) {
                // An already posted frame callback may race close after surface reattachment.
            }
        }, FRAMES);
    }

    Surface surface() {
        return reader.getSurface();
    }

    @Override
    public void close() {
        reader.setOnImageAvailableListener(null, null);
        reader.close();
    }
}
