package com.shilapi.xcertplay.airplay

import kotlin.math.roundToInt

/** Discrete interface scales and display transformations for CarPlay. */
object CarPlayUiScale {
    const val DEFAULT = 100
    val presets = listOf(75, 85, DEFAULT, 115)

    fun sanitize(percent: Int): Int = percent.takeIf { it in presets } ?: DEFAULT

    fun label(percent: Int): String = when (sanitize(percent)) {
        75 -> "Smaller"
        85 -> "Small"
        115 -> "Large"
        else -> "Default"
    }

    /** iOS sizes controls physically; a wider reported screen makes them smaller on the same surface. */
    fun physicalSize(size: AirPlayPhysicalSizeMm, percent: Int): AirPlayPhysicalSizeMm {
        val scale = sanitize(percent)
        if (scale == DEFAULT) return size
        fun millimeters(value: Int): Int = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            (value.toDouble() * DEFAULT / scale).roundToInt(),
        )
        return AirPlayPhysicalSizeMm(millimeters(size.widthMm), millimeters(size.heightMm))
    }

    fun apply(display: AirPlayDisplayConfig, percent: Int): AirPlayDisplayConfig {
        val scale = sanitize(percent)
        if (scale == DEFAULT) return display
        require(display.widthPixels > 0 && display.heightPixels > 0)
        fun pixels(value: Int): Int = ((value.toLong() * 100 / scale + 1) / 2 * 2).toInt()
        val width = pixels(display.widthPixels)
        val height = pixels(display.heightPixels)
        // Never request an enlarged canvas beyond 4K. The host also checks its actual decoder.
        if (scale < DEFAULT && (maxOf(width, height) > 3840 || minOf(width, height) > 2160)) return display
        fun insets(value: AirPlayInsets?): AirPlayInsets? = value?.let {
            it.copy(
                top = (it.top * height.toDouble() / display.heightPixels).roundToInt(),
                bottom = (it.bottom * height.toDouble() / display.heightPixels).roundToInt(),
                left = (it.left * width.toDouble() / display.widthPixels).roundToInt(),
                right = (it.right * width.toDouble() / display.widthPixels).roundToInt(),
            )
        }
        return display.copy(widthPixels = width, heightPixels = height,
            viewArea = insets(display.viewArea), safeArea = insets(display.safeArea))
    }
}
