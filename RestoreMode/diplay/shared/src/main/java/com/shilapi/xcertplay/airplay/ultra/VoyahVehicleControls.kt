package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat

/** Vehicle settings and button actions routed through Native. */
enum class UltraVehicleControl(val serviceIid: Long, val minimum: Int, val maximum: Int) {
    DRIVER_MASSAGE(8192, 0, 3),
    PASSENGER_MASSAGE(8208, 0, 3),
    DRIVER_MASSAGE_PROGRAM(8224, 1, 2),
    PASSENGER_MASSAGE_PROGRAM(8240, 1, 2),
    LIGHT_MODE(8256, 0, 3),
    CAR_HOME(8272, 0, 255),
    VOYAH_SETTINGS(8288, 0, 255);

    val valueIid get() = serviceIid + 3
    val isAppAction get() = this == CAR_HOME || this == VOYAH_SETTINGS
    val isMassage get() = this == DRIVER_MASSAGE || this == PASSENGER_MASSAGE
    val isButton get() = isAppAction || isMassage
    val isProgram get() = this == DRIVER_MASSAGE_PROGRAM || this == PASSENGER_MASSAGE_PROGRAM
    val isPassenger get() = this == PASSENGER_MASSAGE || this == PASSENGER_MASSAGE_PROGRAM

    fun normalize(raw: Any?): Int? = integerInRange(raw, minimum, maximum)

    fun normalizeRequest(raw: Any?): Int? =
        if (isButton) integerInRange(raw, 0, 255) else normalize(raw)

    private fun integerInRange(raw: Any?, minimum: Int, maximum: Int): Int? =
        (raw as? Number)?.toDouble()?.takeIf {
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

    val displayOrder = listOf(
        UltraVehicleControl.CAR_HOME,
        UltraVehicleControl.VOYAH_SETTINGS,
        UltraVehicleControl.LIGHT_MODE,
        UltraVehicleControl.DRIVER_MASSAGE,
        UltraVehicleControl.DRIVER_MASSAGE_PROGRAM,
        UltraVehicleControl.PASSENGER_MASSAGE,
        UltraVehicleControl.PASSENGER_MASSAGE_PROGRAM,
    )

    private fun order(control: UltraVehicleControl) = displayOrder.indexOf(control)

    private fun identifier(control: UltraVehicleControl) =
        "${SECTION}_${order(control).toString().padStart(2, '0')}_${control.name}"

    fun ids(supported: Set<UltraVehicleControl>): Set<Long> {
        if (supported.isEmpty()) return emptySet()
        return (listOf(8065L, 8066L, 8067L) + supported.flatMap { control ->
            (1L..(if (control.isButton) 7L else 8L)).map { control.serviceIid + it }
        }).toSet()
    }

    fun services(
        supported: Set<UltraVehicleControl>,
        values: Map<UltraVehicleControl, Int>,
        text: UltraVehicleControlsText,
        buttonActions: Map<UltraVehicleControl, Int> = emptyMap(),
    ): List<Map<String, Any?>> {
        if (supported.isEmpty()) return emptyList()
        val section = service(SECTION_IID, 0x16000020, listOf(
            characteristic(8065, 0x30000019, SECTION),
            characteristic(8066, 0x30000003, 0L, minimum = 0L, maximum = 100L),
            characteristic(8067, 0x36000004, text.section),
        ))
        return listOf(section) + displayOrder.filter { it in supported }.map { control ->
            val base = control.serviceIid
            val button = control.isButton
            val characteristics = listOf(
                characteristic(base + 1, 0x30000019, identifier(control)),
                characteristic(
                    base + 2,
                    0x30000001,
                    label(control, values[control], text),
                    mutable = control.isMassage,
                ),
                characteristic(
                    base + 3,
                    if (button) BUTTON_ACTION else INT32_VALUE,
                    if (button) (buttonActions[control] ?: 0).toLong()
                    else values[control]?.let(control::normalize)?.toLong(),
                    writable = true,
                    format = if (button) CafCharacteristicFormat.UINT8 else CafCharacteristicFormat.INT32,
                    minimum = if (button) 0L else control.minimum.toLong(),
                    maximum = if (button) 255L else control.maximum.toLong(),
                ),
                characteristic(base + 4, 0x36000006, 0L),
                characteristic(base + 5, 0x3000005E, symbol(control)),
                characteristic(base + 6, 0x30000003, order(control).toLong(), minimum = 0L, maximum = 100L),
                characteristic(base + 7, 0x36000028, SECTION),
            ) + if (button) emptyList() else listOf(
                characteristic(base + 8, 0x30000017, display(control, values[control], text), mutable = true),
            )
            service(base, if (button) BUTTON_SETTING else INTEGER_SETTING, characteristics)
        }
    }

    fun value(
        iid: Long,
        supported: Set<UltraVehicleControl>,
        values: Map<UltraVehicleControl, Int>,
        text: UltraVehicleControlsText,
        buttonActions: Map<UltraVehicleControl, Int> = emptyMap(),
    ): Any? {
        when (iid) {
            8065L -> return SECTION
            8066L -> return 0L
            8067L -> return text.section
        }
        val control = supported.firstOrNull {
            iid in (it.serviceIid + 1)..(it.serviceIid + if (it.isButton) 7L else 8L)
        } ?: return null
        return when (iid - control.serviceIid) {
            1L -> identifier(control)
            2L -> label(control, values[control], text)
            3L -> if (control.isButton) (buttonActions[control] ?: 0).toLong()
                else values[control]?.let(control::normalize)?.toLong()
            4L -> 0L
            5L -> symbol(control)
            6L -> order(control).toLong()
            7L -> SECTION
            else -> display(control, values[control], text)
        }
    }

    private fun title(control: UltraVehicleControl, text: UltraVehicleControlsText) = when (control) {
        UltraVehicleControl.DRIVER_MASSAGE -> text.driverMassage
        UltraVehicleControl.PASSENGER_MASSAGE -> text.passengerMassage
        UltraVehicleControl.DRIVER_MASSAGE_PROGRAM -> text.driverProgram
        UltraVehicleControl.PASSENGER_MASSAGE_PROGRAM -> text.passengerProgram
        UltraVehicleControl.LIGHT_MODE -> text.lights
        UltraVehicleControl.CAR_HOME -> text.carHome
        UltraVehicleControl.VOYAH_SETTINGS -> text.settings
    }

    private fun label(control: UltraVehicleControl, raw: Int?, text: UltraVehicleControlsText): String =
        if (control.isMassage) "${title(control, text)}: ${display(control, raw, text)}"
        else title(control, text)

    private fun symbol(control: UltraVehicleControl) = when (control) {
        UltraVehicleControl.CAR_HOME -> "car"
        UltraVehicleControl.VOYAH_SETTINGS -> "gearshape"
        UltraVehicleControl.LIGHT_MODE -> "lightbulb"
        else -> "figure.seated.side"
    }

    private fun display(control: UltraVehicleControl, raw: Int?, text: UltraVehicleControlsText): String {
        val level = control.normalize(raw) ?: return text.unavailable
        return when {
            control.isProgram -> if (level == 1) text.wave else text.rollers
            control == UltraVehicleControl.LIGHT_MODE -> text.lightModes.getOrNull(level) ?: text.unavailable
            level == 0 -> text.off
            else -> "$level / 3"
        }
    }

    private fun service(iid: Long, type: Long, characteristics: List<Map<String, Any?>>) =
        linkedMapOf<String, Any?>("iid" to iid, "type" to type, "characteristics" to characteristics)

    private fun characteristic(
        iid: Long,
        type: Long,
        value: Any?,
        writable: Boolean = false,
        mutable: Boolean = false,
        format: CafCharacteristicFormat = if (value is String) CafCharacteristicFormat.STRING else CafCharacteristicFormat.UINT8,
        minimum: Long? = null,
        maximum: Long? = null,
    ) = linkedMapOf<String, Any?>(
        "iid" to iid,
        "type" to type,
        "format" to format.wireValue,
        "writable" to writable,
        "oemWritable" to true,
        "mutable" to (mutable || writable),
        "priority" to 1L,
    ).apply {
        if (value != null) put("initialValue", value)
        if (mutable || writable) put("supportsInvalid", true)
        if (format == CafCharacteristicFormat.STRING) put("maximumLength", 65535L)
        if (minimum != null) put("minimumValue", minimum)
        if (maximum != null) {
            put("maximumValue", maximum)
            put("stepValue", 1L)
        }
    }
}
