package com.shilapi.xcertplay.airplay

import java.util.function.BooleanSupplier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleStateDisplayPanelsTest {
    private val config = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
    )

    @After
    fun resetClimate() {
        VehicleClimate.install(null)
        VehicleClimate.update(emptyMap())
    }

    @Test
    fun climateDescribesOnlyTheMainVideoDisplay() {
        VehicleClimate.install(null)
        assertFalse(AirPlayInfoPlist.build(config).containsKey("displayPanels"))

        installClimate()
        val info = AirPlayInfoPlist.build(config)
        val panel = (info["displayPanels"] as List<*>).single() as Map<*, *>
        val stream = (panel["initialVideoStreams"] as List<*>).single() as Map<*, *>

        assertTrue(info.containsKey("vehicleStateProtocolInfo"))
        assertEquals(AirPlayInfoPlist.MAIN_UUID, stream["uuid"])
        assertEquals(1280, panel["widthPixels"])
        assertEquals(720, panel["heightPixels"])
        assertEquals(emptyList<String>(), panel["properties"])
        assertFalse(panel.containsKey("displayPlugins"))
    }

    @Test
    fun clusterKeepsItsVideoStreamWithoutInstrumentOrLocalUiDeclarations() {
        installClimate()
        val info = AirPlayInfoPlist.build(
            config.copy(cluster = AirPlayDisplayConfig(widthPixels = 960, heightPixels = 360)),
        )
        val displays = (info["displays"] as List<*>).map { it as Map<*, *> }
        val panels = (info["displayPanels"] as List<*>).map { it as Map<*, *> }

        assertEquals(2, panels.size)
        displays.zip(panels).forEach { (display, panel) ->
            val stream = (panel["initialVideoStreams"] as List<*>).single() as Map<*, *>

            assertEquals(display["uuid"], stream["uuid"])
            assertEquals(display["widthPixels"], panel["widthPixels"])
            assertEquals(display["heightPixels"], panel["heightPixels"])
            assertEquals(emptyList<String>(), panel["properties"])
            assertFalse(panel.containsKey("displayPlugins"))
        }
    }

    private fun installClimate() {
        VehicleClimate.install(object : VehicleClimate.Host {
            override fun write(
                field: String,
                value: Double,
                current: BooleanSupplier,
                result: VehicleClimate.Result,
            ) {
                error("Building display information must not send climate commands")
            }
        })
    }
}
