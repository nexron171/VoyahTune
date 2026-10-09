package com.shilapi.xcertplay

/** OEM commands must stay disabled until a Voyah-specific adapter is implemented. */
object VoyahPlatformPolicy {
    const val vehicleIntegrationEnabled = false
    const val localAdbEnabled = false

    // VoyahTune owns updates and task launch; the standalone APK paths must stay inactive.
    const val standaloneAppEnabled = false
}
