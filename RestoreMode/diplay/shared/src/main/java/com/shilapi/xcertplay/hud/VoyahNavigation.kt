package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Values observed by VoyahPlay on H97; unknown maneuvers must not invent an arrow. */
internal object VoyahManeuverCodes {
    fun icon(type: Int, drivingSide: Int): Int = when (type) {
        1, 20 -> 7
        2, 21 -> 3
        13, 22, 49, 52 -> 8
        14, 23, 50, 53 -> 2
        47 -> 6
        48 -> 4
        4, 18, 19, 26 -> if (drivingSide == 1) 0 else 5
        3, 5, 8, 9, 11, 51 -> 1
        6, 7, in 28..46 -> if (drivingSide == 1) 0 else 9
        else -> 0
    }
}

data class VoyahManeuver(val icon: Int, val distanceMeters: Int, val road: String)

internal class VoyahNavigationState(nanoTime: () -> Long = System::nanoTime) {
    private var owner: Any? = null
    private val route = BydHudRouteState(nanoTime = nanoTime, keepAcrossNoRoute = false)

    @Synchronized fun begin(session: Any) {
        if (owner === session) return
        owner = session
        route.clear()
    }

    @Synchronized fun end(session: Any) {
        if (owner !== session) return
        owner = null
        route.clear()
    }

    @Synchronized fun accept(session: Any, frame: Iap2Frame) {
        if (owner === session) route.accept(frame.messageId, frame.payload)
    }

    @Synchronized fun snapshot(): VoyahManeuver? = route.currentApple()?.let {
        VoyahManeuver(VoyahManeuverCodes.icon(it.type, it.drivingSide),
            it.distanceMeters.coerceAtLeast(0), it.road.replace('\u0000', ' ').take(120))
    }
}

/** Native pulls a bounded snapshot; the iAP2 thread never calls an OEM Binder. */
object VoyahNavigation {
    private val state = VoyahNavigationState()
    internal fun begin(owner: Any) = state.begin(owner)
    internal fun end(owner: Any) = state.end(owner)
    internal fun onFrame(owner: Any, frame: Iap2Frame) = state.accept(owner, frame)
    @JvmStatic fun snapshot(): VoyahManeuver? = state.snapshot()
}
