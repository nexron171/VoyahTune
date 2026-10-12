package ru.big.town.restoremode.carplay

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.*
import com.shilapi.xcertplay.VoyahCarPlayIntegration
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.*
import org.junit.After
import org.junit.Assert.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayVehicleSpeedPipelineTest {
    private lateinit var bridge: CarPlayVehicleBridge
    private lateinit var connection: ServiceConnection
    private lateinit var replies: Messenger
    private val name = ComponentName("ru.big.town.anative", "ru.big.town.anative.SetModesService")
    private val sent = mutableListOf<Iap2Frame>()
    private var tickNanos = 0L
    private lateinit var reporter: Iap2LocationReporter

    @Before
    fun prepare() {
        VoyahCarPlayIntegration.speed.stop()
        bridge = CarPlayVehicleBridge(RuntimeEnvironment.getApplication())
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(name, nativeService().binder)
        bridge.start()
        shadowOf(Looper.getMainLooper()).idle()
        connection = ReflectionHelpers.getField(bridge, "connection")
        connectNative()
        val noGps = object : Iap2LocationProvider {
            override fun start() = false
            override fun stop() = Unit
            override fun latestNmea(): String? = null
        }
        reporter = Iap2LocationReporter(VehicleSpeedLocationProvider(noGps, VoyahCarPlayIntegration.speed), {}, { tickNanos })
    }

    @After
    fun cleanup() {
        bridge.stop()
        VoyahCarPlayIntegration.speed.stop()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun nativeMessageBecomesRequestedSpeedPacketWithUnitsAndChecksum() {
        requestSpeed()
        receive(36, 3)
        tick()
        assertEquals(1, sent.size)
        assertEquals(0xfffb, sent.single().messageId)
        val parameter = Iap2BodyReader.of(sent.single()).list().single()
        assertEquals(0, parameter.id)
        assertEquals(0.toByte(), parameter.payload.last())
        val sentence = parameter.payload.dropLast(1).toByteArray().decodeToString().trimEnd()
        assertTrue(sentence, sentence.contains(",C,D,0,1,0.00,10.000*"))
        val body = sentence.substring(1, sentence.indexOf('*'))
        val checksum = body.fold(0) { value, character -> value xor character.code }
        assertEquals("%02X".format(checksum), sentence.substringAfter('*'))
        assertFalse(sentence.contains("GPGGA"))
        reporter.handle(Iap2Messages.buildRaw(0xfffc) {}) { sent += it }
        receive(72, 3)
        tick()
        assertEquals(1, sent.size)
    }

    @Test
    fun speedIsNotSentWithoutPhoneSubscriptionOrForInvalidSamples() {
        receive(36, 3)
        tick()
        assertTrue(sent.isEmpty())
        requestSpeed()
        receive(36, 3, SystemClock.elapsedRealtime() - 3001)
        tick()
        receive(36, -1)
        tick()
        receive(-1, 3)
        tick()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun disconnectedAndReplacedNativeBindingsCannotInjectOldSpeed() {
        requestSpeed()
        receive(36, 3)
        connection.onServiceDisconnected(name)
        tick()
        assertTrue(sent.isEmpty())
        val oldReplies = replies
        connectNative()
        receive(36, 3, target = oldReplies)
        tick()
        assertTrue(sent.isEmpty())
        receive(72, 1)
        tick()
        assertTrue(Iap2BodyReader.of(sent.single()).list().single().payload.decodeToString()
            .contains(",C,R,0,1,0.00,20.000*"))
    }

    private fun nativeService() = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == CarPlayVehicleProtocol.WATCH) replies = message.replyTo
            true
        })

    private fun connectNative() {
        connection.onServiceConnected(name, nativeService().binder)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(this::replies.isInitialized)
    }

    private fun requestSpeed() {
        reporter.handle(Iap2Messages.buildRaw(0xfffa) { void(4) }) { sent += it }
    }

    private fun receive(speed: Int, gear: Int, time: Long = SystemClock.elapsedRealtime(), target: Messenger = replies) {
        target.send(Message.obtain(null, CarPlayVehicleProtocol.STATE).apply {
            data = Bundle().apply {
                putInt(CarPlayVehicleProtocol.SPEED_KMH, speed)
                putInt(CarPlayVehicleProtocol.GEAR, gear)
                putLong(CarPlayVehicleProtocol.SPEED_TIME, time)
                putInt(CarPlayVehicleProtocol.SW_REASON, 0)
            }
        })
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tick() {
        tickNanos += 1_000_000_000L
        reporter.tick { sent += it }
    }
}
