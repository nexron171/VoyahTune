package ru.big.town.restoremode.voice.audio;

import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures the assistant start path from session creation to a running microphone. Sessions are
 * serialized by {@link VoiceSessionControl}, so one shared anchor is enough; marks stay in logcat
 * and never change behaviour.
 */
public final class VoiceStartupTrace {
    private static final String TAG = "VoyahVoiceStartup";
    private static final long NO_SESSION = Long.MIN_VALUE;
    private static final AtomicLong ANCHOR = new AtomicLong(NO_SESSION);

    private VoiceStartupTrace() {}

    /** Anchors a new session; marks of an older one keep using the previous clock only. */
    public static void beginSession() {
        ANCHOR.set(SystemClock.elapsedRealtime());
    }

    /** Cumulative time since {@link #beginSession()}. */
    public static void mark(String stage) {
        long anchor = ANCHOR.get();
        if (anchor == NO_SESSION) {
            return;
        }
        Log.i(TAG, stage + " +" + (SystemClock.elapsedRealtime() - anchor) + " ms");
    }

    /** Duration of one step, independent of a session (shared caches are built on demand). */
    public static void took(String step, long startedAt) {
        Log.i(TAG, step + " took " + (SystemClock.elapsedRealtime() - startedAt) + " ms");
    }

    public static long now() {
        return SystemClock.elapsedRealtime();
    }
}
