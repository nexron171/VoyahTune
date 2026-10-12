package com.shilapi.xcertplay.media

import android.content.Context
import android.content.SharedPreferences

/** Shared by the settings screen and retained projection sinks. */
object CarPlayAudioSettings {
    const val DUCK_MUSIC = "duck_music_for_navigation"

    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("voyah_carplay_audio", Context.MODE_PRIVATE)

    @JvmStatic
    fun duckMusic(context: Context): Boolean = preferences(context).getBoolean(DUCK_MUSIC, true)

    @JvmStatic
    fun setDuckMusic(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(DUCK_MUSIC, enabled).apply()
    }
}
