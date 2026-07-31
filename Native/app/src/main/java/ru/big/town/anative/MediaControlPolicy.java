package ru.big.town.anative;

import java.util.List;
import java.util.Locale;

/**
 * Android-free policy for choosing a media target and exactly one command backend.
 *
 * <p>The Android adapter ({@link MediaControlRouter}) deliberately lives elsewhere so this class can
 * be covered by ordinary JVM tests.  In particular, {@code PAUSE_ONLY} never produces both an
 * idempotent pause and a toggle fallback: a delayed second toggle could resume a player which has
 * already paused.</p>
 */
final class MediaControlPolicy {

    static final int STATE_UNKNOWN  = -1;
    static final int STATE_INACTIVE = 0;
    static final int STATE_ACTIVE   = 1;

    static final int KEY_PLAY_PAUSE = 85;
    static final int KEY_NEXT       = 87;
    static final int KEY_PREVIOUS   = 88;
    static final int KEY_PAUSE      = 127;

    enum Command { PLAY_PAUSE, PAUSE_ONLY, NEXT, PREVIOUS }

    enum Operation {
        /** The selected session already satisfies PAUSE_ONLY. */
        NONE,
        /** Send one media-button DOWN+UP directly to the selected MediaController. */
        TARGET_KEY,
        /** Send MediaController.TransportControls.pause() once. */
        TRANSPORT_PAUSE,
        /** The keymanager hook must send the returned standard Android media key. */
        KEYMANAGER_KEY,
        /** Preserve the original Qinggan KeyManager path. */
        NATIVE_QG
    }

    static final class Candidate {
        final String tokenId;
        final String packageName;
        final int playbackClass;
        final boolean supportsPause;
        final boolean bridge;
        final boolean nativeQinggan;

        Candidate(String tokenId, String packageName, int playbackClass,
                  boolean supportsPause, boolean bridge, boolean nativeQinggan) {
            this.tokenId = tokenId == null ? "" : tokenId;
            this.packageName = packageName == null ? "" : packageName;
            this.playbackClass = playbackClass;
            this.supportsPause = supportsPause;
            this.bridge = bridge;
            this.nativeQinggan = nativeQinggan;
        }
    }

    static final class Plan {
        final Operation operation;
        final int keyCode;

        Plan(Operation operation, int keyCode) {
            this.operation = operation;
            this.keyCode = keyCode;
        }
    }

    private MediaControlPolicy() {}

    /**
     * Select in framework priority order. A pinned token is retained after our own pause so the next
     * PLAY_PAUSE resumes the same app instead of jumping to a stale Bluetooth session. A genuine new
     * active transition replaces the pinned token in the Android adapter before this method is called.
     */
    static int chooseTarget(List<Candidate> candidates, String stickyTokenId, boolean stickyShouldWin) {
        if (candidates == null || candidates.isEmpty()) return -1;

        int sticky = indexOfToken(candidates, stickyTokenId);
        if (stickyShouldWin && sticky >= 0) return sticky;

        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).playbackClass == STATE_ACTIVE) return i;
        }
        if (sticky >= 0) return sticky;
        return 0;
    }

    static Plan plan(Candidate target, Command command) {
        if (target == null || command == null) return new Plan(Operation.NATIVE_QG, 0);

        // PAUSE_ONLY must be deterministic. A known paused/stopped session is already done; an
        // unknown session gets idempotent PAUSE=127, never a blind toggle.
        if (command == Command.PAUSE_ONLY) {
            if (target.playbackClass == STATE_INACTIVE) return new Plan(Operation.NONE, 0);
            if (target.playbackClass == STATE_UNKNOWN) {
                return new Plan(target.bridge ? Operation.KEYMANAGER_KEY : Operation.TARGET_KEY,
                        KEY_PAUSE);
            }
        }

        if (target.nativeQinggan) return new Plan(Operation.NATIVE_QG, keyFor(command));
        if (target.bridge) return new Plan(Operation.KEYMANAGER_KEY, keyFor(command));

        if (command == Command.PAUSE_ONLY && target.supportsPause) {
            return new Plan(Operation.TRANSPORT_PAUSE, 0);
        }
        return new Plan(Operation.TARGET_KEY, keyFor(command));
    }

    static int keyFor(Command command) {
        if (command == null) return 0;
        switch (command) {
            case PLAY_PAUSE: return KEY_PLAY_PAUSE;
            case PAUSE_ONLY: return KEY_PLAY_PAUSE; // reached only for a confirmed active target
            case NEXT:       return KEY_NEXT;
            case PREVIOUS:   return KEY_PREVIOUS;
            default:         return 0;
        }
    }

    /**
     * A missing/unknown MediaSession normally permits only idempotent PAUSE. If AudioManager confirms
     * that the music stream is currently active, PLAY_PAUSE is no longer blind and is the appropriate
     * compatibility command for bridges which ignore KEYCODE_MEDIA_PAUSE (notably AutoKit).
     */
    static int pauseKeyWithAudioEvidence(int plannedKey, boolean musicActive) {
        return plannedKey == KEY_PAUSE && musicActive ? KEY_PLAY_PAUSE : plannedKey;
    }

    static boolean isBridgePackage(String packageName) {
        String pkg = packageName == null ? "" : packageName.toLowerCase(Locale.US);
        return pkg.contains("carplay")
                || pkg.contains("autokit")
                || pkg.contains("zlink")
                || pkg.contains("speedplay")
                || pkg.contains("phonemirror")
                || pkg.contains("projection");
    }

    static boolean isNativeQingganPackage(String packageName) {
        if (packageName == null) return false;
        return packageName.equals("android")
                || packageName.startsWith("com.android.")
                || packageName.startsWith("com.qinggan.");
    }

    private static int indexOfToken(List<Candidate> candidates, String tokenId) {
        if (tokenId == null || tokenId.isEmpty()) return -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (tokenId.equals(candidates.get(i).tokenId)) return i;
        }
        return -1;
    }
}
