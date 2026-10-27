package com.shilapi.xcertplay.airplay

import java.util.function.BooleanSupplier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VehicleClimateDockTest {
    private val config = AirPlayConfig(
        deviceName = "test",
        vehicleStateProtocol = true,
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        cluster = AirPlayDisplayConfig(widthPixels = 960, heightPixels = 360),
    )

    @After
    fun close() {
        VehicleClimate.install(null)
        VehicleClimate.update(emptyMap())
    }

    @Test
    fun climateSelectsBottomDockOnMainDisplayAndKeepsClusterAutomatic() {
        installClimate()
        val info = AirPlayInfoPlist.build(config)
        val displays = (info["displays"] as List<*>).map { it as Map<*, *> }

        assertEquals(AirPlayInfoPlist.DOCK_EDGE_BOTTOM, views(displays[0]).single()["viewAreaStatusBarEdge"])
        assertFalse(views(displays[1]).single().containsKey("viewAreaStatusBarEdge"))

        val protocol = info["vehicleStateProtocolInfo"] as Map<*, *>
        val plugin = (protocol["pluginConfigs"] as List<*>).single() as Map<*, *>
        val accessories = plugin["accessories"] as List<*>
        val services = accessories.flatMap { (it as Map<*, *>)["services"] as List<*> }
        val fields = services.flatMap { (it as Map<*, *>)["characteristics"] as List<*> }
            .map { it as Map<*, *> }
        assertEquals(21.0, fields.single { it["iid"] == 520L }["initialValue"])
        assertEquals(22.5, fields.single { it["iid"] == 534L }["initialValue"])
    }

    @Test
    fun explicitDockEdgesAndViewAreaGeometryKeepPriority() {
        installClimate()
        val main = config.main.copy(
            viewAreas = listOf(
                AirPlayViewArea(1280, 720, dockEdge = AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE),
                AirPlayViewArea(1280, 720, dockEdge = AirPlayInfoPlist.DOCK_EDGE_BOTTOM),
                AirPlayViewArea(640, 720, originX = 640),
            ),
            initialViewArea = 2,
        )
        val display = (AirPlayInfoPlist.build(config.copy(main = main))["displays"] as List<*>)[0] as Map<*, *>
        val areas = views(display)

        assertEquals(2, display["initialViewArea"])
        assertEquals(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, areas[0]["viewAreaStatusBarEdge"])
        assertEquals(AirPlayInfoPlist.DOCK_EDGE_BOTTOM, areas[1]["viewAreaStatusBarEdge"])
        assertEquals(AirPlayInfoPlist.DOCK_EDGE_BOTTOM, areas[2]["viewAreaStatusBarEdge"])
        assertEquals(640, areas[2]["widthPixels"])
        assertEquals(640, areas[2]["originXPixels"])
        assertEquals(640, (areas[2]["safeArea"] as Map<*, *>)["widthPixels"])
    }

    @Test
    fun receiverWithoutClimateKeepsPhoneSelectedDock() {
        VehicleClimate.install(null)
        val info = AirPlayInfoPlist.build(config)
        val displays = (info["displays"] as List<*>).map { it as Map<*, *> }

        assertFalse(info.containsKey("vehicleStateProtocolInfo"))
        displays.forEach { display ->
            assertFalse(views(display).single().containsKey("viewAreaStatusBarEdge"))
        }
    }

    private fun views(display: Map<*, *>): List<Map<*, *>> =
        (display["viewAreas"] as List<*>).map { it as Map<*, *> }

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
        VehicleClimate.update(mapOf("driverTemperature" to 21.0, "passengerTemperature" to 22.5))
    }
}
