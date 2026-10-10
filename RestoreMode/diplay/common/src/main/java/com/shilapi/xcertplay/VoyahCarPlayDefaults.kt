package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

internal object VoyahCarPlayDefaults {
    fun initialize(context: Context) {
        val connectionPreferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        if (!connectionPreferences.contains("wireless_hotspot_mode")) {
            AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        }
        AirPlayPersistence.initializeAutomaticNavigationAudioChannel(context)
        val preferences = context.getSharedPreferences("voyah_carplay", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("car_brand_initialized", false)) {
            if (AirPlayPersistence.loadOemLabel(context) == "VoyahTune") {
                AirPlayPersistence.saveOemLabel(context, AirPlayPersistence.DEFAULT_OEM_LABEL)
            }
            preferences.edit().putBoolean("car_brand_initialized", true).apply()
        }

        if (preferences.getBoolean("initialized", false)) return

        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        AirPlayPersistence.saveHevcEnabled(context, false)
        preferences.edit().putBoolean("initialized", true).apply()
    }
}
