package ru.big.town.anative;

import android.content.ComponentName;
import android.content.Context;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import java.util.Objects;

/** Selects OEM Bluetooth once per connected CarPlay session without opening the player. */
final class CarPlayBluetoothAudio implements AutoCloseable {
    private static final String TAG = "CarPlayBluetoothAudio";
    private static final ComponentName SERVICE =
            new ComponentName(
                    "com.qinggan.media", "com.qinggan.media.btmusic.BTMediaBrowserService");

    interface Connection {
        void connect();

        void requestFocus();

        void disconnect();
    }

    interface ConnectionFactory {
        Connection create(MediaBrowser.ConnectionCallback callback);
    }

    private final Handler handler;
    private final ConnectionFactory factory;
    private String session;
    private int generation;
    private Connection connection;
    private final Runnable timeout = () -> finish("Bluetooth media connection timed out");

    CarPlayBluetoothAudio(Context context, Handler handler) {
        this(handler, callback -> new OemConnection(context, callback));
    }

    CarPlayBluetoothAudio(Handler handler, ConnectionFactory factory) {
        this.handler = handler;
        this.factory = factory;
    }

    void update(String nextSession) {
        String next = nextSession == null || nextSession.isEmpty() ? null : nextSession;
        if (Objects.equals(session, next)) {
            return;
        }
        finish(null);
        session = next;
        if (next == null) {
            return;
        }
        int expectedGeneration = generation;
        try {
            connection =
                    factory.create(
                            new MediaBrowser.ConnectionCallback() {
                                @Override
                                public void onConnected() {
                                    if (expectedGeneration != generation || connection == null) {
                                        return;
                                    }
                                    try {
                                        connection.requestFocus();
                                        Log.i(TAG, "OEM Bluetooth source requested");
                                    } catch (RuntimeException error) {
                                        Log.w(TAG, "OEM Bluetooth source unavailable", error);
                                    } finally {
                                        finish(null);
                                    }
                                }

                                @Override
                                public void onConnectionFailed() {
                                    if (expectedGeneration == generation) {
                                        finish("Bluetooth media connection failed");
                                    }
                                }

                                @Override
                                public void onConnectionSuspended() {
                                    if (expectedGeneration == generation) {
                                        finish("Bluetooth media connection suspended");
                                    }
                                }
                            });
            handler.postDelayed(timeout, 5000);
            connection.connect();
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot connect to OEM Bluetooth media", error);
            finish(null);
        }
    }

    private void finish(String reason) {
        generation++;
        handler.removeCallbacks(timeout);
        Connection previous = connection;
        connection = null;
        if (reason != null) {
            Log.w(TAG, reason);
        }
        if (previous != null) {
            try {
                previous.disconnect();
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot disconnect Bluetooth media browser", error);
            }
        }
    }

    @Override
    public void close() {
        finish(null);
        session = null;
    }

    static void requestFocus(MediaController controller) {
        // OEM BTPlayer dereferences extras and requests stream 4 without starting playback.
        controller
                .getTransportControls()
                .sendCustomAction("com.qinggan.media.audio_focus", new Bundle());
    }

    private static final class OemConnection implements Connection {
        private final Context context;
        private final MediaBrowser browser;

        OemConnection(Context context, MediaBrowser.ConnectionCallback callback) {
            this.context = context;
            browser = new MediaBrowser(context, SERVICE, callback, null);
        }

        @Override
        public void connect() {
            browser.connect();
        }

        @Override
        public void requestFocus() {
            CarPlayBluetoothAudio.requestFocus(
                    new MediaController(context, browser.getSessionToken()));
        }

        @Override
        public void disconnect() {
            browser.disconnect();
        }
    }
}
