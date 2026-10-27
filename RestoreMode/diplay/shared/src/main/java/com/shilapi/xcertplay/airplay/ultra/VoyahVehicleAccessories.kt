package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import kotlin.math.roundToLong

/**
 * Read-only vehicle facts shown to the phone, as the car reported them. A null is "not known" and
 * reaches the phone as no value (-6727); nothing here is estimated or filled with zeros.
 */
data class UltraVehicleReadings(
    /** Front left, front right, rear left, rear right. */
    val tirePressureKpa: List<Long?> = listOf(null, null, null, null),
    val fuelPercent: Long? = null,
    val odometerKm: Long? = null,
    /** Total of the independently available petrol and electric ranges, in km. */
    val rangeKm: Long? = null,
    /** Android media stream index, in the source's advertised volume range. */
    val volumeLevel: Long? = null,
    val muted: Boolean? = null,
    val batteryPercent: Long? = null,
    /** Electric-only range from the OEM real-range field, never the combined fuel range. */
    val electricRangeKm: Long? = null,
    /** H97C DRIVING_MODE_SET: 1 Eco, 2 Comfort, 3 Sport, 4 Off-road, 5 Individual, 6 Snow. */
    val driveMode: Int? = null,
    /** Petrol range from OEM mCanTravelMileage, identified by the H97 voice implementation. */
    val fuelRangeKm: Long? = null,
)

/**
 * Tires, fuel, trip computer and audio in the shapes LIVI describes them to iOS (vehicle_state.rs,
 * accessories 0x0A000001, 0x09000006, 0x04000001, 0x02000001). IIDs follow LIVI and do not collide
 * with the climate, seats, resources, navigation or automaker settings trees.
 */
internal object VoyahVehicleDescription {
    const val FUEL_LEVEL = 1538L
    const val FUEL_LOW_MARKER = 1539L
    const val FUEL_RANGE_KM = 1541L
    const val FUEL_RANGE_MILES = 1542L
    const val FUEL_RANGE_HIDDEN = 1543L
    const val RANGE_KM = 1794L
    const val RANGE_MILES = 1795L
    const val RANGE_HIDDEN = 1796L
    const val ODOMETER_KM = 1798L
    const val ODOMETER_MILES = 1799L
    const val VOLUME = 2306L
    const val VOLUME_TYPE = 2307L
    const val VOLUME_LABEL = 2308L
    const val MUTE = 2309L
    private const val TIRES = 2048L
    private const val LOW_FUEL_PERCENT = 10L
    private const val MAIN_VOLUME_TYPE = 0L
    private const val LAST_VOLUME_TYPE = 7L
    const val PRESSURE_UNDEFINED = 0L
    const val SENSOR_ACTIVE = 1L
    const val SENSOR_WAITING = 3L
    private const val KILOPASCALS = 932L
    private const val KILOMETERS = 151L
    private const val MILES = 164L
    private const val PERCENT = 1205L
    private const val KM_PER_MILE = 1.609344
    val WHEEL_KEYS = listOf("tire_front_left", "tire_front_right", "tire_rear_left", "tire_rear_right")

    fun pressure(wheel: Int) = TIRES + 2 + 5L * wheel
    private fun layoutIid(wheel: Int) = pressure(wheel) + 1
    private fun pressureState(wheel: Int) = pressure(wheel) + 2
    fun sensorState(wheel: Int) = pressure(wheel) + 3

    val ids: Set<Long> = (listOf(FUEL_LEVEL, FUEL_LOW_MARKER, FUEL_RANGE_KM, FUEL_RANGE_MILES, FUEL_RANGE_HIDDEN,
        RANGE_KM, RANGE_MILES, RANGE_HIDDEN, ODOMETER_KM, ODOMETER_MILES, VOLUME, VOLUME_TYPE, VOLUME_LABEL, MUTE) +
        WHEEL_KEYS.indices.flatMap { listOf(pressure(it), layoutIid(it), pressureState(it), sensorState(it)) }).toSet()
    /** What the phone may set: the volume slider and its mute. */
    val writableIds = setOf(VOLUME, MUTE)

    fun accessories(readings: UltraVehicleReadings?, volumeWritable: Boolean,
        volumeRange: IntRange = 0..100): List<LinkedHashMap<String, Any?>> = listOf(
        accessory(1536, 0x09000006, listOf(
            service(1537, 0x1A000005, listOf(
                car(FUEL_LEVEL, 0x41000002, CafCharacteristicFormat.UINT8, value(FUEL_LEVEL, readings), range = 0L to 100L, units = PERCENT),
                car(FUEL_LOW_MARKER, 0x4100001A, CafCharacteristicFormat.UINT8, LOW_FUEL_PERCENT, mutable = false, range = 0L to 100L, units = PERCENT))),
            distance(1540, 0x1A000015, FUEL_RANGE_KM, FUEL_RANGE_MILES, FUEL_RANGE_HIDDEN, readings))),
        accessory(1792, 0x04000001, listOf(
            distance(1793, 0x1A00000B, RANGE_KM, RANGE_MILES, RANGE_HIDDEN, readings),
            distance(1797, 0x1A00000A, ODOMETER_KM, ODOMETER_MILES, null, readings))),
        accessory(TIRES, 0x0A000001, WHEEL_KEYS.indices.map { wheel ->
            service(pressure(wheel) - 1, 0x1B000001, listOf(
                car(pressure(wheel), 0x3000001B, CafCharacteristicFormat.UINT32, value(pressure(wheel), readings),
                    range = 0L to 1000L, units = KILOPASCALS),
                car(layoutIid(wheel), 0x36000065, CafCharacteristicFormat.STRING, WHEEL_KEYS[wheel], mutable = false),
                car(pressureState(wheel), 0x3000001C, CafCharacteristicFormat.UINT8, PRESSURE_UNDEFINED, range = 0L to 5L),
                car(sensorState(wheel), 0x3000001A, CafCharacteristicFormat.UINT8, value(sensorState(wheel), readings), range = 0L to 4L)))
        }),
        accessory(2304, 0x02000001, listOf(
            service(2305, 0x13000002, listOf(
                car(VOLUME, 0x33000006, CafCharacteristicFormat.UINT8, value(VOLUME, readings),
                    range = volumeRange.first.toLong() to volumeRange.last.toLong(),
                    writable = volumeWritable, enabled = true),
                car(VOLUME_TYPE, 0x33000005, CafCharacteristicFormat.UINT8, MAIN_VOLUME_TYPE, mutable = false, range = 0L to LAST_VOLUME_TYPE),
                car(VOLUME_LABEL, 0x30000001, CafCharacteristicFormat.STRING, "VoyahTune", mutable = false),
                car(MUTE, 0x33000009, CafCharacteristicFormat.BOOL, value(MUTE, readings), writable = volumeWritable))))),
    )

    /** The value the phone gets for [iid]; null means no value. */
    fun value(iid: Long, readings: UltraVehicleReadings?): Any? {
        val wheel = WHEEL_KEYS.indices.firstOrNull { iid in pressure(it)..sensorState(it) }
        if (wheel != null) {
            val kpa = readings?.tirePressureKpa?.getOrNull(wheel)
            return when (iid) {
                pressure(wheel) -> kpa
                layoutIid(wheel) -> WHEEL_KEYS[wheel]
                pressureState(wheel) -> PRESSURE_UNDEFINED
                else -> if (kpa != null) SENSOR_ACTIVE else SENSOR_WAITING
            }
        }
        return when (iid) {
            FUEL_LEVEL -> readings?.fuelPercent
            FUEL_LOW_MARKER -> LOW_FUEL_PERCENT
            FUEL_RANGE_KM -> readings?.fuelRangeKm
            FUEL_RANGE_MILES -> readings?.fuelRangeKm?.let(::miles)
            FUEL_RANGE_HIDDEN -> readings?.fuelRangeKm == null
            RANGE_KM -> readings?.rangeKm
            RANGE_MILES -> readings?.rangeKm?.let(::miles)
            RANGE_HIDDEN -> readings?.rangeKm == null
            ODOMETER_KM -> readings?.odometerKm
            ODOMETER_MILES -> readings?.odometerKm?.let(::miles)
            VOLUME -> readings?.volumeLevel
            VOLUME_TYPE -> MAIN_VOLUME_TYPE
            VOLUME_LABEL -> "VoyahTune"
            MUTE -> readings?.muted
            else -> null
        }
    }

    private fun miles(km: Long) = (km / KM_PER_MILE).roundToLong()

    private fun distance(iid: Long, type: Long, km: Long, miles: Long, hidden: Long?, readings: UltraVehicleReadings?) =
        service(iid, type, listOfNotNull(
            car(km, 0x3000004A, CafCharacteristicFormat.UINT64, value(km, readings), units = KILOMETERS),
            car(miles, 0x3000004B, CafCharacteristicFormat.UINT64, value(miles, readings), units = MILES),
            hidden?.let { car(it, 0x36000023, CafCharacteristicFormat.BOOL, value(it, readings)) }))

    private fun accessory(iid: Long, type: Long, services: List<Map<String, Any?>>) = linkedMapOf<String, Any?>(
        "type" to type, "iid" to iid, "version" to "1.0", "services" to services)
    private fun service(iid: Long, type: Long, chars: List<Map<String, Any?>>) = linkedMapOf<String, Any?>(
        "type" to type, "iid" to iid, "characteristics" to chars)

    /** A characteristic the car owns (writable only where the phone may set it), described as LIVI does. */
    private fun car(iid: Long, type: Long, format: CafCharacteristicFormat, value: Any?, mutable: Boolean = true,
        range: Pair<Long, Long>? = null, units: Long? = null, writable: Boolean = false, enabled: Boolean = false) =
        linkedMapOf<String, Any?>("iid" to iid, "type" to type, "format" to format.wireValue, "writable" to writable,
            "oemWritable" to true, "mutable" to mutable, "priority" to 1L).apply {
            val counts = format == CafCharacteristicFormat.UINT32 || format == CafCharacteristicFormat.UINT64
            val bounds = range ?: if (counts) 0L to 4_294_967_295L else null
            if (bounds != null) { put("minimumValue", bounds.first); put("maximumValue", bounds.second); put("stepValue", 1L) }
            if (format == CafCharacteristicFormat.STRING) put("maximumLength", 65535L)
            if (enabled) put("disabled", false)
            if (units != null) put("units", units)
            if (value != null) put("initialValue", value)
        }
}
