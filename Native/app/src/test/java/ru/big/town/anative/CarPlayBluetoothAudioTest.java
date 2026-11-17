package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowTransportControls;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(
        sdk = 30,
        manifest = Config.NONE,
        shadows = CarPlayBluetoothAudioTest.RecordingTransportControls.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CarPlayBluetoothAudioTest {
    private final List<FakeConnection> connections = new ArrayList<>();
    private final CarPlayBluetoothAudio audio =
            new CarPlayBluetoothAudio(
                    new Handler(Looper.getMainLooper()),
                    callback -> {
                        FakeConnection connection = new FakeConnection(callback);
                        connections.add(connection);
                        return connection;
                    });

    @Test
    public void heartbeatsSelectOnceAndANewSessionSelectsAgain() {
        audio.update(null);
        assertTrue(connections.isEmpty());

        audio.update("first");
        FakeConnection first = connections.get(0);
        assertTrue(first.connected);
        first.callback.onConnected();
        audio.update("first");
        first.callback.onConnected();
        assertEquals(1, connections.size());
        assertEquals(1, first.requests);
        assertTrue(first.disconnected);

        audio.update("second");
        connections.get(1).callback.onConnected();
        assertEquals(1, connections.get(1).requests);

        audio.close();
    }

    @Test
    public void endReplacementAndTimeoutCancelLateConnections() {
        audio.update("ended");
        FakeConnection ended = connections.get(0);
        audio.update(null);
        ended.callback.onConnected();
        assertEquals(0, ended.requests);
        assertTrue(ended.disconnected);

        audio.update("replaced");
        FakeConnection replaced = connections.get(1);
        audio.update("current");
        replaced.callback.onConnected();
        replaced.callback.onConnectionFailed();
        FakeConnection current = connections.get(2);
        assertEquals(0, replaced.requests);

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        current.callback.onConnected();
        assertTrue(current.disconnected);
        assertEquals(0, current.requests);
        audio.update("current");
        assertEquals(3, connections.size());

        audio.close();
    }

    @Test
    public void connectionFailureAndRequestErrorDoNotRetryEveryHeartbeat() {
        audio.update("failed");
        connections.get(0).callback.onConnectionFailed();
        audio.update("failed");
        assertEquals(1, connections.size());

        audio.update("throws");
        FakeConnection failed = connections.get(1);
        failed.throwOnRequest = true;
        failed.callback.onConnected();
        assertTrue(failed.disconnected);
        audio.update("throws");
        assertEquals(2, connections.size());

        audio.close();
    }

    @Test
    public void sourceSelectionPreparesA2dpBeforeRequestingFocusWithoutPlaying() {
        MediaSession session = new MediaSession(RuntimeEnvironment.getApplication(), "Bluetooth");
        try {
            CarPlayBluetoothAudio.requestSource(session.getController());
            RecordingTransportControls controls =
                    (RecordingTransportControls)
                            shadowOf(session.getController().getTransportControls());

            assertEquals(List.of("prepare", "com.qinggan.media.audio_focus"), controls.commands);
            assertEquals("com.qinggan.media.audio_focus", controls.getCustomAction());
            assertNotNull(controls.getCustomActionArgs());
            assertEquals(0, controls.getLastPerformedAction());
        } finally {
            session.release();
        }
    }

    @Test
    public void preparationCommandFailureDoesNotRequestFocusOrPlay() {
        MediaSession session = new MediaSession(RuntimeEnvironment.getApplication(), "Bluetooth");
        try {
            RecordingTransportControls controls =
                    (RecordingTransportControls)
                            shadowOf(session.getController().getTransportControls());
            controls.failPreparation = true;

            assertThrows(
                    IllegalStateException.class,
                    () -> CarPlayBluetoothAudio.requestSource(session.getController()));
            assertEquals(List.of("prepare"), controls.commands);
            assertEquals(0, controls.getLastPerformedAction());
        } finally {
            session.release();
        }
    }

    @Implements(MediaController.TransportControls.class)
    public static class RecordingTransportControls extends ShadowTransportControls {
        final List<String> commands = new ArrayList<>();
        boolean failPreparation;

        @Implementation
        protected void prepare() {
            commands.add("prepare");
            if (failPreparation) {
                throw new IllegalStateException("OEM preparation unavailable");
            }
        }

        @Implementation
        @Override
        protected void sendCustomAction(String action, Bundle extras) {
            commands.add(action);
            super.sendCustomAction(action, extras);
        }
    }

    private static final class FakeConnection implements CarPlayBluetoothAudio.Connection {
        final MediaBrowser.ConnectionCallback callback;
        boolean connected;
        boolean disconnected;
        boolean throwOnRequest;
        int requests;

        FakeConnection(MediaBrowser.ConnectionCallback callback) {
            this.callback = callback;
        }

        @Override
        public void connect() {
            connected = true;
        }

        @Override
        public void requestSource() {
            if (throwOnRequest) {
                throw new IllegalStateException("OEM service unavailable");
            }
            requests++;
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }
    }
}
