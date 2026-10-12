package com.shilapi.xcertplay

import android.content.Context

/** Process-local access for the host settings, using DiPlay's own preferences and session. */
object CarPlaySettingsAccess {
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    @JvmStatic
    fun autoConnect(context: Context): Boolean = DiPlayPreferences.autoConnect(context)

    @JvmStatic
    fun setAutoConnect(context: Context, enabled: Boolean) = DiPlayPreferences.saveAutoConnect(context, enabled)

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
