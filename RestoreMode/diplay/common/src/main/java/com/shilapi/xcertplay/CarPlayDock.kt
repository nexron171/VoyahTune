package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayVehicleTheme

/**
 * Where CarPlay puts its dock (app shortcuts, clock and status). Automatic leaves it to the iPhone,
 * which picks the driver's side on most screens. A fixed choice declares one whole-screen view area per
 * edge, so switching between the driver's side and the bottom moves the dock without reconnecting.
 */
enum class CarPlayDock(val edge: Int?, val theme: AirPlayVehicleTheme? = null) {
    AUTOMATIC(null),
    DRIVER_SIDE(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE),
    BOTTOM(AirPlayInfoPlist.DOCK_EDGE_BOTTOM),
    DUAL_SIDES(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, AirPlayVehicleTheme.DUAL_SIDE_DOCKS);

    companion object {
        /** Keep the standard layout even when preferences contain a retired theme or edge. */
        fun load(@Suppress("UNUSED_PARAMETER") context: Context): CarPlayDock = DRIVER_SIDE

        /** Changing the theme or automatic layout requires a new session. */
        fun movesLive(from: CarPlayDock, to: CarPlayDock): Boolean =
            from.edge != null && to.edge != null && from.theme == null && to.theme == null
    }
}
