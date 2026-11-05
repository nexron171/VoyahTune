package com.shilapi.xcertplay.orchestration

/**
 * Bounds wired AirPlay startup while allowing one retry after a late USB network appears.
 * Session activation or termination cancels the current timer; stale timer generations are ignored.
 */
internal class WiredStartWatchdog(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val secondRequestCanStillGoOut: () -> Boolean = { false },
    private val onHold: () -> Unit = {},
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private var armed = false
    private var finished = false
    private var rearmed = false
    private var held = false
    // The limit in force; a replaced timer that runs anyway finds a newer one and does nothing.
    private var generation = 0
    private var cancelTimer: (() -> Unit)? = null

    /** The first `carplay-start-session` starts the limit; a repeated one keeps it. */
    @Synchronized
    fun startSessionSent() {
        if (armed || finished) return
        armed = true
        arm(timeoutMillis)
    }

    /**
     * DiPlay's own second request after a late USB network ([WiredStartAgain]) gets a full limit,
     * once.
     */
    @Synchronized
    fun startSessionSentAgain() {
        if (!armed || finished || rearmed) return
        rearmed = true
        cancel()
        arm(timeoutMillis)
    }

    @Synchronized
    fun sessionActive() {
        finished = true
        cancel()
    }

    @Synchronized
    fun terminate() {
        finished = true
        cancel()
    }

    private fun arm(delayMillis: Long) {
        val armedGeneration = ++generation
        cancelTimer = schedule(delayMillis) { expire(armedGeneration) }
    }

    private fun expire(armedGeneration: Int) {
        val holding =
            synchronized(this) {
                if (finished || armedGeneration != generation) return
                if (!held && !rearmed && secondRequestCanStillGoOut()) {
                    held = true
                    arm(HELD_TIMEOUT_MILLIS - timeoutMillis)
                    true
                } else {
                    finished = true
                    cancelTimer = null
                    false
                }
            }
        if (holding) onHold() else onTimeout()
    }

    private fun cancel() {
        cancelTimer?.invoke()
        cancelTimer = null
    }

    companion object {
        /**
         * The same limit as the wireless first connection (WirelessStartupPolicy.FIRST_TCP_MILLIS).
         */
        const val TIMEOUT_MILLIS = 30_000L

        /**
         * Well past the latest USB network seen (30.2 s, #335) plus [WiredStartAgain.QUIET_MILLIS].
         */
        const val HELD_TIMEOUT_MILLIS = 45_000L
    }
}
