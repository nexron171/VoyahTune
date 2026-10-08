package com.shilapi.xcertplay

import android.content.Context

internal object VoyahCarPlayDefaults {
    fun initialize(context: Context) {
        AirPlayPersistence.initializeAutomaticNavigationAudioChannel(context)
        val preferences = context.getSharedPreferences("voyah_carplay", Context.MODE_PRIVATE)
        if (preferences.getBoolean("initialized", false)) return
        AirPlayPersistence.saveOemLabel(context, "VoyahTune")
        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        AirPlayPersistence.saveHevcEnabled(context, false)
        preferences.edit().putBoolean("initialized", true).apply()
    }
}
