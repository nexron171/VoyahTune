package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayUiScale

/** Process-local access for the host settings, using DiPlay's own preferences and session. */
object CarPlaySettingsAccess {
    private const val AVAILABILITY_PREFERENCES = "voyah_carplay_availability"

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean("enabled", true)

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", enabled).apply()
    }

    @JvmStatic
    fun fullscreen(context: Context): Boolean =
        context.getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean("fullscreen", false)

    @JvmStatic
    fun setFullscreen(context: Context, enabled: Boolean) {
        context.getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean("fullscreen", enabled).apply()
    }

    @JvmStatic
    fun autoConnect(context: Context): Boolean = DiPlayPreferences.autoConnect(context)

    @JvmStatic
    fun setAutoConnect(context: Context, enabled: Boolean) = DiPlayPreferences.saveAutoConnect(context, enabled)

    @JvmStatic
    fun uiScalePresets(): List<Int> = CarPlayUiScale.presets

    @JvmStatic
    fun uiScalePercent(context: Context): Int = AirPlayPersistence.loadUiScalePercent(context)

    @JvmStatic
    fun setUiScalePercent(context: Context, percent: Int) = AirPlayPersistence.saveUiScalePercent(context, percent)

    @JvmStatic
    fun themeByLight(context: Context): Boolean =
        AirPlayPersistence.loadCarPlayNightMode(context) == CarPlayNightMode.VEHICLE

    @JvmStatic
    fun setThemeByLight(context: Context, enabled: Boolean) {
        if (enabled) AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.VEHICLE)
        else if (themeByLight(context)) AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.SYSTEM)
    }

    @JvmStatic
    fun hasPhone(context: Context): Boolean = DiPlayPreferences.phoneAddress(context) != null

    @JvmStatic
    fun phoneName(context: Context): String = DiPlayPreferences.phoneName(context)

    @JvmStatic
    fun connectionState(): ConnectionState = when {
        CarPlayBackgroundSession.active -> ConnectionState.CONNECTED
        CarPlayBackgroundSession.hasSession() -> ConnectionState.CONNECTING
        else -> ConnectionState.DISCONNECTED
    }

    @JvmStatic
    fun disconnect(completion: Runnable) = CarPlayBackgroundSession.stop { completion.run() }
}
