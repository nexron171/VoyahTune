package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import kotlin.math.abs

/** Elapsed timestamps and actual OEM cache values; no fabricated sensor readings. */
data class UltraClimateSample(
    val driverTargetCelsius: Double, val sampledAtMillis: Long,
    val comfortLevels: Map<UltraClimateControl, Int> = emptyMap(),
    val comfortSampledAtMillis: Long = sampledAtMillis,
    val hvacValues: Map<UltraHvacControl, Any?> = emptyMap(),
    val hvacSampledAtMillis: Long = sampledAtMillis,
    /** Read-only cache fields (cabin sensor, SYNC, MAX states); never written by this pilot. */
    val readings: Map<UltraClimateReading, Any?> = emptyMap(),
    /** Tires, fuel, odometer, range and volume; null until the car and Android reported them. */
    val vehicle: UltraVehicleReadings? = null,
    val vehicleSampledAtMillis: Long = sampledAtMillis,
    val radio: UltraRadioSnapshot? = null,
    val radioSampledAtMillis: Long = sampledAtMillis,
    /** The OEM retains a selected seat level even while its separate switch is off. */
    val comfortSelectedLevels: Map<UltraClimateControl, Int> = emptyMap(),
    val vehicleControlValues: Map<UltraVehicleControl, Int> = emptyMap(),
    val vehicleControlsSampledAtMillis: Long = sampledAtMillis,
    val lightingSampledAtMillis: Long = vehicleControlsSampledAtMillis,
)
/** Real TX30 cache readings the phone needs to draw the Climate app; there is no command path for them. */
enum class UltraClimateReading(val iid: Long, val type: Long, val format: CafCharacteristicFormat) {
    CABIN_TEMPERATURE(16, 0x31000019, CafCharacteristicFormat.FLOAT),
    MAX_AC(43, 0x31000022, CafCharacteristicFormat.BOOL),
    MAX_DEFROST(44, 0x31000029, CafCharacteristicFormat.BOOL),
    ZONES_SYNCED(96, 0x30000002, CafCharacteristicFormat.BOOL),
    RECIRCULATION_AUTO(72, 0x3000005F, CafCharacteristicFormat.BOOL);
    fun normalize(value: Any?): Any? = when (format) {
        CafCharacteristicFormat.BOOL -> when ((value as? Number)?.toDouble() ?: (value as? Boolean)?.let { if (it) 1.0 else 0.0 }) {
            0.0 -> false; 1.0 -> true; else -> null }
        else -> (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it in MIN_CABIN..MAX_CABIN }
            ?.let { kotlin.math.round(it * 2) / 2 }
    }
    companion object {
        const val MIN_CABIN = -40.0; const val MAX_CABIN = 85.0
        /** The car has one cabin sensor; both temperature zones show that measured value. */
        const val PASSENGER_CABIN_IID = 26L
    }
}
enum class UltraClimateControl(val serviceIid: Long, val maxLevel: Int) {
    DRIVER_HEAT(210, 3), PASSENGER_HEAT(220, 3), DRIVER_VENT(230, 3), PASSENGER_VENT(240, 3), WHEEL_HEAT(30, 1);
    val levelIid get() = serviceIid + 1
    val onIid get() = serviceIid + 2
    val layoutIid get() = serviceIid + 3
    val autoIid get() = serviceIid + 4
    val isWheel get() = this == WHEEL_HEAT
    val isVent get() = this == DRIVER_VENT || this == PASSENGER_VENT
    val isPassenger get() = this == PASSENGER_HEAT || this == PASSENGER_VENT
}
/** The H97C service has no implementation of AC_MAX_SWITCH: it is deliberately absent. */
enum class UltraHvacControl(val iid: Long, val format: CafCharacteristicFormat) {
    DRIVER_TARGET(11, CafCharacteristicFormat.FLOAT), PASSENGER_TARGET(21, CafCharacteristicFormat.FLOAT),
    POWER(41, CafCharacteristicFormat.BOOL), AC(42, CafCharacteristicFormat.BOOL),
    FAN(51, CafCharacteristicFormat.UINT8), AUTO(61, CafCharacteristicFormat.BOOL), RECIRCULATION(71, CafCharacteristicFormat.BOOL),
    FRONT_DEFROST(81, CafCharacteristicFormat.UINT8), REAR_DEFROST(91, CafCharacteristicFormat.UINT8);
    fun normalize(value: Any?): Any? = when (format) {
        CafCharacteristicFormat.BOOL -> value as? Boolean
        CafCharacteristicFormat.FLOAT -> (value as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it in MIN_TARGET..MAX_TARGET && abs(it * 2 - kotlin.math.round(it * 2)) < 0.00001
        }
        else -> (value as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it == kotlin.math.floor(it) && it in 0.0..(if (this == FAN) VoyahHvacFan.MAX_LEVEL.toDouble() else 1.0)
        }?.toLong()
    }
    companion object { const val MIN_TARGET = 15.5; const val MAX_TARGET = 30.5 }
}
data class UltraClimateWriteResult(val confirmed: Boolean, val message: String)

/**
 * Route-sharing values the phone writes, one store per session shared by both data streams: the
 * phone may follow a value on one stream and write it on the other. Each write is counted, because
 * the phone waits for a confirming update even when it writes the value the car already holds
 * (iPhone log 22.10.2026 23:52, userEnabled 262). Never a vehicle command.
 */
internal class UltraPhoneValues {
    private val values = linkedMapOf<Long, Any?>()
    private val writes = linkedMapOf<Long, Long>()
    @Synchronized fun write(iid: Long, value: Any?) { values[iid] = value; writes[iid] = (writes[iid] ?: 0L) + 1 }
    @Synchronized fun value(iid: Long): Any? = values[iid]
    @Synchronized fun writeCount(iid: Long): Long = writes[iid] ?: 0L
}

/** One channel's subscriptions; success is exclusively a confirmed OEM readback. */
internal class VoyahClimateAccessory(
    private val sample: () -> UltraClimateSample?, private val now: () -> Long,
    private val supportedControls: Set<UltraClimateControl> = emptySet(),
    private val writer: ((UltraClimateControl, Int) -> UltraClimateWriteResult)? = null,
    private val supportedHvac: Set<UltraHvacControl> = emptySet(),
    private val hvacWriter: ((UltraHvacControl, Any) -> UltraClimateWriteResult)? = null,
    /** Sets the Android volume and/or mute and answers with the read-back; null = volume is not writable. */
    private val volumeWriter: ((Int?, Boolean?) -> UltraClimateWriteResult)? = null,
    private val phoneValues: UltraPhoneValues = UltraPhoneValues(),
    private val radioSupported: Boolean = false,
    private val radioWriter: ((UltraRadioRequest) -> UltraClimateWriteResult)? = null,
    private val vehicleDataText: UltraVehicleDataText = UltraVehicleDataText(),
    private val supportedVehicleControls: Set<UltraVehicleControl> = emptySet(),
    private val vehicleControlWriter: ((UltraVehicleControl, Int) -> UltraClimateWriteResult)? = null,
    private val vehicleControlsText: UltraVehicleControlsText = UltraVehicleControlsText(),
    private val volumeRange: IntRange = 0..100,
) {
    init { require(volumeRange.first in 0..255 && volumeRange.last in volumeRange.first..255) }
    /** Values the phone wrote successfully on this stream: confirmed by one update even if unchanged. */
    private val confirmWritten = linkedSetOf<Long>()
    private val subscriptions = linkedSetOf<Long>()
    /** Per stream: how many writes of each phone value this stream has already confirmed. */
    private val confirmedWrites = linkedMapOf<Long, Long>()
    /** Echo an accepted action once; the following update returns the button to ready. */
    private val appActions = mutableMapOf<UltraVehicleControl, Int>()
    private val vehicleControlIds = VoyahVehicleControls.ids(supportedVehicleControls)
    private var lastSent: Map<Long, Any?> = emptyMap()
    /** Mirrors the shared ownership used by working CarPlay climate descriptions; unmapped writes still get -50. */
    private val sharedWritable get() = hvacWriter != null && supportedHvac.isNotEmpty()
    private val knownIds: Set<Long> get() = FIXED_IDS + CONSTANTS.keys + PHONE_IDS + VoyahVehicleDescription.ids + VoyahVehicleDataSettings.ids +
        vehicleControlIds +
        (if (radioSupported) VoyahRadioDescription.ids else emptySet()) + UltraHvacControl.entries.map { it.iid } +
        PUBLISHED_READINGS.map { it.iid } + supportedControls.flatMap {
        listOf(it.levelIid) + (if (it.isVent) emptyList() else listOf(it.onIid)) + (if (it.isWheel) emptyList() else listOf(it.layoutIid)) +
            if (it.isVent) listOf(it.autoIid) else emptyList() }
    fun config(): Map<String, Any?> {
        val accessories = arrayListOf<Map<String, Any?>>(navigation(), automakerSettings())
        accessories.addAll(VoyahVehicleDescription.accessories(vehicle(), volumeWriter != null, volumeRange))
        if (radioSupported) accessories.addAll(VoyahRadioDescription.accessories(radio()))
        return linkedMapOf("accessories" to accessories)
    }
    /** Route sharing as the phone describes it to a car: phone-owned values plus fixed car labels. */
    private fun navigation() = accessory(256, 0x0E000002, listOf(
        service(257, 0x1E000104, listOf(phone(258, 0x45000103, CafCharacteristicFormat.BOOL), phone(259, 0x45000112, CafCharacteristicFormat.UINT32),
            phone(260, 0x45000109, CafCharacteristicFormat.ARRAY), phone(261, 0x4500010D, CafCharacteristicFormat.UINT8, 4), phone(262, 0x45000104, CafCharacteristicFormat.BOOL),
            fixed(263, 0x4500010E, CafCharacteristicFormat.STRING), characteristic(264, 0x45000105, CafCharacteristicFormat.BOOL, CONSTANTS[264L]))),
        service(265, 0x1E000102, listOf(phone(266, 0x45000108, CafCharacteristicFormat.DICTIONARY), phone(267, 0x45000106, CafCharacteristicFormat.UINT8, 2),
            phone(268, 0x45000107, CafCharacteristicFormat.DICTIONARY), phone(269, 0x45000110, CafCharacteristicFormat.UINT8, 6), phone(270, 0x45000101, CafCharacteristicFormat.UINT8, 255),
            phone(271, 0x45000102, CafCharacteristicFormat.STRING))),
        service(272, 0x1E000103, listOf(characteristic(273, 0x4500010C, CafCharacteristicFormat.UINT8, CONSTANTS[273L]).apply {
                put("minimumValue", 0L); put("maximumValue", 5L); put("stepValue", 1L) },
            fixed(274, 0x4500010A, CafCharacteristicFormat.STRING), fixed(275, 0x45000111, CafCharacteristicFormat.STRING)))))
    /** The vehicle app's information rows: version and live vehicle data. */
    private fun automakerSettings() = accessory(1280, 0x05000001,
        VoyahVehicleControls.services(supportedVehicleControls, vehicleControls(), vehicleControlsText) +
        VoyahVehicleDataSettings.services(vehicle(), vehicleDataText) + listOf(
        service(1281, 0x16000015, listOf(fixed(1282, 0x30000019, CafCharacteristicFormat.STRING), fixed(1283, 0x30000001, CafCharacteristicFormat.STRING),
            fixed(1284, 0x30000017, CafCharacteristicFormat.STRING), fixed(1285, 0x36000006, CafCharacteristicFormat.UINT8, 15), fixed(1286, 0x3000005E, CafCharacteristicFormat.STRING),
            fixed(1287, 0x30000003, CafCharacteristicFormat.UINT8, 100), fixed(1288, 0x36000028, CafCharacteristicFormat.STRING))),
        service(1297, 0x16000020, listOf(fixed(1298, 0x30000019, CafCharacteristicFormat.STRING), fixed(1299, 0x30000003, CafCharacteristicFormat.UINT8, 100),
            fixed(1300, 0x36000004, CafCharacteristicFormat.STRING)))))
    private fun fixed(iid: Long, type: Long, format: CafCharacteristicFormat, max: Long? = null) =
        characteristic(iid, type, format, CONSTANTS.getValue(iid), mutable = false).apply {
            if (max != null) { put("minimumValue", 0L); put("maximumValue", max); put("stepValue", 1L) }
        }
    private fun phone(iid: Long, type: Long, format: CafCharacteristicFormat, max: Long? = null) = linkedMapOf<String, Any?>(
        "iid" to iid, "type" to type, "format" to format.wireValue, "writable" to true,
        "oemWritable" to false, "mutable" to true, "priority" to 1L).apply {
        when {
            max != null -> { put("minimumValue", 0L); put("maximumValue", max); put("stepValue", 1L) }
            format == CafCharacteristicFormat.UINT32 -> { put("minimumValue", 0L); put("maximumValue", 4294967295L); put("stepValue", 1L) }
        }
        if (format in LENGTH_FORMATS) put("maximumLength", 65535L)
        if (format != CafCharacteristicFormat.UINT8) put("supportsInvalid", true)
    }
    private fun accessory(iid: Long, type: Long, services: List<Map<String, Any?>>) = linkedMapOf<String, Any?>(
        "type" to type, "iid" to iid, "version" to "1.0", "services" to services)
    private fun service(iid: Long, type: Long, chars: List<Map<String, Any?>>) = linkedMapOf<String, Any?>(
        "type" to type, "iid" to iid, "characteristics" to chars)
    private fun layout(iid: Long, key: String) = characteristic(iid, 0x36000065, CafCharacteristicFormat.STRING, key, mutable = false)
    private fun characteristic(iid: Long, type: Long, format: CafCharacteristicFormat, value: Any?,
        writable: Boolean = false, mutable: Boolean = true) = linkedMapOf<String, Any?>(
        "iid" to iid, "type" to type, "format" to format.wireValue, "writable" to writable,
        "oemWritable" to true, "mutable" to mutable, "priority" to 1L).apply {
        if (value != null) put("initialValue", value)
        if (mutable) put("supportsInvalid", true)
        if (format in LENGTH_FORMATS) put("maximumLength", 65535L)
    }
    private fun hvac(control: UltraHvacControl, type: Long) = characteristic(control.iid, type, control.format,
        current(control), control in supportedHvac && hvacWriter != null).apply {
        when (control.format) {
            CafCharacteristicFormat.FLOAT -> {
                put("minimumValue", UltraHvacControl.MIN_TARGET); put("maximumValue", UltraHvacControl.MAX_TARGET)
                put("stepValue", 0.5); put("units", 1082L)
            }
            CafCharacteristicFormat.UINT8 -> {
                put("minimumValue", 0L); put("maximumValue", if (control == UltraHvacControl.FAN) VoyahHvacFan.MAX_LEVEL else 1L); put("stepValue", 1L)
            }
            else -> Unit
        }
    }
    private fun temperature(iid: Long, control: UltraHvacControl, key: String) = service(iid, 0x11000002,
        listOf(characteristic(iid + 5, 0x30000002, CafCharacteristicFormat.BOOL, current(UltraHvacControl.POWER), sharedWritable),
            hvac(control, 0x31000017),
            characteristic(iid + 6, UltraClimateReading.CABIN_TEMPERATURE.type, CafCharacteristicFormat.FLOAT,
                cabinTemperature(control)).apply {
                put("minimumValue", UltraClimateReading.MIN_CABIN); put("maximumValue", UltraClimateReading.MAX_CABIN)
                put("stepValue", 0.5); put("units", 1082L)
            },
            layout(iid + 4, key)))
    /**
     * H97C reports airTempInCar=-9999: the car has no cabin reading for us. Until it does, the
     * phone's "current temperature" carries that zone's set point (user decision 21.10.2026),
     * because the phone subscribes to it for the climate controls. A real sensor value wins.
     */
    private fun cabinTemperature(zone: UltraHvacControl): Any? =
        reading(UltraClimateReading.CABIN_TEMPERATURE) ?: current(zone)
    private fun reading(reading: UltraClimateReading, writable: Boolean) =
        characteristic(reading.iid, reading.type, reading.format, reading(reading), writable)
    private fun reading(reading: UltraClimateReading): Any? {
        val s = sample() ?: return null
        return if (fresh(s.hvacSampledAtMillis)) reading.normalize(s.readings[reading]) else null
    }
    private fun defrost(iid: Long, control: UltraHvacControl, key: String, type: Long) = service(iid, 0x11000005,
        listOf(hvac(control, 0x30000004), layout(iid + 2, key),
            characteristic(iid + 3, 0x31000014, CafCharacteristicFormat.UINT8, type, mutable = false).apply {
                put("minimumValue", 0L); put("maximumValue", 7L); put("stepValue", 1L)
            }))
    private fun comfort(control: UltraClimateControl): Map<String, Any?> {
        val level = comfortLevel(control)
        val chars = arrayListOf<Map<String, Any?>>(characteristic(control.levelIid,
            if (control.isVent) 0x31000012 else 0x31000021,
            if (control.isVent) CafCharacteristicFormat.UINT8 else CafCharacteristicFormat.INT32,
            level?.toLong(), writer != null).apply {
                put("minimumValue", 0L)
                put("maximumValue", control.maxLevel.toLong()); put("stepValue", 1L) })
        // SeatFan.On is optional (CAFSeatFan.hasOn). Publish the actual 0..3 level:
        // a retained selected speed plus a separate switch did not update iOS's seat button.
        if (!control.isVent) chars.add(characteristic(control.onIid, 0x30000002,
            CafCharacteristicFormat.BOOL, level?.let { it > 0 }, writer != null))
        if (!control.isWheel) chars.add(layout(control.layoutIid, if (control.isPassenger) "seat_front_right" else "seat_front_left"))
        if (control.isVent) chars.add(characteristic(control.autoIid, 0x3000005F, CafCharacteristicFormat.BOOL, false, mutable = false))
        return service(control.serviceIid, if (control.isWheel) 0x11000003 else if (control.isVent) 0x22000002 else 0x22000001, chars)
    }
    fun handle(message: CafMessageReader): CafRcsFrame? {
        if (message.pluginId != PLUGIN_ID) return message.transactionId?.let { CarAccessoryMessages.generalError(message.pluginId, PARAMETER_ERROR, it) }
        return when (message.command) {
            CafCommand.CONFIG_REQUEST -> CarAccessoryMessages.configResponse(PLUGIN_ID, message.requireTransactionId(), config())
            CafCommand.READ_REQUEST -> identifiers(message).let { ids ->
                val current = values(ids)
                CarAccessoryMessages.readResponse(PLUGIN_ID, message.requireTransactionId(), present(current), errors(ids, current))
            }
            CafCommand.REGISTER_REQUEST -> {
                val ids = if (message.values() == "*") knownIds.toList() else identifiers(message)
                val transaction = message.requireTransactionId()
                subscriptions.addAll(ids.filter { it in knownIds })
                ids.filter { it in PHONE_IDS }.forEach { confirmedWrites[it] = phoneValues.writeCount(it) }
                val current = values(ids); lastSent = lastSent + current
                CarAccessoryMessages.registerResponse(PLUGIN_ID, transaction, present(current), errors(ids, current))
            }
            CafCommand.UNREGISTER_REQUEST -> {
                val transaction = message.requireTransactionId()
                subscriptions.removeAll(identifiers(message).toSet())
                CarAccessoryMessages.unregisterResponse(PLUGIN_ID, transaction)
            }
            CafCommand.WRITE_REQUEST -> write(message)
            CafCommand.CONTROL_REQUEST -> if (radioSupported) control(message) else
                CarAccessoryMessages.generalError(PLUGIN_ID, PARAMETER_ERROR, message.requireTransactionId())
            else -> null
        }
    }
    private fun control(message: CafMessageReader): CafRcsFrame {
        val transaction = message.requireTransactionId()
        val requested = message.valuesMap()
        if (requested.size > 16) return CarAccessoryMessages.generalError(PLUGIN_ID, PARAMETER_ERROR, transaction)
        val answered = linkedMapOf<Long, Any?>()
        val refused = linkedMapOf<Long, Long>()
        for ((iid, parameters) in requested) {
            if (!radioSupported || iid !in VoyahRadioDescription.controls) { refused[iid] = -6735L; continue }
            val command = VoyahRadioDescription.request(iid, parameters)
            if (command == null || command.stationId != null && radio()?.items()?.none { it.id == command.stationId } != false) {
                refused[iid] = PARAMETER_ERROR; continue
            }
            val result = radioWriter?.let { runCatching { it(command) }.getOrNull() }
            if (result?.confirmed == true) answered[iid] = null else refused[iid] = UNAVAILABLE
        }
        return CarAccessoryMessages.controlResponse(PLUGIN_ID, transaction, answered, refused)
    }
    private fun write(message: CafMessageReader): CafRcsFrame {
        val transaction = message.requireTransactionId() // Before any vehicle side effect.
        val requested = message.valuesMap()
        if (requested.size > 32) return CarAccessoryMessages.generalError(PLUGIN_ID, PARAMETER_ERROR, transaction)
        val results = requested.keys.associateWith { PARAMETER_ERROR }.toMutableMap()
        // Phone-owned route data is accepted as a value store only; it never becomes a vehicle command.
        for (iid in requested.keys.filter { it in PHONE_IDS }) { phoneValues.write(iid, requested[iid]); results[iid] = 0L }
        val sendVehicleControl = vehicleControlWriter
        for (control in supportedVehicleControls) {
            if (control.valueIid !in requested || sendVehicleControl == null) continue
            val wanted = control.normalize(requested[control.valueIid]) ?: continue
            val result = runCatching { sendVehicleControl.invoke(control, wanted) }.getOrNull()
            results[control.valueIid] = if (result?.confirmed == true) 0L else UNAVAILABLE
            if (control.isAppAction && result?.confirmed == true) appActions[control] = wanted
        }
        val volumeIds = requested.keys.filter { it in VoyahVehicleDescription.writableIds }
        val sendVolume = volumeWriter
        if (volumeIds.isNotEmpty() && sendVolume != null) {
            val level = if (VoyahVehicleDescription.VOLUME in requested)
                integerLevel(requested[VoyahVehicleDescription.VOLUME], volumeRange.last)?.takeIf { it in volumeRange } else null
            val mute = requested[VoyahVehicleDescription.MUTE] as? Boolean
            val malformed = VoyahVehicleDescription.VOLUME in requested && level == null ||
                VoyahVehicleDescription.MUTE in requested && mute == null
            if (!malformed) {
                val result = runCatching { sendVolume.invoke(level, mute) }.getOrNull()
                volumeIds.forEach { results[it] = if (result?.confirmed == true) 0L else UNAVAILABLE }
            }
        }
        val sendHvac = hvacWriter
        val sendComfort = writer
        val conflictingHvac = requested[UltraHvacControl.POWER.iid] == false && (
            requested[UltraHvacControl.AC.iid] == true || requested[UltraHvacControl.AUTO.iid] == true ||
            ((requested[UltraHvacControl.FAN.iid] as? Number)?.toDouble() ?: 0.0) > 0 ||
            ((requested[UltraHvacControl.FRONT_DEFROST.iid] as? Number)?.toDouble() ?: 0.0) > 0)
        for (control in UltraHvacControl.entries) {
            if (conflictingHvac || control.iid !in requested || control !in supportedHvac || sendHvac == null) continue
            val wanted = control.normalize(requested[control.iid]) ?: continue
            val result = runCatching { sendHvac.invoke(control, wanted) }.getOrNull()
            results[control.iid] = if (result?.confirmed == true) 0L else UNAVAILABLE
        }
        for (control in supportedControls) {
            if (sendComfort == null) continue
            val ids = listOf(control.levelIid, control.onIid).filter { it in requested && it in knownIds }
            if (ids.isEmpty()) continue
            val level = if (control.levelIid in requested) integerLevel(requested[control.levelIid], control.maxLevel) else null
            val on = requested[control.onIid] as? Boolean
            if (control.levelIid in requested && level == null || control.onIid in requested && on == null) continue
            if (level != null && on != null && on != (level > 0)) continue
            val wanted = level ?: if (on == false) 0 else comfortDisplayLevel(control)?.takeIf { it > 0 } ?: control.maxLevel
            val result = runCatching { sendComfort.invoke(control, wanted) }.getOrNull()
            ids.forEach { results[it] = if (result?.confirmed == true) 0L else UNAVAILABLE }
            if (result?.confirmed == true) {
                confirmWritten.add(control.levelIid)
                if (!control.isVent) confirmWritten.add(control.onIid)
            }
        }
        confirmWritten.addAll(results.filter { (iid, result) -> result == 0L && iid !in PHONE_IDS }.keys)
        return CarAccessoryMessages.writeResponse(PLUGIN_ID, transaction, results)
    }
    private fun integerLevel(value: Any?, max: Int) = (value as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it in 0.0..max.toDouble() && it == kotlin.math.floor(it) }?.toInt()
    fun changed(): CafRcsFrame? {
        // The phone waits 0.5 s for an update confirming each value it wrote and otherwise reverts
        // the write ("echo timeout", iPhone log 22.10.2026). Every write of a followed route value
        // is therefore sent back once, also when it did not change the value.
        val current = values(subscriptions.toList())
        val changed = current.filter { (iid, value) -> !lastSent.containsKey(iid) || lastSent[iid] != value }.toMutableMap()
        for (iid in subscriptions.filter { it in PHONE_IDS }) {
            val writes = phoneValues.writeCount(iid)
            if (writes != (confirmedWrites[iid] ?: 0L)) { confirmedWrites[iid] = writes; changed[iid] = current[iid] }
        }
        // A setting the car applied (climate, comfort, volume) is confirmed with its read-back too,
        // also when the phone wrote the value the car already had.
        for (iid in confirmWritten) if (iid in subscriptions && current[iid] != null) changed[iid] = current[iid]
        confirmWritten.clear()
        // First confirm the phone's written action. Then publish ready (0) on the
        // next update: keeping the action disables the button, skipping its echo
        // leaves the phone's write unconfirmed and prevents another invocation.
        appActions.clear()
        if (changed.isEmpty()) return null
        lastSent = lastSent + changed
        return CarAccessoryMessages.updateNotify(PLUGIN_ID, changed)
    }
    private fun fresh(time: Long) = now() - time in 0..MAX_AGE_MILLIS
    private fun vehicle(): UltraVehicleReadings? = sample()?.takeIf { fresh(it.vehicleSampledAtMillis) }?.vehicle
    private fun vehicleControls(): Map<UltraVehicleControl, Int> {
        val s = sample()
        val current = s?.vehicleControlValues?.filter { (c, _) -> fresh(
            if (c == UltraVehicleControl.LIGHT_MODE) s.lightingSampledAtMillis else s.vehicleControlsSampledAtMillis) }.orEmpty()
        return current + UltraVehicleControl.entries.filter { it.isAppAction }.associateWith { appActions[it] ?: 0 }
    }
    private fun radio(): UltraRadioSnapshot? = sample()?.takeIf { fresh(it.radioSampledAtMillis) }?.radio
    private fun current(control: UltraHvacControl): Any? {
        val s = sample() ?: return null
        if (control == UltraHvacControl.DRIVER_TARGET && control !in s.hvacValues)
            return s.driverTargetCelsius.takeIf { fresh(s.sampledAtMillis) }?.let(control::normalize)
        return if (fresh(s.hvacSampledAtMillis)) control.normalize(s.hvacValues[control]) else null
    }
    private fun fanOn(): Boolean? {
        if (current(UltraHvacControl.POWER) == false) return false
        return (current(UltraHvacControl.FAN) as? Number)?.let { it.toInt() > 0 }
    }
    private fun comfortLevel(control: UltraClimateControl): Int? {
        val s = sample() ?: return null
        return s.comfortLevels[control]?.takeIf { fresh(s.comfortSampledAtMillis) && it in 0..control.maxLevel }
    }
    private fun comfortDisplayLevel(control: UltraClimateControl): Int? {
        val active = comfortLevel(control) ?: return null
        if (!control.isVent || active > 0) return active
        return sample()?.comfortSelectedLevels?.get(control)?.takeIf { it in 1..control.maxLevel }
            ?: 1
    }
    private fun identifiers(message: CafMessageReader) = message.valuesList().map { CafNumbers.toLong(it, "climate IID") }
    private fun values(ids: List<Long>): Map<Long, Any?> = ids.filter { it in knownIds }.associateWith { iid ->
        val hvac = UltraHvacControl.entries.find { it.iid == iid }
        val comfort = supportedControls.find { iid == it.levelIid || iid == it.onIid ||
            !it.isWheel && iid == it.layoutIid || it.isVent && iid == it.autoIid }
        when {
            iid in PHONE_IDS -> phoneValues.value(iid)
            iid in CONSTANTS -> CONSTANTS[iid]
            iid in VoyahVehicleDataSettings.ids -> VoyahVehicleDataSettings.value(iid, vehicle(), vehicleDataText)
            iid in vehicleControlIds ->
                VoyahVehicleControls.value(iid, supportedVehicleControls, vehicleControls(), vehicleControlsText)
            iid in VoyahVehicleDescription.ids -> VoyahVehicleDescription.value(iid, vehicle())
            radioSupported && iid in VoyahRadioDescription.ids -> VoyahRadioDescription.value(iid, radio())
            hvac != null -> current(hvac)
            comfort != null -> when (iid) {
                comfort.levelIid -> comfortLevel(comfort)?.toLong()
                comfort.onIid -> comfortLevel(comfort)?.let { it > 0 }
                comfort.autoIid -> false
                else -> if (comfort.isPassenger) "seat_front_right" else "seat_front_left"
            }
            iid == UltraClimateReading.CABIN_TEMPERATURE.iid -> cabinTemperature(UltraHvacControl.DRIVER_TARGET)
            iid == UltraClimateReading.PASSENGER_CABIN_IID -> cabinTemperature(UltraHvacControl.PASSENGER_TARGET)
            UltraClimateReading.entries.any { it.iid == iid } -> reading(UltraClimateReading.entries.first { it.iid == iid })
            iid == 97L -> "seat_front"
            iid == 14L -> "seat_front_left"
            iid == 24L -> "seat_front_right"
            iid == 52L -> fanOn()
            iid == 53L -> current(UltraHvacControl.AUTO)
            iid == 54L -> "seat_front"
            iid == 82L -> "windshield_front"
            iid == 92L -> "windshield_rear"
            iid == 83L -> 1L
            iid == 93L -> 2L
            iid == 102L -> "5Seater"
            else -> current(UltraHvacControl.POWER)
        }
    }
    private fun present(values: Map<Long, Any?>): Map<Long, Any?> = values.filterValues { it != null }
    /** Unknown IIDs are -50; a known IID without a value is -6727, as the phone expects, never a null value. */
    private fun errors(ids: List<Long>, values: Map<Long, Any?> = emptyMap()): Map<Long, Long> =
        ids.filter { it !in knownIds }.associateWith { PARAMETER_ERROR } +
            ids.filter { it in knownIds && values[it] == null }.associateWith { UNAVAILABLE }
    companion object {
        const val PLUGIN_ID = 1L
        const val PLUGIN_NAME = "org.voyahtune.vehicle"
        const val TARGET_IID = 11L
        const val MAX_AGE_MILLIS = 6_000L
        const val PARAMETER_ERROR = -50L
        const val UNAVAILABLE = -6727L
        private val LENGTH_FORMATS = setOf(CafCharacteristicFormat.STRING, CafCharacteristicFormat.DATA,
            CafCharacteristicFormat.DICTIONARY, CafCharacteristicFormat.ARRAY)
        private val PHONE_IDS = setOf(258L, 259L, 260L, 261L, 262L, 266L, 267L, 268L, 269L, 270L, 271L)
        private val CONSTANTS: Map<Long, Any> = mapOf(
            263L to "VoyahTune", 264L to true, 273L to 0L, 274L to "VoyahTune", 275L to "Voyah",
            1282L to "VoyahPlayVersion", 1283L to "VoyahTune", 1284L to VoyahUltraProtocol.WIRE_REV, 1285L to 0L,
            1286L to "info.circle", 1287L to 0L, 1288L to "VoyahVersion", 1298L to "VoyahVersion", 1299L to 2L, 1300L to "VoyahTune")
        private val FIXED_IDS = setOf(14L, 15L, 24L, 25L, UltraClimateReading.PASSENGER_CABIN_IID, 52L, 53L, 54L, 82L, 83L, 92L, 93L, 97L, 102L)
        private val PUBLISHED_READINGS = setOf(UltraClimateReading.CABIN_TEMPERATURE, UltraClimateReading.ZONES_SYNCED)
    }
}
