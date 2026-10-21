package com.shilapi.xcertplay.orchestration

/**
 * A time limit for the iPhone's AirPlay session after a wired `carplay-start-session`.
 *
 * The wired control loop has no limit of its own, so an iPhone that accepts the start request but never opens the
 * session kept DiPlay on "connecting" until the cable was replugged (#889, #890). On a working head unit the session
 * is active 2 to 13 s after the request. When the limit passes, [onTimeout] ends the attempt so the usual retry starts
 * a new one, which asks the iPhone to switch modes again, as a replug does.
 */
internal class WiredStartWatchdog(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private var armed = false
    private var finished = false
    private var rearmed = false
    // The limit in force; a replaced timer that runs anyway finds a newer one and does nothing.
    private var generation = 0
    private var cancelTimer: (() -> Unit)? = null

    /** The first `carplay-start-session` starts the limit; a repeated one keeps it. */
    @Synchronized fun startSessionSent() {
        if (armed || finished) return
        armed = true
        arm()
    }

    /** DiPlay's own second request after a late USB network ([WiredStartAgain]) gets a full limit, once. */
    @Synchronized fun startSessionSentAgain() {
        if (!armed || finished || rearmed) return
        rearmed = true
        cancel()
        arm()
    }

    @Synchronized fun sessionActive() {
        finished = true
        cancel()
    }

    @Synchronized fun terminate() {
        finished = true
        cancel()
    }

    private fun arm() {
        val armedGeneration = ++generation
        cancelTimer = schedule(timeoutMillis) { expire(armedGeneration) }
    }

    private fun expire(armedGeneration: Int) {
        synchronized(this) {
            if (finished || armedGeneration != generation) return
            finished = true
            cancelTimer = null
        }
        onTimeout()
    }

    private fun cancel() {
        cancelTimer?.invoke()
        cancelTimer = null
    }

    companion object {
        /** The same limit as the wireless first connection (WirelessStartupPolicy.FIRST_TCP_MILLIS). */
        const val TIMEOUT_MILLIS = 30_000L
    }
}
