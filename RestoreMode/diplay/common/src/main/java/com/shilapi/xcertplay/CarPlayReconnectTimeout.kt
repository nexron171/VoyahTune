package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** One recovery window, including deep sleep and all transport retries. Main-thread confined. */
internal class CarPlayReconnectTimeout(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
    private val onExpired: () -> Unit,
) {
    private var startedAtMillis: Long? = null
    private var expired = false
    private val expiration = Runnable { check() }

    fun begin(): Boolean {
        if (startedAtMillis == null && !expired) {
            startedAtMillis = elapsedMillis()
            handler.postDelayed(expiration, WINDOW_MILLIS)
        }
        return check()
    }

    fun check(): Boolean {
        if (expired) return false
        val startedAt = startedAtMillis ?: return true
        val remainingMillis = WINDOW_MILLIS - (elapsedMillis() - startedAt)
        if (remainingMillis > 0) return true
        expired = true
        handler.removeCallbacks(expiration)
        onExpired()
        return false
    }

    fun reset() {
        handler.removeCallbacks(expiration)
        startedAtMillis = null
        expired = false
    }

    private companion object {
        const val WINDOW_MILLIS = 5 * 60 * 1_000L
    }
}
