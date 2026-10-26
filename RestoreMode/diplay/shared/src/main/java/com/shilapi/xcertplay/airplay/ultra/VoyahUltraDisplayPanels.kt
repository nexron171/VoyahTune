package com.shilapi.xcertplay.airplay.ultra

/** Panel-only experiment adapted from LIVI info.rs at f999f557 (GPL-3.0-or-later). */
internal object VoyahUltraDisplayPanels {
    const val CLUSTER_UID = "b7e6c5a0-3333-4000-8000-000000000003"

    fun build(displays: List<*>, virtualCluster: Boolean, themed: Boolean = false): List<Map<String, Any?>> {
        val screens = displays.filterIsInstance<Map<*, *>>().filter { it["uuid"] is String }
        fun panel(screen: Map<*, *>, uid: String, streams: List<String>, instruments: Boolean) =
            linkedMapOf<String, Any?>("uid" to uid, "widthPixels" to screen["widthPixels"],
                "heightPixels" to screen["heightPixels"], "widthPhysical" to screen["widthPhysical"],
                "heightPhysical" to screen["heightPhysical"],
                "properties" to if (instruments) listOf("showsInstruments") else emptyList<String>(),
                "initialVideoStreams" to streams.map { uuid ->
                    mapOf("uuid" to uuid, "originXPixels" to 0, "originYPixels" to 0, "zIndex" to 0)
                })
        val panels = screens.map { screen ->
            val uuid = screen["uuid"] as String
            val cluster = (screen["type"] as? Number)?.toInt() == 111
            val uid = if (themed) uuid else "$uuid-panel"
            panel(screen, uid, listOf(uuid), cluster).apply {
                if (themed && cluster) put("displayPlugins", listOf(mapOf("uid" to uid, "videoStreams" to listOf(uuid))))
            }
        }.toMutableList()
        if (virtualCluster && screens.isNotEmpty() && !(themed && screens.any { (it["type"] as? Number)?.toInt() == 111 })) {
            val sized = screens.firstOrNull { (it["type"] as? Number)?.toInt() == 111 } ?: screens.first()
            panels += panel(sized, "$CLUSTER_UID-panel", emptyList(), true).apply {
                put("displayPlugins", listOf(mapOf("uid" to "$CLUSTER_UID-plugin", "videoStreams" to emptyList<String>())))
            }
        }
        return panels
    }
}
