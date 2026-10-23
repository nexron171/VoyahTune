package ru.big.town.restoremode.carplay

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import ru.big.town.common.CarPlayVehicleProtocol
import ru.big.town.common.CarPlayWifiScanProtocol
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayWifiScanBridgeTest {
    private lateinit var bridge: CarPlayVehicleBridge
    private lateinit var service: Messenger
    private val requests = mutableListOf<Boolean>()
    private val name = ComponentName("ru.big.town.anative", "ru.big.town.anative.SetModesService")

    @Before
    fun prepare() {
        service = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == CarPlayVehicleProtocol.NAVIGATION) {
                requests += message.data.getBoolean(CarPlayWifiScanProtocol.PAUSED)
                assertTrue(message.replyTo != null)
            }
            true
        })
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(name, service.binder)
        bridge = CarPlayVehicleBridge(RuntimeEnvironment.getApplication())
        bridge.start()
        idle()
    }

    @After
    fun cleanup() {
        bridge.stop()
        idle()
    }

    @Test
    fun overlappingControllersKeepThePauseUntilTheLastOwnerCloses() {
        val previous = Any()
        val replacement = Any()
        bridge.acquireWifiScanPause(previous) { true }
        bridge.acquireWifiScanPause(replacement) { true }
        idle()
        assertTrue(requests.last())
        bridge.releaseWifiScanPause(previous)
        idle()
        assertTrue(requests.last())
        bridge.releaseWifiScanPause(replacement)
        idle()
        assertFalse(requests.last())
    }

    @Test
    fun closingBeforeTheMainThreadReceivesTheLeaseCannotPauseScanning() {
        val current = AtomicBoolean(true)
        bridge.acquireWifiScanPause(Any(), current::get)
        current.set(false)
        idle()
        assertTrue(requests.none { it })
    }

    @Test
    fun reconnectPublishesCurrentOwnershipAndStoppedSessionsDoNotRetainIt() {
        bridge.acquireWifiScanPause(Any()) { true }
        idle()
        val connection = ReflectionHelpers.getField<ServiceConnection>(bridge, "connection")
        connection.onServiceDisconnected(name)
        connection.onServiceConnected(name, service.binder)
        idle()
        assertTrue(requests.last())
        bridge.stop()
        bridge.start()
        idle()
        assertFalse(requests.last())
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
