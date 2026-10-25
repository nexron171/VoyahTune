// SPDX-License-Identifier: GPL-3.0-or-later
// Display declarations adapted from f-io/LIVI, Copyright 2026 Lasse Heitgres.
package com.shilapi.xcertplay.airplay

internal object VehicleStateDisplayPanels {
    private val dimensionKeys = listOf("widthPixels", "heightPixels", "widthPhysical", "heightPhysical")

    fun build(
        displays: List<Map<String, Any?>>,
        namedMainPanel: String? = null,
    ): List<Map<String, Any?>> = displays.map { display ->
        val streamIdentifier = display.getValue("uuid")

        dimensionKeys.associateWith { display[it] }.toMutableMap().apply {
            this["uid"] = if (streamIdentifier == namedMainPanel) streamIdentifier else "$streamIdentifier-panel"
            this["properties"] = emptyList<String>()
            this["initialVideoStreams"] = listOf(
                mapOf("uuid" to streamIdentifier, "originXPixels" to 0, "originYPixels" to 0, "zIndex" to 0),
            )
        }
    }
}
