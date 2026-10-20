// SPDX-License-Identifier: GPL-3.0-or-later
// Display declarations adapted from f-io/LIVI, Copyright 2026 Lasse Heitgres.
package com.shilapi.xcertplay.airplay

internal object VehicleStateDisplayPanels {
    private const val LOCAL_PANEL_UUID = "b7e6c5a0-3333-4000-8000-000000000003"
    private val dimensions = listOf("widthPixels", "heightPixels", "widthPhysical", "heightPhysical")

    fun build(
        displays: List<Map<String, Any?>>,
        namedMainPanel: String? = null,
    ): List<Map<String, Any?>> {
        val panels = displays.map { display ->
            val uuid = display.getValue("uuid")
            dimensions.associateWith { display[it] }.toMutableMap().apply {
                this["uid"] = if (uuid == namedMainPanel) uuid else "$uuid-panel"
                this["properties"] = if (uuid == AirPlayInfoPlist.ALT_UUID) {
                    listOf("showsInstruments")
                } else {
                    emptyList<String>()
                }
                this["initialVideoStreams"] = listOf(
                    mapOf("uuid" to uuid, "originXPixels" to 0, "originYPixels" to 0, "zIndex" to 0),
                )
            }
        }.toMutableList()

        // iOS 27 exposes Climate only with an instruments panel and Local UI plug-in.
        // This compatibility panel owns no video stream or Android display.
        val sized = displays.firstOrNull { it["uuid"] == AirPlayInfoPlist.ALT_UUID } ?: displays.first()
        panels += dimensions.associateWith { sized[it] }.toMutableMap().apply {
            this["uid"] = "$LOCAL_PANEL_UUID-panel"
            this["properties"] = listOf("showsInstruments")
            this["initialVideoStreams"] = emptyList<Any>()
            this["displayPlugins"] = listOf(
                mapOf("uid" to "$LOCAL_PANEL_UUID-plugin", "videoStreams" to emptyList<String>()),
            )
        }
        return panels
    }
}
