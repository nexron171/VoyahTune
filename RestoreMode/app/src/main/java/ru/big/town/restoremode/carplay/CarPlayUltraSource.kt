package ru.big.town.restoremode.carplay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.CarPlayUltraHost
import com.shilapi.xcertplay.airplay.ultra.*
import ru.big.town.common.CarPlayAudioProtocol
import ru.big.town.common.CarPlayClimateProtocol
import ru.big.town.restoremode.AdvanceActivity
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/** Native owns CAN; this session adapter combines its observations with the OEM public media service. */
class CarPlayUltraSource private constructor(
    private val context: Context,
    private val bridge: CarPlayVehicleBridge,
) : UltraClimateSource {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var listener: ((UltraClimateSample?) -> Unit)? = null
    @Volatile private var volumes = CarPlayAudioClient.Volumes(null, null)
    @Volatile private var volumeObservedAt = 0L
    private val firstVolume = CountDownLatch(1)
    private val audio = CarPlayAudioClient(context) { observed ->
        volumes = observed
        volumeObservedAt = SystemClock.elapsedRealtime()
        firstVolume.countDown()
        if (observed.levels[0] > 0) lastAudible = observed.levels[0]
        publish()
    }
    @Volatile private var lastAudible = 1
    private val radio = VoyahRadioClient(context, ::trace, ::publish)
    private val changed: () -> Unit = {
        val text = observed.getBundle("radioText")
        val band = text?.getString("band")?.let { runCatching { VoyahRadioOem.Band.valueOf(it) }.getOrNull() }
        radio.updateRadioText(if (text != null && band != null)
            VoyahRadioOem.RadioText(band, text.getLong("frequency"), text.getString("text")) else null)
        publish()
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            audio.query()
            publish()
            handler.postDelayed(this, 1000)
        }
    }

    override val supportsRadio get() = radio.supported
    init {
        handler.post { if (!closed) audio.start() }
    }

    private val advertisedMaximum: Int? by lazy {
        try { firstVolume.await(1500, TimeUnit.MILLISECONDS) }
        catch (cancelled: InterruptedException) { Thread.currentThread().interrupt() }
        volumes.maxima[0].takeIf { it in 1..255 }
    }
    override val supportsVolume get() = advertisedMaximum != null
    override val volumeRange get() = 0..(advertisedMaximum ?: 100)
    override val supportedVehicleControls = UltraVehicleControl.entries.toSet()

    override fun start(onSample: (UltraClimateSample?) -> Unit) {
        listener = onSample
        observers.add(changed)
        handler.post {
            if (closed) return@post
            audio.start()
            radio.start()
            handler.post(poll)
            publish()
        }
    }

    private fun publish() {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        val data = observed.takeIf { now - observedAt <= 6000 } ?: Bundle()
        fun number(name: String): Long? = if (data.containsKey(name)) data.getDouble(name)
            .takeIf { it.isFinite() }?.roundToLong() else null
        val fuelRange = number("fuelRange")
        val electricRange = number("electricRange")
        val currentVolumes = volumes
        val volume = currentVolumes.levels[0].takeIf { it >= 0 && now - volumeObservedAt <= 6000 }?.toLong()
        val vehicle = UltraVehicleReadings(
            tirePressureKpa = (data.getFloatArray("tires")?.toList() ?: List(4) { Float.NaN }).map {
                it.takeIf { value -> value.isFinite() && value > 0 && value <= 10 }?.let { value -> (value * 100.0).roundToLong() }
            },
            fuelPercent = number("fuel"), odometerKm = number("odometer"),
            rangeKm = if (fuelRange != null && electricRange != null) fuelRange + electricRange else null,
            volumeLevel = volume, muted = volume?.let { it == 0L }, batteryPercent = number("battery"),
            electricRangeKm = electricRange, fuelRangeKm = fuelRange,
            driveMode = if (data.containsKey("driveMode")) data.getInt("driveMode") else null,
        )
        val controls = UltraVehicleControl.entries.filter { data.containsKey(it.name) }
            .associateWith { data.getInt(it.name) }
        listener?.invoke(UltraClimateSample(Double.NaN, now, vehicle = vehicle,
            radio = radio.snapshot, vehicleControlValues = controls))
    }

    override fun writeRadio(request: UltraRadioRequest): UltraClimateWriteResult =
        if (closed) unavailable() else radio.write(request)

    override fun writeVolume(level: Int?, mute: Boolean?): UltraClimateWriteResult {
        if (closed || !supportsVolume || level == null && mute == null
            || level != null && level !in volumeRange
            || level != null && mute != null && mute != (level == 0)) return unavailable()
        val wanted = if (mute == true) 0 else level ?: lastAudible.coerceIn(volumeRange)
        val done = CountDownLatch(1)
        val requestedAt = SystemClock.elapsedRealtime()
        handler.post {
            if (!closed) audio.setVolume(CarPlayAudioProtocol.MEDIA, wanted)
            done.countDown()
        }
        try {
            if (!done.await(1, TimeUnit.SECONDS)) return unavailable()
            val deadline = SystemClock.elapsedRealtime() + 2500
            while (!closed && SystemClock.elapsedRealtime() < deadline) {
                if (volumeObservedAt >= requestedAt && volumes.levels[0] == wanted) return UltraClimateWriteResult(true, "Native volume confirmed")
                Thread.sleep(50)
            }
        } catch (cancelled: InterruptedException) { Thread.currentThread().interrupt() }
        return unavailable()
    }

    override fun writeVehicleControl(control: UltraVehicleControl, value: Int): UltraClimateWriteResult {
        if (closed || control.normalize(value) == null) return unavailable()
        if (control.isAppAction) {
            val done = CountDownLatch(1)
            var launched = false
            handler.post {
                try {
                    if (!closed) {
                        val intent = if (control == UltraVehicleControl.CAR_HOME)
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        else Intent(context, AdvanceActivity::class.java)
                        val options = ActivityOptions.makeBasic().apply { launchDisplayId = 0 }
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
                        launched = true
                    }
                } catch (unavailable: RuntimeException) { trace("Open vehicle UI failed: ${unavailable.javaClass.simpleName}") }
                finally { done.countDown() }
            }
            return try {
                UltraClimateWriteResult(done.await(2, TimeUnit.SECONDS) && launched, "Vehicle UI launch")
            } catch (cancelled: InterruptedException) { Thread.currentThread().interrupt(); unavailable() }
        }
        val done = CountDownLatch(1)
        var result = CarPlayClimateProtocol.UNAVAILABLE
        bridge.writeUltra(control.name, value.toDouble(), { !closed }) { error -> result = error; done.countDown() }
        return try {
            UltraClimateWriteResult(done.await(4, TimeUnit.SECONDS) && result == CarPlayClimateProtocol.SUCCESS,
                "Native result=$result")
        } catch (cancelled: InterruptedException) { Thread.currentThread().interrupt(); unavailable() }
    }

    override fun close() {
        closed = true
        observers.remove(changed)
        listener = null
        handler.post { handler.removeCallbacks(poll); audio.stop(); radio.close() }
    }

    private fun unavailable() = UltraClimateWriteResult(false, "Unavailable or unconfirmed")

    companion object {
        @Volatile private var observed = Bundle()
        @Volatile private var observedAt = 0L
        private val observers = CopyOnWriteArraySet<() -> Unit>()
        private fun trace(message: String) { Log.d("CarPlayUltra", message) }

        @JvmStatic fun update(values: Bundle?) {
            observed = values?.let(::Bundle) ?: Bundle()
            observedAt = SystemClock.elapsedRealtime()
            observers.forEach { it() }
        }

        @JvmStatic fun install(context: Context, bridge: CarPlayVehicleBridge) {
            val app = context.applicationContext
            CarPlayUltraHost.install {
                VoyahUltraProtocol({ CarPlayUltraSource(app, bridge) }, SystemClock::elapsedRealtime, ::trace,
                    themeProbe = true,
                    vehicleDataText = UltraVehicleDataText(
                        section = "Автомобиль", frontLeft = "Переднее левое колесо", frontRight = "Переднее правое колесо",
                        rearLeft = "Заднее левое колесо", rearRight = "Заднее правое колесо", range = "Общий запас хода",
                        odometer = "Пробег", fuel = "Топливо", fuelRange = "Запас хода на бензине", battery = "Заряд батареи",
                        electricRange = "Запас хода на электричестве", driveMode = "Режим движения",
                        driveModes = listOf("Эко", "Комфорт", "Спорт", "Бездорожье", "Индивидуальный", "Снег"),
                        unavailable = "Нет данных", bar = "бар", kilometers = "км", localeTag = "ru"),
                    vehicleControlsText = UltraVehicleControlsText(
                        section = "Управление автомобилем", driverMassage = "Массаж водителя", passengerMassage = "Массаж пассажира",
                        driverProgram = "Программа массажа водителя", passengerProgram = "Программа массажа пассажира",
                        lights = "Наружное освещение", carHome = "Меню автомобиля", off = "Выкл.", wave = "Волны", rollers = "Ролики",
                        lightModes = listOf("Выкл.", "Габариты", "Ближний свет", "Авто"), unavailable = "Нет данных", settings = "Настройки VoyahTune"))
            }
        }
    }
}
