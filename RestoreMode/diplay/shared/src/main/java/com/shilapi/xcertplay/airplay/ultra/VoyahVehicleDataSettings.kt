package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import java.text.NumberFormat
import java.util.Locale

/** Labels come from the receiver's localized resources; the CAF tree owns no Android context. */
data class UltraVehicleDataText(
    val section: String = "Vehicle data",
    val frontLeft: String = "Front left tire",
    val frontRight: String = "Front right tire",
    val rearLeft: String = "Rear left tire",
    val rearRight: String = "Rear right tire",
    val range: String = "Total range",
    val odometer: String = "Odometer",
    val fuel: String = "Fuel level",
    val fuelRange: String = "Petrol range",
    val battery: String = "Battery",
    val electricRange: String = "Electric range",
    val driveMode: String = "Drive mode",
    val driveModes: List<String> = listOf("Eco", "Comfort", "Sport", "Off-road", "Individual", "Snow"),
    val unavailable: String = "No data",
    val bar: String = "bar",
    val kilometers: String = "km",
    val localeTag: String = "en",
)

/** Read-only information rows in CarPlay's AutomakerSettings, using the existing version-row schema. */
internal object VoyahVehicleDataSettings {
    private const val SECTION = "VoyahVehicleData"
    private val rows = (0..10).map { 1329L + it * 16 }
    val displayOrder = listOf(7, 8, 6, 10, 4, 9, 0, 1, 2, 3, 5)
    private fun identifier(row: Int) = "${SECTION}_${displayOrder.indexOf(row).toString().padStart(2, '0')}_$row"
    fun valueId(row: Int) = rows[row] + 3
    val ids = (listOf(1314L, 1315L, 1316L) + rows.flatMap { base -> (1L..7L).map { base + it } }).toSet()

    fun services(readings: UltraVehicleReadings?, text: UltraVehicleDataText): List<Map<String, Any?>> =
        listOf(service(1313, 0x16000020, listOf(
            char(1314, 0x30000019, SECTION), char(1315, 0x30000003, 1L, max = 100), char(1316, 0x36000004, text.section)))) +
            displayOrder.map { row -> val base = rows[row]; service(base, 0x16000015, listOf(
                char(base + 1, 0x30000019, identifier(row)), char(base + 2, 0x30000001, title(row, text)),
                char(base + 3, 0x30000017, display(row, readings, text), mutable = true),
                char(base + 4, 0x36000006, 0L, max = 15),
                char(base + 5, 0x3000005E, symbol(row)),
                char(base + 6, 0x30000003, displayOrder.indexOf(row).toLong(), max = 100), char(base + 7, 0x36000028, SECTION))) }

    fun value(iid: Long, readings: UltraVehicleReadings?, text: UltraVehicleDataText): Any? {
        if (iid == 1314L) return SECTION
        if (iid == 1315L) return 1L
        if (iid == 1316L) return text.section
        val row = rows.indexOfFirst { iid in it + 1..it + 7 }
        if (row < 0) return null
        return when (iid - rows[row]) {
            1L -> identifier(row)
            2L -> title(row, text)
            3L -> display(row, readings, text)
            4L -> 0L
            5L -> symbol(row)
            6L -> displayOrder.indexOf(row).toLong()
            else -> SECTION
        }
    }

    private fun title(row: Int, text: UltraVehicleDataText) = listOf(text.frontLeft, text.frontRight,
        text.rearLeft, text.rearRight, text.range, text.odometer, text.fuel, text.battery, text.electricRange, text.driveMode, text.fuelRange)[row]

    private fun symbol(row: Int) = when (row) {
        4, 8, 10 -> "road.lanes"; 5 -> "gauge"; 6 -> "fuelpump"; 7 -> "battery.100percent"; 9 -> "car"; else -> "tirepressure"
    }

    private fun display(row: Int, readings: UltraVehicleReadings?, text: UltraVehicleDataText): String {
        if (row == 9) return readings?.driveMode?.takeIf { it in 1..6 }
            ?.let { text.driveModes.getOrNull(it - 1) } ?: text.unavailable
        val raw = when (row) {
            in 0..3 -> readings?.tirePressureKpa?.getOrNull(row)?.takeIf { it in 0..1000 }
            4 -> readings?.rangeKm?.takeIf { it >= 0 }
            5 -> readings?.odometerKm?.takeIf { it >= 0 }
            6 -> readings?.fuelPercent?.takeIf { it in 0..100 }
            7 -> readings?.batteryPercent?.takeIf { it in 0..100 }
            8 -> readings?.electricRangeKm?.takeIf { it in 0..1022 }
            else -> readings?.fuelRangeKm?.takeIf { it in 0..1022 }
        } ?: return text.unavailable
        val format = NumberFormat.getNumberInstance(Locale.forLanguageTag(text.localeTag)).apply {
            minimumFractionDigits = if (row < 4) 1 else 0
            maximumFractionDigits = minimumFractionDigits
        }
        return when (row) {
            in 0..3 -> "${format.format(raw / 100.0)} ${text.bar}"
            4, 5, 8, 10 -> "${format.format(raw)} ${text.kilometers}"
            else -> "${format.format(raw)} %"
        }
    }

    private fun service(iid: Long, type: Long, chars: List<Map<String, Any?>>) =
        linkedMapOf<String, Any?>("iid" to iid, "type" to type, "characteristics" to chars)

    private fun char(iid: Long, type: Long, value: Any, mutable: Boolean = false, max: Long? = null) =
        linkedMapOf<String, Any?>("iid" to iid, "type" to type,
            "format" to (if (value is String) CafCharacteristicFormat.STRING else CafCharacteristicFormat.UINT8).wireValue,
            "writable" to false, "oemWritable" to true, "mutable" to mutable, "priority" to 1L, "initialValue" to value).apply {
            if (value is String) put("maximumLength", 65535L)
            if (mutable) put("supportsInvalid", true)
            if (max != null) { put("minimumValue", 0L); put("maximumValue", max); put("stepValue", 1L) }
        }
}
