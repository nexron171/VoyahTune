package com.shilapi.xcertplay

import android.os.SystemClock
import com.shilapi.xcertplay.transport.VoyahVehicleSpeedSource

/** App-owned IPC keeps Native dependencies outside the vendored protocol implementation. */
object VoyahCarPlayIntegration {
    interface Host {
        fun start()
        fun stop()
    }

    private var host: Host? = null
    val available: Boolean get() = host != null
    val speed = VoyahVehicleSpeedSource(SystemClock::elapsedRealtime)
    @Volatile var night: Boolean? = null
        private set

    @JvmStatic fun install(host: Host?) { this.host = host }
    internal fun start() { host?.start() }
    internal fun stop() { host?.stop(); reset() }

    @JvmStatic fun update(kmh: Int, gear: Int, elapsed: Long, reason: Int) {
        speed.update(kmh, gear, elapsed)
        val nextNight = nightForReason(reason)
        if (night != nextNight) {
            night = nextNight
            nextNight?.let { CarPlayBackgroundSession.snapshot()?.controller?.setVehicleNightMode(it) }
        }
    }

    @JvmStatic fun isProjectionOnMainDisplay(): Boolean =
        CarPlayBackgroundSession.snapshot()?.ownerDisplayId?.let { it in 0..1 } == true

    @JvmStatic fun reset() { speed.reset(); night = null }
    @JvmStatic fun requestSiri(): Boolean =
        CarPlayBackgroundSession.snapshot()?.controller?.requestSiri() == true

    internal fun nightForReason(reason: Int): Boolean? = when (reason) {
        0, 1 -> false
        2, 3, 4 -> true
        else -> null
    }
}
