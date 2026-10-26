package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat

/** Absolute commands only. Massage uses the same TX57/TX58 path as the native VoyahPlay bar. */
enum class UltraVehicleControl(val serviceIid: Long, val minimum: Int, val maximum: Int) {
    DRIVER_MASSAGE(8192, 0, 3), PASSENGER_MASSAGE(8208, 0, 3),
    DRIVER_MASSAGE_PROGRAM(8224, 1, 2), PASSENGER_MASSAGE_PROGRAM(8240, 1, 2),
    LIGHT_MODE(8256, 0, 3), CAR_HOME(8272, 0, 255), VOYAH_SETTINGS(8288, 0, 255);

    val valueIid get() = serviceIid + 3
    val isAppAction get() = this == CAR_HOME || this == VOYAH_SETTINGS
    val isProgram get() = this == DRIVER_MASSAGE_PROGRAM || this == PASSENGER_MASSAGE_PROGRAM
    val isPassenger get() = this == PASSENGER_MASSAGE || this == PASSENGER_MASSAGE_PROGRAM
    fun normalize(raw: Any?): Int? = (raw as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it == kotlin.math.floor(it) && it >= minimum && it <= maximum
    }?.toInt()
}

data class UltraVehicleControlsText(
    val section: String = "Vehicle controls",
    val driverMassage: String = "Driver massage",
    val passengerMassage: String = "Passenger massage",
    val driverProgram: String = "Driver massage program",
    val passengerProgram: String = "Passenger massage program",
    val lights: String = "Exterior lights",
    val carHome: String = "Vehicle menu",
    val off: String = "Off",
    val wave: String = "Wave",
    val rollers: String = "Rollers",
    val lightModes: List<String> = listOf("Off", "Position lights", "Low beam", "Auto"),
    val unavailable: String = "No data",
    val settings: String = "VoyahTune settings",
)

/** AutomakerSettings controls, separate from CAF SeatFan/SeatHeatingCooling. */
internal object VoyahVehicleControls {
    private const val SECTION = "VoyahVehicleControls"
    private const val SECTION_IID = 8064L
    private const val INTEGER_SETTING = 0x16000007L
    private const val BUTTON_SETTING = 0x16000002L
    private const val INT32_VALUE = 0x30000010L
    private const val BUTTON_ACTION = 0x36000010L
    val displayOrder = listOf(UltraVehicleControl.CAR_HOME, UltraVehicleControl.VOYAH_SETTINGS, UltraVehicleControl.LIGHT_MODE,
        UltraVehicleControl.DRIVER_MASSAGE, UltraVehicleControl.DRIVER_MASSAGE_PROGRAM,
        UltraVehicleControl.PASSENGER_MASSAGE, UltraVehicleControl.PASSENGER_MASSAGE_PROGRAM)
    private fun order(c: UltraVehicleControl) = displayOrder.indexOf(c)
    private fun identifier(c: UltraVehicleControl) = "${SECTION}_${order(c).toString().padStart(2, '0')}_${c.name}"

    fun ids(supported: Set<UltraVehicleControl>): Set<Long> = if (supported.isEmpty()) emptySet() else
        (listOf(8065L, 8066L, 8067L) + supported.flatMap { c ->
            (1L..(if (c.isAppAction) 7L else 8L)).map { c.serviceIid + it }
        }).toSet()

    fun services(supported: Set<UltraVehicleControl>, values: Map<UltraVehicleControl, Int>, text: UltraVehicleControlsText): List<Map<String, Any?>> {
        if (supported.isEmpty()) return emptyList()
        return listOf(service(SECTION_IID, 0x16000020, listOf(
            char(8065, 0x30000019, SECTION), char(8066, 0x30000003, 0L, minimum = 0L, maximum = 100L), char(8067, 0x36000004, text.section)))) +
            displayOrder.filter { it in supported }.map { c ->
                val base = c.serviceIid
                val button = c.isAppAction
                service(base, if (button) BUTTON_SETTING else INTEGER_SETTING, listOf(
                    char(base + 1, 0x30000019, identifier(c)), char(base + 2, 0x30000001, title(c, text)),
                    char(base + 3, if (button) BUTTON_ACTION else INT32_VALUE,
                        if (button) (values[c] ?: 0).toLong() else values[c]?.let(c::normalize)?.toLong(), writable = true,
                        format = if (button) CafCharacteristicFormat.UINT8 else CafCharacteristicFormat.INT32,
                        minimum = c.minimum.toLong(), maximum = c.maximum.toLong()),
                    char(base + 4, 0x36000006, 0L), char(base + 5, 0x3000005E, symbol(c)),
                    char(base + 6, 0x30000003, order(c).toLong(), minimum = 0L, maximum = 100L), char(base + 7, 0x36000028, SECTION)) +
                    if (button) emptyList() else listOf(char(base + 8, 0x30000017, display(c, values[c], text), mutable = true)))
            }
    }

    fun value(iid: Long, supported: Set<UltraVehicleControl>, values: Map<UltraVehicleControl, Int>, text: UltraVehicleControlsText): Any? {
        when (iid) { 8065L -> return SECTION; 8066L -> return 0L; 8067L -> return text.section }
        val c = supported.firstOrNull { iid in (it.serviceIid + 1)..(it.serviceIid + if (it.isAppAction) 7L else 8L) }
            ?: return null
        return when (iid - c.serviceIid) {
            1L -> identifier(c)
            2L -> title(c, text)
            3L -> if (c.isAppAction) (values[c] ?: 0).toLong() else values[c]?.let(c::normalize)?.toLong()
            4L -> 0L
            5L -> symbol(c)
            6L -> order(c).toLong()
            7L -> SECTION
            else -> display(c, values[c], text)
        }
    }
    private fun title(c: UltraVehicleControl, t: UltraVehicleControlsText) = when (c) {
        UltraVehicleControl.DRIVER_MASSAGE -> t.driverMassage
        UltraVehicleControl.PASSENGER_MASSAGE -> t.passengerMassage
        UltraVehicleControl.DRIVER_MASSAGE_PROGRAM -> t.driverProgram
        UltraVehicleControl.PASSENGER_MASSAGE_PROGRAM -> t.passengerProgram
        UltraVehicleControl.LIGHT_MODE -> t.lights
        UltraVehicleControl.CAR_HOME -> t.carHome
        UltraVehicleControl.VOYAH_SETTINGS -> t.settings
    }
    private fun symbol(c: UltraVehicleControl) = when (c) {
        UltraVehicleControl.CAR_HOME -> "car"
        UltraVehicleControl.VOYAH_SETTINGS -> "gearshape"
        UltraVehicleControl.LIGHT_MODE -> "lightbulb"
        else -> "figure.seated.side"
    }
    private fun display(c: UltraVehicleControl, raw: Int?, t: UltraVehicleControlsText): String {
        val v = c.normalize(raw) ?: return t.unavailable
        return when {
            c.isProgram -> if (v == 1) t.wave else t.rollers
            c == UltraVehicleControl.LIGHT_MODE -> t.lightModes.getOrNull(v) ?: t.unavailable
            v == 0 -> t.off
            else -> "$v / 3"
        }
    }
    private fun service(iid: Long, type: Long, chars: List<Map<String, Any?>>) =
        linkedMapOf<String, Any?>("iid" to iid, "type" to type, "characteristics" to chars)
    private fun char(iid: Long, type: Long, value: Any?, writable: Boolean = false, mutable: Boolean = false,
        format: CafCharacteristicFormat = if (value is String) CafCharacteristicFormat.STRING else CafCharacteristicFormat.UINT8,
        minimum: Long? = null, maximum: Long? = null) = linkedMapOf<String, Any?>(
        "iid" to iid, "type" to type, "format" to format.wireValue, "writable" to writable,
        "oemWritable" to true, "mutable" to (mutable || writable), "priority" to 1L).apply {
            if (value != null) put("initialValue", value)
            if (mutable || writable) put("supportsInvalid", true)
            if (format == CafCharacteristicFormat.STRING) put("maximumLength", 65535L)
            if (minimum != null) put("minimumValue", minimum)
            if (maximum != null) { put("maximumValue", maximum); put("stepValue", 1L) }
        }
}
