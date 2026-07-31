package ru.big.town.anative;

import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single routing point for steering-wheel commands and door PAUSE_ONLY.
 *
 * <p>For ordinary Android players a key is dispatched to one concrete {@link MediaController}; for
 * bridge players (AutoKit/CarPlay/Android Auto) the command is returned to the injected keymanager
 * hook, preserving the caller/path which is known to work on the head unit; OEM/Bluetooth remains on
 * the original Qinggan route.</p>
 */
final class MediaControlRouter {
    private static final String TAG = "$$$ MediaControlRouter $$$";

    static final String ROUTE_DIRECT     = "direct";
    static final String ROUTE_KEYMANAGER = "keymanager";
    static final String ROUTE_NATIVE     = "native";
    static final String ROUTE_NOOP       = "noop";

    private static final Object TARGET_LOCK = new Object();
    private static MediaSession.Token stickyToken;
    /** True only while preserving the target of our own pause/toggle across its state transition. */
    private static boolean stickyPinned;
    private static final List<MediaSession.Token> lastActiveTokens = new ArrayList<>();
    private static boolean activeSnapshotInitialized;

    private MediaControlRouter() {}

    static final class Result {
        final String route;
        final int keyCode;
        final String packageName;
        final int playbackClass;

        Result(String route, int keyCode, String packageName, int playbackClass) {
            this.route = route;
            this.keyCode = keyCode;
            this.packageName = packageName == null ? "" : packageName;
            this.playbackClass = playbackClass;
        }

        Bundle toBundle() {
            Bundle b = new Bundle();
            b.putString("route", route);
            b.putInt("keyCode", keyCode);
            b.putString("package", packageName);
            b.putInt("playbackClass", playbackClass);
            return b;
        }
    }

    static Result dispatch(Context context, MediaControlPolicy.Command command) {
        if (context == null || command == null) return nativeFallback(command, "", MediaControlPolicy.STATE_UNKNOWN);
        try {
            MediaSessionManager msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return nativeFallback(command, "", MediaControlPolicy.STATE_UNKNOWN);
            List<MediaController> controllers = msm.getActiveSessions(null);
            if (controllers == null) controllers = Collections.emptyList();
            MediaController target = selectController(controllers);
            logDecisionInput(command, controllers, target);

            // With no Android session the wheel must remain OEM-native. Door PAUSE_ONLY instead asks
            // keymanager for idempotent PAUSE=127; it must not blindly toggle an unknown player.
            if (target == null) {
                return command == MediaControlPolicy.Command.PAUSE_ONLY
                        ? new Result(ROUTE_KEYMANAGER, MediaControlPolicy.KEY_PAUSE, "",
                                MediaControlPolicy.STATE_UNKNOWN)
                        : nativeFallback(command, "", MediaControlPolicy.STATE_UNKNOWN);
            }

            MediaControlPolicy.Candidate candidate = candidate(target, false);
            MediaControlPolicy.Plan plan = MediaControlPolicy.plan(candidate, command);
            Result result = executePlan(target, candidate, command, plan);
            Log.i(TAG, "dispatch " + command + " -> route=" + result.route + " key=" + result.keyCode
                    + " pkg=" + result.packageName + " stateClass=" + result.playbackClass);
            return result;
        } catch (SecurityException e) {
            Log.w(TAG, "dispatch " + command + ": no MEDIA_CONTENT_CONTROL: " + e.getMessage());
            return safeFailure(command);
        } catch (Throwable e) {
            Log.w(TAG, "dispatch " + command + ": " + e.getMessage());
            return safeFailure(command);
        }
    }

    /** Called by NowPlayingService when a controller genuinely enters an active playback state. */
    static void notePlaying(MediaController controller) {
        if (controller == null) return;
        synchronized (TARGET_LOCK) {
            stickyToken = controller.getSessionToken();
            // A fresh inactive->active transition is stronger evidence than stale PLAYING states
            // left behind by Bluetooth/bridge sessions. This is not an own-pause pin: once it becomes
            // inactive, another actually active session must be allowed to win.
            stickyPinned = false;
        }
    }

    /** Selection shared by the metadata service and command router. */
    static MediaController selectController(List<MediaController> supplied) {
        List<MediaController> controllers = supplied == null ? Collections.emptyList() : supplied;
        observeActiveTransitions(controllers);
        if (controllers.isEmpty()) {
            synchronized (TARGET_LOCK) {
                stickyToken = null;
                stickyPinned = false;
            }
            return null;
        }

        MediaSession.Token sticky;
        boolean pinned;
        synchronized (TARGET_LOCK) {
            sticky = stickyToken;
            pinned = stickyPinned;
        }

        List<MediaControlPolicy.Candidate> candidates = new ArrayList<>(controllers.size());
        boolean stickyPresent = false;
        boolean stickyActive = false;
        for (int i = 0; i < controllers.size(); i++) {
            MediaController c = controllers.get(i);
            boolean isSticky = sameToken(c.getSessionToken(), sticky);
            stickyPresent |= isSticky;
            MediaControlPolicy.Candidate value = candidate(c, isSticky);
            stickyActive |= isSticky && value.playbackClass == MediaControlPolicy.STATE_ACTIVE;
            candidates.add(value);
        }

        if (!stickyPresent && sticky != null) {
            synchronized (TARGET_LOCK) {
                if (sameToken(stickyToken, sticky)) {
                    stickyToken = null;
                    stickyPinned = false;
                }
            }
            pinned = false;
        }

        // An own-pause pin may keep an inactive target ahead of stale PLAYING sessions so the next
        // press resumes the same app. A normal sticky token wins only while it is itself active.
        if (pinned && stickyActive) {
            synchronized (TARGET_LOCK) {
                if (sameToken(stickyToken, sticky)) stickyPinned = false;
            }
            pinned = false;
        }
        int index = MediaControlPolicy.chooseTarget(
                candidates, "sticky", pinned || stickyActive);
        MediaController selected = index >= 0 && index < controllers.size() ? controllers.get(index) : null;
        if (selected != null
                && playbackClass(safePlaybackState(selected)) == MediaControlPolicy.STATE_ACTIVE) {
            synchronized (TARGET_LOCK) {
                stickyToken = selected.getSessionToken();
                stickyPinned = false;
            }
        }
        return selected;
    }

    /** Detects a newly active token even when a session was already PLAYING when it was added. */
    private static void observeActiveTransitions(List<MediaController> controllers) {
        List<MediaSession.Token> activeNow = new ArrayList<>();
        for (MediaController controller : controllers) {
            if (isActiveState(safePlaybackState(controller))) {
                activeNow.add(controller.getSessionToken());
            }
        }
        synchronized (TARGET_LOCK) {
            MediaSession.Token newlyActive = null;
            if (activeSnapshotInitialized) {
                for (MediaSession.Token token : activeNow) {
                    if (!containsToken(lastActiveTokens, token)) {
                        newlyActive = token;
                        break; // activeSessions is already in framework priority order
                    }
                }
            }
            lastActiveTokens.clear();
            lastActiveTokens.addAll(activeNow);
            activeSnapshotInitialized = true;
            if (newlyActive != null) {
                stickyToken = newlyActive;
                stickyPinned = false;
            }
        }
    }

    static boolean isActiveState(PlaybackState state) {
        return playbackClass(state) == MediaControlPolicy.STATE_ACTIVE;
    }

    private static Result executePlan(MediaController target, MediaControlPolicy.Candidate candidate,
                                      MediaControlPolicy.Command command,
                                      MediaControlPolicy.Plan plan) {
        switch (plan.operation) {
            case NONE:
                return new Result(ROUTE_NOOP, 0, candidate.packageName, candidate.playbackClass);

            case NATIVE_QG:
                rememberCommandTarget(target, command);
                return new Result(ROUTE_NATIVE, plan.keyCode, candidate.packageName, candidate.playbackClass);

            case KEYMANAGER_KEY:
                rememberCommandTarget(target, command);
                return new Result(ROUTE_KEYMANAGER, plan.keyCode, candidate.packageName, candidate.playbackClass);

            case TRANSPORT_PAUSE:
                target.getTransportControls().pause();
                rememberCommandTarget(target, command);
                return new Result(ROUTE_DIRECT, 0, candidate.packageName, candidate.playbackClass);

            case TARGET_KEY:
                boolean sent = dispatchTargetedKey(target, plan.keyCode);
                if (!sent) {
                    // No second semantic command was sent, so falling back to the same single key in
                    // keymanager cannot double-toggle the player.
                    rememberCommandTarget(target, command);
                    return new Result(ROUTE_KEYMANAGER, plan.keyCode,
                            candidate.packageName, candidate.playbackClass);
                }
                rememberCommandTarget(target, command);
                return new Result(ROUTE_DIRECT, plan.keyCode,
                        candidate.packageName, candidate.playbackClass);

            default:
                return new Result(ROUTE_NATIVE, plan.keyCode,
                        candidate.packageName, candidate.playbackClass);
        }
    }

    private static boolean dispatchTargetedKey(MediaController target, int keyCode) {
        if (target == null || keyCode == 0) return false;
        long t = SystemClock.uptimeMillis();
        MediaKeyPairDelivery.Outcome outcome = MediaKeyPairDelivery.dispatch(down ->
                target.dispatchMediaButtonEvent(new KeyEvent(
                        t, t, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keyCode, 0)));
        if (outcome == MediaKeyPairDelivery.Outcome.DOWN_ONLY) {
            Log.w(TAG, "target key UP failed/not accepted after DOWN; no fallback, key=" + keyCode);
        }
        return outcome != MediaKeyPairDelivery.Outcome.NOT_SENT;
    }

    private static void rememberCommandTarget(MediaController target,
                                              MediaControlPolicy.Command command) {
        if (target == null) return;
        synchronized (TARGET_LOCK) {
            stickyToken = target.getSessionToken();
            stickyPinned = command == MediaControlPolicy.Command.PAUSE_ONLY
                    || command == MediaControlPolicy.Command.PLAY_PAUSE;
        }
    }

    private static MediaControlPolicy.Candidate candidate(MediaController c, boolean sticky) {
        String pkg = c == null ? "" : safePackage(c);
        PlaybackState state = c == null ? null : safePlaybackState(c);
        long actions = state == null ? 0L : state.getActions();
        boolean bridge = MediaControlPolicy.isBridgePackage(pkg);
        boolean nativeQinggan = !bridge && MediaControlPolicy.isNativeQingganPackage(pkg);
        return new MediaControlPolicy.Candidate(
                sticky ? "sticky" : "other",
                pkg,
                playbackClass(state),
                (actions & PlaybackState.ACTION_PAUSE) != 0,
                bridge,
                nativeQinggan);
    }

    private static int playbackClass(PlaybackState state) {
        if (state == null) return MediaControlPolicy.STATE_UNKNOWN;
        switch (state.getState()) {
            case PlaybackState.STATE_NONE:
            case PlaybackState.STATE_ERROR:
                return MediaControlPolicy.STATE_UNKNOWN;
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                return MediaControlPolicy.STATE_ACTIVE;
            default:
                return MediaControlPolicy.STATE_INACTIVE;
        }
    }

    private static Result safeFailure(MediaControlPolicy.Command command) {
        return command == MediaControlPolicy.Command.PAUSE_ONLY
                ? new Result(ROUTE_KEYMANAGER, MediaControlPolicy.KEY_PAUSE, "",
                        MediaControlPolicy.STATE_UNKNOWN)
                : nativeFallback(command, "", MediaControlPolicy.STATE_UNKNOWN);
    }

    private static Result nativeFallback(MediaControlPolicy.Command command, String pkg, int stateClass) {
        return new Result(ROUTE_NATIVE, MediaControlPolicy.keyFor(command), pkg, stateClass);
    }

    private static void logDecisionInput(MediaControlPolicy.Command command,
                                         List<MediaController> controllers, MediaController selected) {
        StringBuilder sb = new StringBuilder("command=").append(command).append(" sessions=[");
        for (int i = 0; i < controllers.size(); i++) {
            MediaController c = controllers.get(i);
            PlaybackState ps = safePlaybackState(c);
            if (i > 0) sb.append(", ");
            sb.append(i).append(':').append(safePackage(c))
                    .append(" state=").append(ps == null ? "null" : ps.getState())
                    .append(" actions=0x").append(Long.toHexString(ps == null ? 0L : ps.getActions()));
        }
        sb.append("] selected=").append(selected == null ? "none" : safePackage(selected));
        Log.i(TAG, sb.toString());
    }

    private static String safePackage(MediaController c) {
        try { return c == null || c.getPackageName() == null ? "" : c.getPackageName(); }
        catch (Exception e) { return ""; }
    }

    private static PlaybackState safePlaybackState(MediaController c) {
        try { return c == null ? null : c.getPlaybackState(); }
        catch (Exception e) { return null; }
    }

    private static boolean sameToken(MediaSession.Token a, MediaSession.Token b) {
        if (a == null || b == null) return a == b;
        try { return a.equals(b); } catch (Exception e) { return false; }
    }

    private static boolean containsToken(List<MediaSession.Token> tokens, MediaSession.Token target) {
        for (MediaSession.Token token : tokens) {
            if (sameToken(token, target)) return true;
        }
        return false;
    }
}
