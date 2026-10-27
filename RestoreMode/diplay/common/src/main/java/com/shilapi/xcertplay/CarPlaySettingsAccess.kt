package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayUiScale

/** Process-local access for the host settings, using DiPlay's own preferences and session. */
object CarPlaySettingsAccess {
    private const val AVAILABILITY_PREFERENCES = "voyah_carplay_availability"

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        context
            .getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean("enabled", true)

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", enabled)
            .apply()
    }

    @JvmStatic
    fun ultra(context: Context): Boolean =
        context
            .getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean("ultra", true)

    @JvmStatic
    fun setUltra(context: Context, enabled: Boolean) {
        if (!enabled) {
            val selected = dock(context)
            CarPlayDock.save(
                context,
                if (selected == CarPlayDock.DUAL_SIDES) CarPlayDock.DRIVER_SIDE else selected,
            )
        }
        context
            .getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("ultra", enabled)
            .apply()
    }

    @JvmStatic
    fun fullscreen(context: Context): Boolean =
        context
            .getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean("fullscreen", false)

    @JvmStatic
    fun setFullscreen(context: Context, enabled: Boolean) {
        if (!enabled || !fullscreen(context)) {
            val selected = dock(context)
            CarPlayDock.save(
                context,
                if (selected == CarPlayDock.DUAL_SIDES) CarPlayDock.DRIVER_SIDE else selected,
            )
        }
        context
            .getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("fullscreen", enabled)
            .apply {
                if (!enabled) putBoolean("hide_system_bars", false)
            }
            .apply()
    }

    @JvmStatic fun dock(context: Context): CarPlayDock = CarPlayDock.load(context)

    @JvmStatic
    fun setDock(context: Context, dock: CarPlayDock) {
        val selected =
            when {
                dock == CarPlayDock.AUTOMATIC -> CarPlayDock.DRIVER_SIDE
                dock == CarPlayDock.DUAL_SIDES && !ultra(context) -> CarPlayDock.DRIVER_SIDE
                else -> dock
            }
        if (selected == CarPlayDock.DUAL_SIDES) {
            setFullscreen(context, true)
            context
                .getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("hide_system_bars", true)
                .apply()
        }
        CarPlayDock.save(context, selected)
    }

    @JvmStatic
    fun hideSystemBars(context: Context): Boolean =
        fullscreen(context) &&
            (dock(context) == CarPlayDock.DUAL_SIDES ||
                context
                    .getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
                    .getBoolean("hide_system_bars", false))

    @JvmStatic
    fun setHideSystemBars(context: Context, enabled: Boolean) {
        if (dock(context) == CarPlayDock.DUAL_SIDES) return
        context
            .getSharedPreferences(AVAILABILITY_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("hide_system_bars", enabled && fullscreen(context))
            .apply()
    }

    @JvmStatic fun autoConnect(context: Context): Boolean = DiPlayPreferences.autoConnect(context)

    @JvmStatic
    fun setAutoConnect(context: Context, enabled: Boolean) =
        DiPlayPreferences.saveAutoConnect(context, enabled)

    @JvmStatic fun uiScalePresets(): List<Int> = CarPlayUiScale.presets

    @JvmStatic
    fun uiScalePercent(context: Context): Int = AirPlayPersistence.loadUiScalePercent(context)

    @JvmStatic
    fun setUiScalePercent(context: Context, percent: Int) =
        AirPlayPersistence.saveUiScalePercent(context, percent)

    @JvmStatic
    fun themeByLight(context: Context): Boolean =
        AirPlayPersistence.loadCarPlayNightMode(context) == CarPlayNightMode.VEHICLE

    @JvmStatic
    fun setThemeByLight(context: Context, enabled: Boolean) {
        if (enabled) AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.VEHICLE)
        else if (themeByLight(context))
            AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.SYSTEM)
    }

    @JvmStatic
    fun hasPhone(context: Context): Boolean = DiPlayPreferences.phoneAddress(context) != null

    @JvmStatic fun phoneName(context: Context): String = DiPlayPreferences.phoneName(context)

    @JvmStatic
    fun connectionState(): ConnectionState =
        when {
            CarPlayBackgroundSession.active -> ConnectionState.CONNECTED
            CarPlayBackgroundSession.hasSession() -> ConnectionState.CONNECTING
            else -> ConnectionState.DISCONNECTED
        }

    @JvmStatic
    fun disconnect(completion: Runnable) = CarPlaySessionHosts.disconnect { completion.run() }
}
