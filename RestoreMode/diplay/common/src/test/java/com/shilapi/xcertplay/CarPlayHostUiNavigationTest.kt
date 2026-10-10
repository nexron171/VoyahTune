package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercise requestUI through the production session/controller without creating an Activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostUiNavigationTest {
    private class Fixture(openHostUi: (() -> Boolean)? = null) : AutoCloseable {
        val app = RuntimeEnvironment.getApplication()
        private val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        val controller = CarPlayController(app,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, {}, openHostUi = openHostUi)
        private val listener = controller.javaClass.getDeclaredField("sessionListener")
            .apply { isAccessible = true }.get(controller) as AirPlaySessionListener
        private val phones = mutableListOf<AirPlaySession>()
        val phone = newPhone()

        fun newPhone() = AirPlaySession(Socket(), config, identity, PairingStore(), null,
            listener, object : AirPlayMediaHandler {}).also { phones += it }

        fun activate(phone: AirPlaySession?) {
            controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }
                .set(controller, phone)
        }

        fun activePhone(): Any? = controller.javaClass.getDeclaredField("activeSession")
            .apply { isAccessible = true }.get(controller)

        fun requestUi(phone: AirPlaySession = this.phone): RtspMessage.Response {
            val body = BplistCodec.encode(mapOf("type" to "requestUI", "params" to emptyMap<String, Any>()))
            return phone.javaClass.getDeclaredMethod("handleCommand", RtspMessage.Request::class.java)
                .apply { isAccessible = true }
                .invoke(phone, RtspMessage.Request("POST", "/command", "RTSP/1.0", emptyMap(), body))
                as RtspMessage.Response
        }

        override fun close() {
            controller.close()
            controller.awaitClosed(2_000)
            phones.forEach { it.close() }
        }
    }

    @Test fun carIconUsesTheVoyahHostAndKeepsThePhoneSession() {
        val displays = mutableListOf<Int>()
        VoyahCarPlayIntegration.install(object : VoyahCarPlayIntegration.Host {
            override fun start() = Unit
            override fun stop() = Unit
            override fun openDashboard(displayId: Int): Boolean = displays.add(displayId)
        })
        try {
            Fixture { VoyahCarPlayIntegration.openDashboard(20) }.use { f ->
                f.activate(f.phone)
                assertEquals(200, f.requestUi().status)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(listOf(20), displays)
                assertSame(f.phone, f.activePhone())
                assertFalse(f.controller.isClosed())
                assertNull(shadowOf(f.app).nextStartedActivity)
            }
        } finally { VoyahCarPlayIntegration.install(null) }
    }

    @Test fun failedVoyahNavigationDoesNotOpenSystemHome() {
        var requests = 0
        Fixture { requests++; false }.use { f ->
            f.activate(f.phone)
            f.requestUi()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, requests)
            assertNull(shadowOf(f.app).nextStartedActivity)
            assertSame(f.phone, f.activePhone())
        }
    }

    @Test fun anOldPhoneCannotNavigateTheReplacementSession() {
        var requests = 0
        Fixture { requests++; true }.use { f ->
            f.activate(f.newPhone())
            f.requestUi()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, requests)
        }
    }

    @Test fun aQueuedNavigationCannotRunAfterThePhoneIsReplaced() {
        var requests = 0
        Fixture { requests++; true }.use { f ->
            f.activate(f.phone)
            f.requestUi()
            f.activate(f.newPhone())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, requests)
        }
    }

    @Test fun standaloneFallbackStillUsesSystemHome() {
        Fixture().use { f ->
            f.activate(f.phone)
            f.requestUi()
            shadowOf(Looper.getMainLooper()).idle()
            val intent = shadowOf(f.app).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_MAIN, intent.action)
            assertTrue(intent.hasCategory(android.content.Intent.CATEGORY_HOME))
        }
    }
}
