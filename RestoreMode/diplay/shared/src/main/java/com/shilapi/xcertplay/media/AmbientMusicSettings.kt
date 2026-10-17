package com.shilapi.xcertplay.media

import android.content.Context
import com.shilapi.xcertplay.VoyahPlatformPolicy

object AmbientMusicSettings {
    private const val PREFS = "ambient_music"

    data class Values(
        val enabled: Boolean = false,
        val music: Boolean = true,
        val colorMode: AmbientColorMode = AmbientColorMode.BEAT,
        val speed: AmbientColorSpeed = AmbientColorSpeed.STANDARD,
        val selectedColors: List<Int> = listOf(1),
        val colorCycle: Boolean = false,
        val color: Int = 1,
        val brightness: Int = 0,
        val area: Int = 3,
        val colorSource: AmbientColorSource = AmbientColorSource.SELECTED,
        val idle: AmbientIdleBehavior = AmbientIdleBehavior.STEADY,
        // The car owns the lamps while its music rhythm is active.
        val carRhythm: Boolean = false,
        val carRhythmFeed: Boolean = false,
    )

    fun load(context: Context): Values {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun boolean(key: String, fallback: Boolean) =
            runCatching { preferences.getBoolean(key, fallback) }.getOrDefault(fallback)
        fun integer(key: String, fallback: Int) =
            runCatching { preferences.getInt(key, fallback) }.getOrDefault(fallback)
        fun text(key: String, fallback: String) =
            runCatching { preferences.getString(key, fallback) ?: fallback }.getOrDefault(fallback)

        val color = integer("color", 1).coerceIn(1, 31)
        val palette = runCatching {
            preferences.getStringSet("selectedColors", null)?.mapNotNull { it.toIntOrNull() }
        }.getOrNull()
        return Values(
            enabled = VoyahPlatformPolicy.vehicleIntegrationEnabled && boolean("enabled", false),
            music = boolean("music", true),
            colorMode = runCatching { AmbientColorMode.valueOf(text("colorMode", "BEAT")) }
                .getOrDefault(AmbientColorMode.BEAT),
            speed = runCatching { AmbientColorSpeed.valueOf(text("speed", "STANDARD")) }
                .getOrDefault(AmbientColorSpeed.STANDARD),
            selectedColors = normalizeAmbientPalette(palette ?: listOf(color), color),
            colorCycle = boolean("colorCycle", false),
            color = color,
            brightness = integer("brightness", 0).coerceIn(0, 6),
            area = integer("area", 3).coerceIn(1, 3),
            colorSource = runCatching { AmbientColorSource.valueOf(text("colorSource", "SELECTED")) }
                .getOrDefault(AmbientColorSource.SELECTED),
            idle = runCatching { AmbientIdleBehavior.valueOf(text("idle", "STEADY")) }
                .getOrDefault(AmbientIdleBehavior.STEADY),
            carRhythm = VoyahPlatformPolicy.vehicleIntegrationEnabled && boolean("carRhythm", false),
            carRhythmFeed = VoyahPlatformPolicy.vehicleIntegrationEnabled && boolean("carRhythmFeed", false),
        )
    }

    fun save(context: Context, values: Values) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", values.enabled)
            .putBoolean("music", values.music)
            .putBoolean("colorCycle", values.colorCycle)
            .putString("colorMode", values.colorMode.name)
            .putString("speed", values.speed.name)
            .putString("colorSource", values.colorSource.name)
            .putString("idle", values.idle.name)
            .putStringSet(
                "selectedColors",
                normalizeAmbientPalette(values.selectedColors, values.color).map { it.toString() }.toSet(),
            )
            .putInt("color", values.color.coerceIn(1, 31))
            .putInt("brightness", values.brightness.coerceIn(0, 6))
            .putInt("area", values.area.coerceIn(1, 3))
            .putBoolean("carRhythm", values.carRhythm)
            .putBoolean("carRhythmFeed", values.carRhythmFeed)
            .apply()
        AmbientMusicController.settingsChanged(context)
    }
}
