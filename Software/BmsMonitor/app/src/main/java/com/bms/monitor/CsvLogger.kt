package com.bms.monitor

import android.provider.Settings
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись лога в CSV: один файл на каждое включение записи,
 * <папка>/BmsMonitor_yyyy-MM-dd_HH-mm-ss.csv.
 *
 *  Папка: Settings.Global bms_log_dir, если не задано — /sdcard/Download
 *  (на Android 11 приложение создаёт там свои файлы без разрешений).
 *
 *  Строки двух типов с общей шапкой:
 *   - bms   — каждый цикл опроса при записи (раз в 10 с): 5 температур (°C),
 *             SOH/SOC/SOE (%), 0x574: сопротивление изоляции (кОм) и индексы
 *             модуля/датчика Max/Min, оценка предела тягового тока drivingCurrentLimit по temp_max (А), датчики модулей sensor_1..sensor_9 (°C, 0x584)
 *             и cell_1..cell_100 (В);
 *   - cells — чтение ячеек кнопкой «Обновить»: только cell_1..cell_100.
 *  Разделитель «,», десятичная точка; недействительное/нет данных — пусто.
 */
object CsvLogger {

    private const val DEFAULT_DIR = "/sdcard/Download"
    private const val DIR_SETTING_KEY = "bms_log_dir"
    private const val CELLS = 100
    private const val SENSORS = 9
    private const val TBOX_COLUMNS = 5

    private val HEADER = (listOf(
        "time", "type",
        "temp_max", "temp_min", "temp_avg", "temp_inlet", "temp_outlet",
        "soh", "soc", "soe",
        "iso_resistance", "max_module", "max_sensor", "min_module", "min_sensor",
        "driving_current_limit"
    ) + (1..SENSORS).map { "sensor_$it" } + (1..CELLS).map { "cell_$it" }).joinToString(",")

    private var writer: BufferedWriter? = null

    /** Текущий файл лога, null — запись выключена. */
    @Volatile var file: File? = null
        private set

    private fun logDir(): File {
        val ctx = BmsApp.probeCtx()
        val custom = try {
            ctx?.let { Settings.Global.getString(it.contentResolver, DIR_SETTING_KEY) }
        } catch (e: Exception) {
            null
        }
        return File(custom?.takeIf { it.isNotBlank() } ?: DEFAULT_DIR)
    }

    /** Новый файл с шапкой. Бросает исключение, если файл не создать. */
    @Synchronized
    fun start(): File {
        stop()
        val dir = logDir()
        dir.mkdirs()
        val name = "BmsMonitor_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".csv"
        val f = File(dir, name)
        val w = f.bufferedWriter()
        w.write(HEADER); w.newLine(); w.flush()
        writer = w
        file = f
        return f
    }

    @Synchronized
    fun stop() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        file = null
    }

    /** Строка цикла опроса: CAN (0x304 + 0x514 + 0x574 + 0x584) и ячейки (null — не читались). */
    fun logBms(
        timeMs: Long, t: Parsers.PackTemps, m: Parsers.BmsMetric, tbox: Parsers.TboxRequest?,
        dcl: Double?, sensors: List<Parsers.Temp>, cells: List<Double>?
    ) {
        val temps = listOf(t.max, t.min, t.avg, t.inletCoolant, t.outletCoolant).map(::tempValue)
        val pct = listOf(m.soh, m.soc, m.soe)
            .map { (it as? Parsers.Percent.Value)?.percent?.let { v -> fmt(v, 1) } ?: "" }
        val sens = (0 until SENSORS).map { i -> sensors.getOrNull(i)?.let(::tempValue) ?: "" }
        val req = tbox?.run {
            listOf(isolationResistance, maxModuleIdx, maxSensorIdx, minModuleIdx, minSensorIdx).map { it.toString() }
        } ?: List(TBOX_COLUMNS) { "" }
        val lmt = dcl?.let { fmt(it, 1) } ?: ""
        row(timeMs, "bms", temps + pct + req + lmt + sens + cellValues(cells.orEmpty()))
    }

    private fun tempValue(t: Parsers.Temp) = (t as? Parsers.Temp.Value)?.celsius?.let { fmt(it, 1) } ?: ""

    /** Строка чтения ячеек; меньше 100 — недостающие пустые. */
    fun logCells(timeMs: Long, cells: List<Double>) {
        row(timeMs, "cells", List(8 + TBOX_COLUMNS + 1 + SENSORS) { "" } + cellValues(cells))
    }

    private fun cellValues(cells: List<Double>) =
        (0 until CELLS).map { i -> cells.getOrNull(i)?.let { fmt(it, 3) } ?: "" }

    @Synchronized
    private fun row(timeMs: Long, type: String, values: List<String>) {
        val w = writer ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))
        try {
            w.write((listOf(time, type) + values).joinToString(","))
            w.newLine()
            w.flush() // строки редкие (раз в 10 с) — сразу на диск
        } catch (e: Exception) {
            stop()
        }
    }

    private fun fmt(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)
}
