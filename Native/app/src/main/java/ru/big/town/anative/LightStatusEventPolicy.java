package ru.big.town.anative;

/** Pure event-time policy for distinguishing a real external OFF from stale/command status. */
final class LightStatusEventPolicy {
    enum Decision {
        IGNORE,
        DEFER,
        CONFIRM
    }

    private LightStatusEventPolicy() {}

    static Decision classifyExternalOff(int autoLamp, int headLight,
                                        long eventElapsedRealtime,
                                        long decisionStartedElapsedRealtime,
                                        long frameAttemptElapsedRealtime,
                                        boolean frameAttemptTargetOn,
                                        boolean eventProtectedBeforeFrame,
                                        long commandEchoGuardMs) {
        if (autoLamp != 0 || headLight != 0) return Decision.IGNORE;
        if (eventElapsedRealtime <= decisionStartedElapsedRealtime) return Decision.IGNORE;
        if (eventProtectedBeforeFrame) return Decision.IGNORE;
        if (frameAttemptElapsedRealtime <= 0L) return Decision.CONFIRM;
        // Delivery may lag behind command dispatch.  A status captured before this exact frame
        // cannot be its echo, even if the main mailbox observes the frame first.
        if (eventElapsedRealtime < frameAttemptElapsedRealtime) return Decision.CONFIRM;
        long sinceAttempt = eventElapsedRealtime - frameAttemptElapsedRealtime;
        if (sinceAttempt >= commandEchoGuardMs) return Decision.CONFIRM;
        // OFF after an attempted LOW is ambiguous: it may be a physical manual action, but the
        // OEM also emits this transient while powering down. One finite wake-generation-fenced
        // adjudication resolves it if no newer status arrives. An attempted OFF is its own echo.
        return frameAttemptTargetOn ? Decision.DEFER : Decision.IGNORE;
    }
}
