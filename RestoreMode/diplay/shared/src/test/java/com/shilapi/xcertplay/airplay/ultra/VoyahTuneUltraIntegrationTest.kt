package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.function.BooleanSupplier

class VoyahTuneUltraIntegrationTest {
    private fun protocol() = VoyahUltraProtocol({ object : UltraClimateSource {
        override val supportsRadio = true
        override fun start(onSample: (UltraClimateSample?) -> Unit) {
            onSample(UltraClimateSample(Double.NaN, 1000, vehicle = UltraVehicleReadings(batteryPercent = 65)))
        }
        override fun close() {}
    } }, { 1000L }, {}, themeProbe = true)

    private fun climate() {
        VehicleClimate.install(object : VehicleClimate.Host {
            override fun write(field: String, value: Double, current: BooleanSupplier, result: VehicleClimate.Result) {
                error("Configuration and reads must not write the vehicle")
            }
        })
        VehicleClimate.update(mapOf("driverTemperature" to 21.0))
    }

    @After fun cleanup() { VehicleClimate.install(null); VehicleClimate.update(emptyMap()) }

    @Test fun themedInfoPreservesOurClimateAndKeepsVehicleServicesSeparate() {
        climate()
        protocol().use { extension ->
            val theme = AirPlayVehicleTheme.DUAL_SIDE_DOCKS
            val config = AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "950.7.1",
                theme.mainDisplay(30, 1).copy(viewAreas = null), vehicleTheme = theme,
                vehicleStateProtocol = true, mutualAuthentication = true, protocolExtension = extension)
            val info = AirPlayInfoPlist.build(config)
            val descriptor = info["vehicleStateProtocolInfo"] as Map<*, *>
            assertEquals(2, descriptor["pluginCount"])
            val plugins = (descriptor["pluginConfigs"] as List<*>).map { it as Map<*, *> }
            assertEquals(setOf(0L, 1L), plugins.map { (it["pluginID"] as Number).toLong() }.toSet())
            assertEquals(VehicleStateProtocol.descriptor()["pluginConfigs"].let { it as List<*> }.single(), plugins[0])
            val vehicle = plugins[1]["accessories"] as List<*>
            assertTrue(vehicle.size >= 6)
            val display = (info["displays"] as List<*>).first() as Map<*, *>
            assertEquals("Center_Display", display["uuid"])
            val area = (display["viewAreas"] as List<*>).first() as Map<*, *>
            assertFalse(area.containsKey("viewAreaStatusBarEdge"))
            val panels = (info["displayPanels"] as List<*>).map { it as Map<*, *> }
            assertFalse(panels.first().containsKey("displayPlugins"))
            assertTrue(panels.last().containsKey("displayPlugins"))
            val ordinary = AirPlayInfoPlist.build(config.copy(vehicleTheme = null,
                vehicleStateProtocol = false, mutualAuthentication = false, protocolExtension = null))
            assertFalse(ordinary.containsKey("vehicleStateProtocolInfo"))
            assertFalse(ordinary.containsKey("uiSyncInfo"))
            assertEquals(AirPlayInfoPlist.MAIN_UUID, ((ordinary["displays"] as List<*>).first() as Map<*, *>)["uuid"])
        }
    }

    @Test fun bothPluginsReplyOnTheSameEncryptedChannelAndClimateAcceptsLegacyWrapper() {
        climate()
        val address = InetAddress.getByName("127.0.0.1")
        val read = ByteArray(32) { it.toByte() }
        val write = ByteArray(32) { (it + 35).toByte() }
        protocol().use { extension ->
            extension.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
            val response = extension.openChannel(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, read, write, address, emptyMap())
            extension.attachPending()
            RcsChannel.connect(InetSocketAddress(address, response["dataPort"] as Int), write, read).use { phone ->
                for (plugin in listOf(0L, 1L)) {
                    phone.send(CarAccessoryMessages.configRequest(plugin, 10 + plugin).asRcsMessage())
                    val reply = CarAccessoryMessages.reader(requireNotNull(phone.receiveNext(2000)).body)
                    assertEquals(plugin, reply.pluginId)
                    assertEquals(CafCommand.CONFIG_RESPONSE, reply.command)
                }
                phone.send(CarAccessoryMessages.registerRequest(0, 18, CafRegistration.Identifiers(listOf(520))).asRcsMessage())
                assertEquals(CafCommand.REGISTER_RESPONSE,
                    CarAccessoryMessages.reader(requireNotNull(phone.receiveNext(2000)).body).command)
                phone.send(CarAccessoryMessages.unregisterRequest(0, 19, listOf(520)).asRcsMessage())
                assertEquals(CafCommand.UNREGISTER_RESPONSE,
                    CarAccessoryMessages.reader(requireNotNull(phone.receiveNext(2000)).body).command)
                val request = CarAccessoryMessages.readRequest(0, 20, listOf(520))
                phone.send(CafRcsCodec.encode(request.envelope, CafBodyEncoding.BPLIST_WRAPPED).asRcsMessage())
                val responseBody = requireNotNull(phone.receiveNext(2000)).body
                assertEquals(CafBodyEncoding.BPLIST_WRAPPED, CafRcsCodec.encoding(responseBody))
                assertEquals(21.0, CarAccessoryMessages.reader(responseBody).valuesMap()[520])
            }
        }
    }
}
