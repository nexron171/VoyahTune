package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset

/** Published vehicle resources selected by iAP2; the iPhone obtains the package from Apple. */
enum class AirPlayVehicleTheme(
    val assetIdentifier: String,
    val assetVersion: Long,
    val sdkVersion: String,
    val mainDisplayIdentifier: String,
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPhysicalMm: Int,
    val heightPhysicalMm: Int,
) {
    DUAL_SIDE_DOCKS("44C9FA31EC", 985, "19.0", "Center_Display", 1920, 720, 311, 116);

    val clusterAsset: Iap2ClusterAsset
        get() = Iap2ClusterAsset(assetIdentifier, assetVersion)

    fun mainDisplay(fps: Int, primaryInputDevice: Int): AirPlayDisplayConfig =
        AirPlayDisplayConfig(
            widthPixels = widthPixels,
            heightPixels = heightPixels,
            widthPhysicalMm = widthPhysicalMm,
            heightPhysicalMm = heightPhysicalMm,
            fps = fps,
            primaryInputDevice = primaryInputDevice,
            safeAreaDrawOutside = true,
            // The theme uses a fixed coordinate space, including both status bars.
            viewAreas = listOf(
                AirPlayViewArea(widthPixels, heightPixels, dockEdge = AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE),
            ),
        )
}
