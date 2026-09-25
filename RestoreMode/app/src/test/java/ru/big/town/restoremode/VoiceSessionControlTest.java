package ru.big.town.restoremode;

import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceSessionControlTest {
    private static final class Microphone implements VoiceSessionControl.Capture {
        int closes;
        @Override public int read(short[] data, int offset, int count) {
            assertEquals("No reads after release", 0, closes); return count;
        }
        @Override public void close() { closes++; }
    }

    @Test public void closingScreenWhilePreparingNeverOpensMicrophoneLater() throws Exception {
        VoiceSessionControl control = new VoiceSessionControl();
        long session = control.begin();
        assertTrue(control.cancel(session));
        assertFalse(control.open(session, () -> { fail("Cancelled preparation opened mic"); return null; }));
    }

    @Test public void newSessionClosesOldCaptureAndIgnoresOldCleanup() throws Exception {
        VoiceSessionControl control = new VoiceSessionControl();
        Microphone first = new Microphone(), second = new Microphone();
        long old = control.begin();
        assertTrue(control.open(old, () -> first));
        long current = control.begin();
        assertEquals(1, first.closes);
        assertTrue(control.open(current, () -> second));
        assertFalse(control.cancel(old));
        control.finishCapture(old);
        assertEquals(0, second.closes);
        assertEquals(160, control.read(current, new short[160], 0, 160));
        control.cancel(current);
        control.finishCapture(current);
        assertEquals(1, second.closes);
        assertEquals(0, control.read(current, new short[160], 0, 160));
    }

    @Test public void endOfSpeechReleasesCaptureBeforeDecodingAndKeepsResultToken() throws Exception {
        VoiceSessionControl control = new VoiceSessionControl();
        long session = control.begin();
        Microphone mic = new Microphone();
        control.open(session, () -> mic);
        control.finishCapture(session);
        assertEquals(1, mic.closes);
        assertTrue(control.active(session));
        control.cancel(session);
        assertEquals(1, mic.closes);
    }

    @Test public void disablingServiceReleasesActiveMicAndInvalidatesQueuedSession() throws Exception {
        VoiceSessionControl control = new VoiceSessionControl();
        long session = control.begin();
        Microphone mic = new Microphone();
        control.open(session, () -> mic);
        control.cancelAll();
        assertEquals(1, mic.closes);
        assertFalse(control.active(session));
        assertFalse(control.open(session, () -> { fail("Late callback"); return null; }));
    }

    @Test public void cancellationRacingMicrophoneOpenCannotLeakCapture() throws Exception {
        VoiceSessionControl control = new VoiceSessionControl();
        Microphone mic = new Microphone();
        long session = control.begin();
        CountDownLatch opening = new CountDownLatch(1), proceed = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> open = executor.submit(() -> control.open(session, () -> {
                opening.countDown(); assertTrue(proceed.await(5, TimeUnit.SECONDS)); return mic;
            }));
            assertTrue(opening.await(5, TimeUnit.SECONDS));
            Future<Boolean> cancel = executor.submit(() -> control.cancel(session));
            proceed.countDown();
            assertTrue(open.get(5, TimeUnit.SECONDS));
            assertTrue(cancel.get(5, TimeUnit.SECONDS));
            assertEquals(1, mic.closes);
            assertFalse(control.active(session));
        } finally { proceed.countDown(); executor.shutdownNow(); }
    }
}
