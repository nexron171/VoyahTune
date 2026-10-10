package com.shilapi.xcertplay

import android.app.Activity
import android.os.SystemClock
import com.shilapi.xcertplay.transport.VoyahVehicleSpeedSource

/** App-owned IPC keeps Native dependencies outside the vendored protocol implementation. */
object VoyahCarPlayIntegration {
    fun interface ProjectionResult { fun complete(success: Boolean) }
    interface Host {
        fun start()
        fun stop()
        fun audioFocus(stream: Int) {}
        fun openProjection(activity: Activity, displayId: Int, result: ProjectionResult): Boolean = false
        fun openDashboard(displayId: Int): Boolean = false
    }

    private var host: Host? = null
    val available: Boolean get() = host != null
    val speed = VoyahVehicleSpeedSource(SystemClock::elapsedRealtime)
    @Volatile var night: Boolean? = null
        private set
    @Volatile var swReason: Int = -1
        private set
    private var projectionMode = CarPlayNightMode.SYSTEM
    private var projectionFallbackNight = false

    @JvmStatic fun install(host: Host?) { this.host = host }
    internal fun start() { host?.start() }
    internal fun stop() { host?.stop(); reset() }
    internal fun audioFocus(stream: Int) { host?.audioFocus(stream) }
    internal fun openProjection(activity: Activity, displayId: Int, result: ProjectionResult): Boolean =
        host?.openProjection(activity, displayId, result) == true
    internal fun openDashboard(displayId: Int): Boolean = host?.openDashboard(displayId) == true

    @JvmStatic fun update(kmh: Int, gear: Int, elapsed: Long, reason: Int) {
        speed.update(kmh, gear, elapsed)
        if (swReason != reason) android.util.Log.i("VoyahCarPlayTheme",
            "Native swReason=$reason mode=${projectionMode.key} night=${nightForReason(reason)}")
        swReason = reason
        val nextNight = nightForReason(reason)
        if (night != nextNight) {
            night = nextNight
            if (projectionMode == CarPlayNightMode.VEHICLE) sendProjectionTheme()
        }
    }

    internal fun updateProjectionTheme(mode: CarPlayNightMode, fallbackNight: Boolean) {
        projectionMode = mode
        projectionFallbackNight = fallbackNight
        sendProjectionTheme()
    }

    private fun sendProjectionTheme() {
        CarPlayBackgroundSession.snapshot()?.controller?.setVehicleNightMode(
            projectionNight(projectionMode, night, projectionFallbackNight),
        )
    }

    @JvmStatic fun isProjectionOnMainDisplay(): Boolean =
        CarPlayBackgroundSession.snapshot()?.ownerDisplayId?.let { it in 0..1 } == true

    @JvmStatic fun reset() {
        speed.reset()
        swReason = -1
        night = null
        if (projectionMode == CarPlayNightMode.VEHICLE) sendProjectionTheme()
    }
    @JvmStatic fun requestSiri(): Boolean =
        CarPlayBackgroundSession.snapshot()?.controller?.requestSiri() == true

    internal fun nightForReason(reason: Int): Boolean? = when (reason) {
        0, 1 -> false
        2, 3, 4 -> true
        else -> null
    }
}
