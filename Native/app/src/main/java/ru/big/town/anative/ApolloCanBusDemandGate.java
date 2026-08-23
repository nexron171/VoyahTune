package ru.big.town.anative;

/**
 * Android-free client-demand and idle-release generation gate for Apollo's CanBus binding.
 *
 * <p>The gate deliberately tracks only whether the Apollo UI currently needs the transport.
 * Binder identity, schema and write generations remain owned by {@link ApolloTlcService}. Every
 * acquire/release invalidates an already queued idle callback, so a configuration-change re-entry
 * cannot tear down the newly active session.</p>
 */
final class ApolloCanBusDemandGate {
    static final int REJECTED_GENERATION = -1;
    static final long NO_QUERY_SESSION = 0L;
    static final int ACQUIRE_REJECTED = -1;
    static final int ACQUIRE_EXISTING = 0;
    static final int ACQUIRE_NEW = 1;

    private int generation;
    private long currentSessionToken;
    private long runningQuerySession;
    private long trailingQuerySession;
    private boolean clientActive;
    private boolean closed;

    /**
     * Acquires one monotonically versioned UI session.
     *
     * <p>A released token is a tombstone and can never be reactivated. This matters because the
     * query travels through Messenger/startService while release travels through a permission-
     * gated broadcast; either IPC may reach the worker first.</p>
     */
    synchronized int acquire(long sessionToken) {
        if (closed || sessionToken <= 0L || sessionToken < currentSessionToken) {
            return ACQUIRE_REJECTED;
        }
        if (sessionToken == currentSessionToken) {
            return clientActive ? ACQUIRE_EXISTING : ACQUIRE_REJECTED;
        }
        currentSessionToken = sessionToken;
        clientActive = true;
        generation++;
        return ACQUIRE_NEW;
    }

    /**
     * Releases or tombstones a session. A newer release also retires any older active session,
     * making release-before-acquire delivery safe.
     *
     * @return true when the current demand state changed and transport cleanup should be armed.
     */
    synchronized boolean release(long sessionToken) {
        if (closed || sessionToken <= 0L || sessionToken < currentSessionToken) return false;
        if (sessionToken == currentSessionToken && !clientActive) return false;
        currentSessionToken = sessionToken;
        clientActive = false;
        generation++;
        return true;
    }

    synchronized boolean isActive() {
        return !closed && clientActive;
    }

    synchronized boolean isActive(long sessionToken) {
        return !closed && clientActive && sessionToken > 0L
                && sessionToken == currentSessionToken;
    }

    /**
     * Starts a refresh or coalesces it. Only a newer UI session earns one trailing refresh;
     * duplicate requests from the same visible session do not multiply the five CAN reads.
     */
    synchronized boolean beginQuery(long sessionToken) {
        if (closed || sessionToken <= 0L) return false;
        if (runningQuerySession == NO_QUERY_SESSION) {
            runningQuerySession = sessionToken;
            return true;
        }
        if (sessionToken > runningQuerySession
                && sessionToken > trailingQuerySession) {
            trailingQuerySession = sessionToken;
        }
        return false;
    }

    /** Completes the running refresh and atomically reserves at most one newer trailing refresh. */
    synchronized long finishQuery(long sessionToken) {
        if (sessionToken <= 0L || sessionToken != runningQuerySession) {
            return NO_QUERY_SESSION;
        }
        long next = trailingQuerySession;
        trailingQuerySession = NO_QUERY_SESSION;
        runningQuerySession = next;
        return next;
    }

    synchronized void abandonQuery(long sessionToken) {
        if (sessionToken != runningQuerySession) return;
        runningQuerySession = NO_QUERY_SESSION;
        trailingQuerySession = NO_QUERY_SESSION;
    }

    /** Invalidates a queued idle callback without changing the versioned UI-session tombstone. */
    synchronized void invalidateIdleRelease() {
        if (!closed) generation++;
    }

    /** Arms one idle callback only when no client or protected operation needs the transport. */
    synchronized int armIdleRelease(boolean operationPending) {
        if (closed || clientActive || operationPending) return REJECTED_GENERATION;
        return ++generation;
    }

    synchronized boolean isIdleReleaseCurrent(int candidateGeneration,
                                              boolean operationPending) {
        return !closed && !clientActive && !operationPending
                && candidateGeneration > 0 && candidateGeneration == generation;
    }

    synchronized void close() {
        closed = true;
        clientActive = false;
        runningQuerySession = NO_QUERY_SESSION;
        trailingQuerySession = NO_QUERY_SESSION;
        generation++;
    }

    /** Existing bounded exponential reconnect policy, extracted for deterministic host tests. */
    static long reconnectDelayMs(int attempt, long baseMs, long maxMs) {
        int exponent = Math.min(Math.max(0, attempt), 4);
        return Math.min(maxMs, baseMs << exponent);
    }
}
