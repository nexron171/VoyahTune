package ru.big.town.restoremode.carplay

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import ru.big.town.common.CarPlayAudioProtocol
import ru.big.town.common.CarPlayVehicleProtocol
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayBluetoothAudioBridgeTest {
    @Test
    fun sessionTokenSurvivesDelayedBindingAndReconnectButNotStop() {
        val sessions = mutableListOf<String?>()
        val service = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == CarPlayVehicleProtocol.NAVIGATION) {
                sessions += message.data.getString(CarPlayAudioProtocol.BLUETOOTH_SESSION)
            }
            true
        })
        val context = RuntimeEnvironment.getApplication()
        val name = ComponentName("ru.big.town.anative", "ru.big.town.anative.SetModesService")
        shadowOf(context).setComponentNameAndServiceForBindService(name, service.binder)
        val bridge = CarPlayVehicleBridge(context)
        bridge.start()
        bridge.bluetoothAudioSession("first-session")
        shadowOf(Looper.getMainLooper()).idle()
        try {
            assertEquals("first-session", sessions.last())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals("first-session", sessions.last())
            val connection = ReflectionHelpers.getField<ServiceConnection>(bridge, "connection")
            connection.onServiceDisconnected(name)
            connection.onServiceConnected(name, service.binder)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("first-session", sessions.last())
            bridge.bluetoothAudioSession(null)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(sessions.last())
            bridge.bluetoothAudioSession("second-session")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("second-session", sessions.last())
            bridge.stop()
            bridge.start()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(sessions.last())
        } finally {
            bridge.stop()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
