package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleStateDisplayPanelsTest {
    @Test
    fun climateAddsLocalPanelWithoutCreatingAnotherVideoStream() {
        val config = AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )
        VehicleClimate.install(null)
        val ordinary = AirPlayInfoPlist.build(config)
        assertFalse(ordinary.containsKey("displayPanels"))
        try {
            VehicleClimate.install(object : VehicleClimate.Host {
                override fun write(
                    field: String,
                    value: Double,
                    current: java.util.function.BooleanSupplier,
                    result: VehicleClimate.Result,
                ) = result.complete(-6727)
            })
            val climate = AirPlayInfoPlist.build(config)
            assertEquals(ordinary["displays"], climate["displays"])
            val panels = climate["displayPanels"] as List<*>
            assertEquals(2, panels.size)
            val main = panels[0] as Map<*, *>
            val mainStream = (main["initialVideoStreams"] as List<*>).single() as Map<*, *>
            assertEquals(AirPlayInfoPlist.MAIN_UUID, mainStream["uuid"])
            val local = panels[1] as Map<*, *>
            assertEquals(listOf("showsInstruments"), local["properties"])
            assertTrue((local["initialVideoStreams"] as List<*>).isEmpty())
            val plugin = (local["displayPlugins"] as List<*>).single() as Map<*, *>
            assertTrue((plugin["videoStreams"] as List<*>).isEmpty())
        } finally {
            VehicleClimate.install(null)
        }
    }
}
