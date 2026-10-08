package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager

internal object VoyahCarPlayDefaults {
    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences("voyah_carplay", Context.MODE_PRIVATE)
        if (preferences.getBoolean("initialized", false)) return
        AirPlayPersistence.saveOemLabel(context, "VoyahTune")
        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        AirPlayPersistence.saveNavigationStreamType(context, AudioManager.STREAM_MUSIC)
        AirPlayPersistence.saveHevcEnabled(context, false)
        preferences.edit().putBoolean("initialized", true).apply()
    }
}
