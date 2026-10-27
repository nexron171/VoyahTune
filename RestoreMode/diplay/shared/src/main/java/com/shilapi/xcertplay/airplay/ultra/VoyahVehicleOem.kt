package com.shilapi.xcertplay.airplay.ultra

import kotlin.math.roundToLong

/**
 * OEM CanBusService objects (the 1.2.0 (7) build VoyahClient verifies) to the facts the phone is shown.
 * Field meanings as established on the car by VoyahTune (Docs/oem-cluster-telemetry.md): TPMSInfo
 * pressures are bar, Odometer.mBeenTravelingMileage is the total odometer in km with 0 as UNKNOWN,
 * H97's VoiceCarStatusListener labels mCanTravelMileage as petrol range. 1023 marks it invalid.
 */
object VoyahVehicleOem {
    val PRESSURES = listOf("leftFrontPressure", "rightFrontPressure", "leftRearPressure", "rightRearPressure")
    private const val INVALID_RANGE = 1023L

    fun tireKpa(raw: Map<String, Number>): List<Long?> = PRESSURES.map { key ->
        raw[key]?.toDouble()?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }?.let { (it * 100).roundToLong() }
    }

    fun odometerKm(raw: Map<String, Number>): Long? =
        raw["mBeenTravelingMileage"]?.toLong()?.takeIf { it in 1..9_999_999 }

    fun fuelRangeKm(raw: Map<String, Number>): Long? = raw["mCanTravelMileage"]?.toDouble()?.takeIf {
        it.isFinite() && it == kotlin.math.floor(it) && it >= 0.0 && it < INVALID_RANGE
    }?.toLong()

    /** A total is available only when both component ranges are known, including a real zero. */
    fun rangeKm(raw: Map<String, Number>): Long? = fuelRangeKm(raw)?.let { fuel -> electricRangeKm(raw)?.let { fuel + it } }

    fun electricRangeKm(raw: Map<String, Number>): Long? =
        raw["mEVCruisingRang_real"]?.toDouble()?.takeIf {
            it.isFinite() && it == kotlin.math.floor(it) && it in 0.0..1022.0
        }?.toLong()

    fun fuelPercent(fuel: Float?): Long? = fuel?.takeIf { it.isFinite() && it in 0f..100f }?.let { Math.round(it).toLong() }

    fun readings(fuel: Float?, odometer: Map<String, Number>, tires: Map<String, Number>,
        volumeLevel: Long?, muted: Boolean?, battery: Float? = null, driveMode: Int? = null) = UltraVehicleReadings(
        tirePressureKpa = tireKpa(tires), fuelPercent = fuelPercent(fuel), odometerKm = odometerKm(odometer),
        rangeKm = rangeKm(odometer), volumeLevel = volumeLevel, muted = muted,
        batteryPercent = fuelPercent(battery), electricRangeKm = electricRangeKm(odometer),
        driveMode = driveMode?.takeIf { it in 1..6 }, fuelRangeKm = fuelRangeKm(odometer))
}
