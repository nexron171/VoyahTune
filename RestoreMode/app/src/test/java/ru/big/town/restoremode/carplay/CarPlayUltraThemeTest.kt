package ru.big.town.restoremode.carplay

import android.app.Application
import com.shilapi.xcertplay.CarPlayDock
import com.shilapi.xcertplay.CarPlaySettingsAccess
import com.shilapi.xcertplay.CarPlayUltraHost
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.ultra.VoyahUltraProtocol
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class CarPlayUltraThemeTest {
    @Test
    fun vehicleServicesKeepTheirPanelsAndUseThemeIdentifiersOnlyForAstonMartin() {
        val app = RuntimeEnvironment.getApplication()
        val factory =
            CarPlayUltraHost::class.java.getDeclaredField("factory").apply { isAccessible = true }
        val previousFactory = factory.get(null)
        CarPlayUltraSource.install(app, CarPlayVehicleBridge(app))
        try {
            for (dock in
                listOf(CarPlayDock.DRIVER_SIDE, CarPlayDock.BOTTOM, CarPlayDock.DUAL_SIDES)) {
                CarPlaySettingsAccess.setDock(app, dock)
                (CarPlayUltraHost.create() as VoyahUltraProtocol).use { extension ->
                    val theme = dock.theme
                    val config =
                        AirPlayConfig(
                            "test",
                            "02:00:00:00:00:01",
                            "02:00:00:00:00:02",
                            "950.7.1",
                            theme?.mainDisplay(30, 1) ?: AirPlayDisplayConfig(1920, 720),
                            vehicleTheme = theme,
                            protocolExtension = extension,
                            vehicleStateProtocol = true,
                            mutualAuthentication = true,
                        )
                    val info = AirPlayInfoPlist.build(config)
                    assertTrue(info.containsKey("vehicleStateProtocolInfo"))
                    val display = (info["displays"] as List<*>).first() as Map<*, *>
                    assertEquals(config.mainDisplayIdentifier, display["uuid"])
                    val panels = (info["displayPanels"] as List<*>).map { it as Map<*, *> }
                    val streams = panels.first()["initialVideoStreams"] as List<*>
                    assertEquals(
                        config.mainDisplayIdentifier,
                        (streams.single() as Map<*, *>)["uuid"],
                    )
                    assertTrue(panels.last().containsKey("displayPlugins"))
                    assertEquals(
                        if (theme == null) null else "Center_Display",
                        extension.displayIdentifier(110),
                    )
                }
            }
        } finally {
            factory.set(null, previousFactory)
        }
    }
}
