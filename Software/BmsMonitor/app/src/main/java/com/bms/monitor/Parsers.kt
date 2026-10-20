package com.bms.monitor

/**
 * Перенос обработки perl из файла commands на сторону приложения.
 */
object Parsers {

    /** Результат чтения температуры. */
    sealed class Temp {
        data class Value(val celsius: Double) : Temp()   // реальное значение
        object NaN : Temp()                               // -nan (BMS не отдаёт поле)
        object Fail : Temp()                              // get_signal_double fail
        object NoData : Temp()                            // нет совпадения
    }

    // value[...] oriValue[   — ровно как в perl: ловит только строку get_signal_double
    private val TEMP_RE = Regex("""value\[([^\]]*)\]\s*oriValue\[""")

    /**
     * Порт perl для температуры:
     *   /get_signal_double fail/ -> FAIL
     *   /value[X] oriValue[/     -> X (как %.1f)
     */
    fun parseTemp(stdout: ByteArray): Temp {
        val text = String(stdout, Charsets.ISO_8859_1)
        if (text.contains("get_signal_double fail")) return Temp.Fail
        val m = TEMP_RE.find(text) ?: return Temp.NoData
        val raw = m.groupValues[1].trim()
        val d = raw.toDoubleOrNull()
        return when {
            d == null -> if (raw.contains("nan", true)) Temp.NaN else Temp.NoData
            d.isNaN() -> Temp.NaN
            else -> Temp.Value(d)
        }
    }

    /** Температуры из одного кадра 0x304. */
    data class PackTemps(
        val max: Temp,
        val min: Temp,
        val avg: Temp,
        val inletCoolant: Temp,
        val outletCoolant: Temp
    )

    /**
     * Все кадры CAN [id] из вывода vnsMonitorCan как 64-битные
     * little-endian числа (байт 0 — младший), по порядку прихода.
     * Формат: "CAN[304] channel[1] data:\n68 C8 94 D9 C2 45 0B 00".
     */
    private fun canFrames(text: String, id: String): Sequence<Long> {
        val re = Regex("""CAN\[$id\][^\n]*\n\s*((?:[0-9A-Fa-f]{2}\s+){7}[0-9A-Fa-f]{2})""")
        return re.findAll(text).map { m ->
            var frame = 0L
            m.groupValues[1].trim().split(Regex("""\s+""")).forEachIndexed { i, b ->
                frame = frame or (b.toLong(16) shl (8 * i))
            }
            frame
        }
    }

    /** Последний кадр CAN [id], null — кадра нет. */
    private fun lastCanFrame(text: String, id: String): Long? = canFrames(text, id).lastOrNull()

    /** Сигнал Intel-кадра: младший бит — startbit-1 (нумерация can-in.xml). */
    private fun bits(frame: Long, startBit: Int, len: Int): Int =
        ((frame ushr (startBit - 1)) and ((1L shl len) - 1)).toInt()

    /**
     * Температуры из последнего кадра 0x304 вывода vnsMonitorCan. Раскладка по
     * etc/vns/H97/can-in.xml: Intel (little-endian), младший бит сигнала —
     * startbit-1, 9 бит, °C = raw * 0.5 - 40. Max: startbit 1, Min: 10, Avg: 19,
     * InletCoolant: 28, OutletCoolant: 37.
     * raw 0x1FF (все единицы) — недействительное значение.
     */
    fun parseTempFrame(stdout: ByteArray): PackTemps {
        val frame = lastCanFrame(String(stdout, Charsets.ISO_8859_1), "304")
            ?: return PackTemps(Temp.NoData, Temp.NoData, Temp.NoData, Temp.NoData, Temp.NoData)
        fun sig(startBit: Int): Temp {
            val raw = bits(frame, startBit, 9)
            return if (raw == 0x1FF) Temp.NaN else Temp.Value(raw * 0.5 - 40)
        }
        return PackTemps(
            max = sig(1),
            min = sig(10),
            avg = sig(19),
            inletCoolant = sig(28),
            outletCoolant = sig(37)
        )
    }

    /**
     * Датчики температуры модулей из кадров 0x584 BMS_moduleTemperature
     * (H97.xmls): мультиплексный кадр, номер — байт 0 (datano), 6 датчиков
     * на кадр. bms_moduleXTempSensorN — datano (N-1)/6, startbit
     * 9 + 9*((N-1)%6), 9 бит, °C = raw * 0.5 - 40. BMS шлёт только кадры
     * 0 и 1, живые — датчики 1..9, у 10..12 raw 0x1FE.
     * Нет кадра — NoData, raw 0x1FE/0x1FF — NaN.
     */
    fun parseModuleTemps(stdout: ByteArray, count: Int = 9): List<Temp> {
        val frames = HashMap<Int, Long>()
        for (frame in canFrames(String(stdout, Charsets.ISO_8859_1), "584")) {
            frames[(frame and 0xFF).toInt()] = frame
        }
        return (0 until count).map { n ->
            val frame = frames[n / 6] ?: return@map Temp.NoData
            val raw = bits(frame, 9 + 9 * (n % 6), 9)
            if (raw >= 0x1FE) Temp.NaN else Temp.Value(raw * 0.5 - 40)
        }
    }

    /** Сигналы 0x574 BMS_tboxRequest: изоляция и где Max/Min температуры. */
    data class TboxRequest(
        val isolationResistance: Int,
        val minModuleIdx: Int,
        val minSensorIdx: Int,
        val maxModuleIdx: Int,
        val maxSensorIdx: Int
    )

    /**
     * Последний кадр 0x574 BMS_tboxRequest (H97.xmls), null — кадра нет:
     * bms_isolationResistance — startbit 1, 16 бит, factor 1 (единица в
     * описании не указана, по величине ~13000 — кОм);
     * bms_packTempMinModuleIdx — 28, 5 бит; bms_packTempMinSensorIdx — 33, 3 бита;
     * bms_packTempMaxModuleIdx — 36, 5 бит; bms_packTempMaxSensorIdx — 41, 3 бита.
     */
    fun parseTboxRequest(stdout: ByteArray): TboxRequest? {
        val frame = lastCanFrame(String(stdout, Charsets.ISO_8859_1), "574") ?: return null
        return TboxRequest(
            isolationResistance = bits(frame, 1, 16),
            minModuleIdx = bits(frame, 28, 5),
            minSensorIdx = bits(frame, 33, 3),
            maxModuleIdx = bits(frame, 36, 5),
            maxSensorIdx = bits(frame, 41, 3)
        )
    }

    /**
     * Оценка предела тягового тока батареи drivingCurrentLimit, А, по
     * bms_packTempMax. Сам сигнал идёт в кадре 0x134 (startbit 1, 14 бит, ×0.2),
     * но его нет в белом списке MCU — на T-Box кадр не приходит. Таблица снята с
     * логов (25..47.5 °C, целые А) и CAN-дампа (48..52 °C, точно), шаг 0.5 °C;
     * между точками — линейно. Ниже 25 °C — 1048 А; выше 52 °C данных нет —
     * продолжение последнего наклона, не меньше 0.
     */
    private val DCL_FROM_C = 25.0
    private val DCL_TABLE = doubleArrayOf(
        1048.0, 1043.0, 1038.0, 1034.0, 1029.0, 1024.0, 1019.0, 1014.0,  // 25.0..28.5
        1009.0, 1004.0, 999.0, 994.0, 989.0, 984.0, 979.0, 974.0,        // 29.0..32.5
        969.0, 963.0, 958.0, 953.0, 948.0, 943.0, 937.0, 932.0,          // 33.0..36.5
        926.0, 921.0, 915.0, 910.0, 905.0, 899.0, 894.0, 888.0,          // 37.0..40.5
        882.0, 876.0, 871.0, 865.0, 859.0, 853.0, 848.0, 842.0,          // 41.0..44.5
        836.4, 830.2, 824.0, 817.8, 811.6, 805.4, 799.2, 793.0,          // 45.0..48.5
        786.8, 780.6, 774.4, 711.4, 648.2, 585.2, 523.2                  // 49.0..52.0
    )

    fun estimateDrivingCurrentLimit(packTempMax: Temp): Double? {
        val c = (packTempMax as? Temp.Value)?.celsius ?: return null
        val x = (c - DCL_FROM_C) / 0.5
        if (x <= 0) return DCL_TABLE.first()
        val last = DCL_TABLE.size - 1
        val i = minOf(x.toInt(), last - 1)
        val v = DCL_TABLE[i] + (DCL_TABLE[i + 1] - DCL_TABLE[i]) * (x - i)
        return v.coerceAtLeast(0.0)
    }

    /** Процентный сигнал BMS. */
    sealed class Percent {
        data class Value(val percent: Double) : Percent()
        object NaN : Percent()      // raw 0x3FF — недействительно
        object NoData : Percent()   // кадр 0x514 не получен
    }

    /** SOH / SOC / SOE из одного кадра 0x514. */
    data class BmsMetric(val soh: Percent, val soc: Percent, val soe: Percent)

    /**
     * Последний кадр 0x514 BMS_metric (can-in.xml): 10 бит, % = raw * 0.1;
     * bms_SOC — startbit 1, bms_SOH — 11, bms_SOE — 21.
     * SOH совпадает с vnsTestApp get_signal_double bms_SOH.
     */
    fun parseMetric(stdout: ByteArray): BmsMetric {
        val frame = lastCanFrame(String(stdout, Charsets.ISO_8859_1), "514")
            ?: return BmsMetric(Percent.NoData, Percent.NoData, Percent.NoData)
        fun sig(startBit: Int): Percent {
            val raw = bits(frame, startBit, 10)
            return if (raw == 0x3FF) Percent.NaN else Percent.Value(raw * 0.1)
        }
        return BmsMetric(soh = sig(11), soc = sig(1), soe = sig(21))
    }

    /**
     * Кадры 0x594 BMS_moduleVoltage из вывода vnsMonitorCan: мультиплексор
     * (байт 0, datano) → последний кадр с этим номером.
     */
    fun parseCellFrames(stdout: ByteArray): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        for (frame in canFrames(String(stdout, Charsets.ISO_8859_1), "594")) {
            out[(frame and 0xFF).toInt()] = frame
        }
        return out
    }

    /**
     * Напряжения ячеек из кадров 0x594 (H97.xmls / can-in.xml):
     * bms_moduleXcellBlockN — datano (N-1)/4, startbit 9 + 13*((N-1)%4),
     * 13 бит, В = raw * 0.001. Значения вне диапазона 0..5 В (недействительные,
     * заполнители) отбрасываются. Список обрывается на первом недостающем кадре.
     */
    fun cellsFromFrames(frames: Map<Int, Long>, maxCells: Int = 100): List<Double> {
        val out = ArrayList<Double>(maxCells)
        for (n in 0 until maxCells) {
            val frame = frames[n / 4] ?: break
            val v = bits(frame, 9 + 13 * (n % 4), 13) / 1000.0
            if (v >= 0.0 && v <= 5.0) out.add(v)
        }
        return out
    }
}
