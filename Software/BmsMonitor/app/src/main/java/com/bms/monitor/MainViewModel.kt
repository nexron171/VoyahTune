package com.bms.monitor

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

    private val _tempMax = MutableLiveData<Parsers.Temp>()
    val tempMax: LiveData<Parsers.Temp> = _tempMax

    private val _tempMin = MutableLiveData<Parsers.Temp>()
    val tempMin: LiveData<Parsers.Temp> = _tempMin

    /** Кадр 0x574: изоляция и индексы модуля/датчика Max/Min; null — кадра нет. */
    private val _tboxRequest = MutableLiveData<Parsers.TboxRequest?>()
    val tboxRequest: LiveData<Parsers.TboxRequest?> = _tboxRequest

    private val _tempInlet = MutableLiveData<Parsers.Temp>()
    val tempInlet: LiveData<Parsers.Temp> = _tempInlet

    private val _tempOutlet = MutableLiveData<Parsers.Temp>()
    val tempOutlet: LiveData<Parsers.Temp> = _tempOutlet

    private val _metric = MutableLiveData<Parsers.BmsMetric>()
    val metric: LiveData<Parsers.BmsMetric> = _metric

    /** Датчики температуры модулей 1..9 (кадр 0x584). */
    /** Оценка предела тягового тока drivingCurrentLimit, А, по packTempMax; null — нет температуры. */
    private val _drivingCurrentLimit = MutableLiveData<Double?>()
    val drivingCurrentLimit: LiveData<Double?> = _drivingCurrentLimit

    private val _sensors = MutableLiveData<List<Parsers.Temp>>(emptyList())
    val sensors: LiveData<List<Parsers.Temp>> = _sensors

    private val _tempStatus = MutableLiveData("Запуск опроса…")
    val tempStatus: LiveData<String> = _tempStatus

    private val history = TempHistory()
    private val _tempHistory = MutableLiveData<List<TempPoint>>(emptyList())
    val tempHistory: LiveData<List<TempPoint>> = _tempHistory

    private val _cells = MutableLiveData<List<Double>>(emptyList())
    val cells: LiveData<List<Double>> = _cells

    private val _cellsStatus = MutableLiveData("Нажмите «Обновить»")
    val cellsStatus: LiveData<String> = _cellsStatus

    private val _cellsLoading = MutableLiveData(false)
    val cellsLoading: LiveData<Boolean> = _cellsLoading

    private var pollJob: Job? = null

    /** Имя файла CSV-лога, null — запись выключена. */
    private val _logFile = MutableLiveData<String?>(null)
    val logFile: LiveData<String?> = _logFile

    /** Включение/выключение записи CSV-лога (CsvLogger); новый файл на каждое включение. */
    fun toggleLogging() {
        if (CsvLogger.file != null) {
            CsvLogger.stop()
            _logFile.value = null
            return
        }
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { CsvLogger.start() }
                _logFile.value = f.absolutePath
            } catch (e: Exception) {
                _logFile.value = null
                _tempStatus.value = "Ошибка записи лога: ${e.message}"
            }
        }
    }

    /** CsvLogger сам выключается при ошибке записи — отражаем это в UI. */
    private fun syncLogState() {
        if (CsvLogger.file == null && _logFile.value != null) _logFile.postValue(null)
    }

    /** true, пока открыт фрейм напряжений — цикл опроса температур не ходит по SSH. */
    @Volatile private var pollPaused = false

    /**
     * Фоновый опрос: по SSH читается сырой CAN-кадр 0x304 (vnsMonitorCan),
     * из него — Max, Min, Avg (только в лог) и температуры охлаждающей жидкости на входе/выходе;
     * тем же запросом кадр 0x514 — SOH, SOC, SOE, 0x584 — 9 датчиков модулей,
     * 0x574 — сопротивление изоляции и индексы модуля/датчика Max/Min,
     * drivingCurrentLimit — оценка по packTempMax (кадр 0x134 на T-Box не приходит).
     *  - Запись лога выключена: только CAN, пауза 10 с.
     *  - Запись включена: 0x304, 0x514 и ячейки 0x594 одной командой (≈6 с),
     *    одной строкой лога, цикл раз в 10 с (от начала до начала).
     * Каждый цикл добавляет точку в историю (окно 30 мин) для графика.
     * Пока pollPaused (открыт фрейм напряжений) — итерации пропускаются.
     */
    fun startTemperaturePolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (pollPaused) {
                    delay(1000)
                    continue
                }
                val logging = CsvLogger.file != null
                val now = System.currentTimeMillis()
                var failed = false
                try {
                    _tempStatus.postValue(
                        if (logging) "Чтение CAN 0x304 и ячеек для лога (≈6 с)…" else "Чтение CAN 0x304…"
                    )
                    val out = SshManager.exec(
                        if (logging) SshManager.CMD_BMS_CAN_CELLS else SshManager.CMD_BMS_CAN
                    )
                    val t = Parsers.parseTempFrame(out)
                    val metric = Parsers.parseMetric(out)
                    _metric.postValue(metric)
                    val sensors = Parsers.parseModuleTemps(out, SENSORS)
                    _sensors.postValue(sensors)
                    val tbox = Parsers.parseTboxRequest(out)
                    _tboxRequest.postValue(tbox)
                    val dcl = Parsers.estimateDrivingCurrentLimit(t.max)
                    _drivingCurrentLimit.postValue(dcl)
                    val minTemp = t.min
                    val maxTemp = t.max
                    _tempMin.postValue(minTemp)
                    _tempMax.postValue(maxTemp)
                    _tempInlet.postValue(t.inletCoolant)
                    _tempOutlet.postValue(t.outletCoolant)

                    // При записи лога — ещё и ячейки из того же вывода, в ту же строку
                    var cells: List<Double>? = null
                    if (logging) {
                        cells = readCells(out)
                        _cells.postValue(cells!!)
                        _cellsStatus.postValue(cellsStatusText(cells) + " (запись лога)")
                    }
                    // Пишет только цикл, начатый при включённой записи: иначе первая
                    // строка нового файла была бы без ячеек
                    if (logging) CsvLogger.logBms(now, t, metric, tbox, dcl, sensors, cells)
                    syncLogState()

                    fun c(t: Parsers.Temp) = (t as? Parsers.Temp.Value)?.celsius
                    history.add(
                        TempPoint(
                            now, c(minTemp), c(maxTemp),
                            c(t.inletCoolant), c(t.outletCoolant),
                            (metric.soc as? Parsers.Percent.Value)?.percent,
                            dcl
                        )
                    )
                    _tempHistory.postValue(history.snapshot())

                } catch (e: Exception) {
                    failed = true
                    _tempStatus.postValue("Ошибка SSH: ${e.message}")
                }
                val pause = if (logging)
                    (LOG_PERIOD_MS - (System.currentTimeMillis() - now)).coerceAtLeast(0)
                else
                    PAUSE_MS
                if (!failed) _tempStatus.postValue("Обновлено. Пауза ${(pause + 500) / 1000} с")
                delay(pause)
            }
        }
    }

    /** Пауза опроса температур: открыт фрейм напряжений (ячейки). */
    fun pauseTemperaturePolling() {
        pollPaused = true
        _tempStatus.postValue("Пауза: открыт фрейм напряжений")
    }

    /** Возобновление опроса: вернулись на фрейм температур. */
    fun resumeTemperaturePolling() {
        pollPaused = false
        startTemperaturePolling()
    }

    /**
     * Чтение 100 ячеек (≈6 с): кадры 0x594 через vnsMonitorCan. [first] — уже
     * полученный вывод (CMD_BMS_CAN_CELLS), считается первой попыткой. Если
     * пришли не все кадры (долгая подписка), запрос CMD_CELLS повторяется,
     * кадры объединяются — всего до CELLS_ATTEMPTS попыток.
     */
    private suspend fun readCells(first: ByteArray? = null): List<Double> = withContext(Dispatchers.IO) {
        val frames = HashMap<Int, Long>()
        var attempts = 0
        if (first != null) {
            frames.putAll(Parsers.parseCellFrames(first))
            attempts++
        }
        var cells = Parsers.cellsFromFrames(frames, maxCells = CELLS)
        while (cells.size < CELLS && attempts < CELLS_ATTEMPTS) {
            frames.putAll(Parsers.parseCellFrames(SshManager.exec(SshManager.CMD_CELLS)))
            attempts++
            cells = Parsers.cellsFromFrames(frames, maxCells = CELLS)
        }
        cells
    }

    private fun cellsStatusText(list: List<Double>) = when {
        list.isEmpty() -> "Нет данных (шина спит?)"
        list.size < CELLS -> "Ячеек: ${list.size} из $CELLS — не все кадры 0x594 получены или значения вне 0..5 В отфильтрованы"
        else -> "Ячеек: ${list.size}"
    }

    /** Чтение напряжений ячеек по кнопке (вне записи лога ячейки сами не опрашиваются). */
    fun refreshCells() {
        if (_cellsLoading.value == true) return
        viewModelScope.launch {
            _cellsLoading.value = true
            _cellsStatus.value = "Чтение ячеек (≈6 с)…"
            try {
                val list = readCells()
                if (list.isNotEmpty()) CsvLogger.logCells(System.currentTimeMillis(), list)
                syncLogState()
                _cells.value = list
                _cellsStatus.value = cellsStatusText(list)
            } catch (e: Exception) {
                _cellsStatus.value = "Ошибка SSH: ${e.message}"
            } finally {
                _cellsLoading.value = false
            }
        }
    }

    companion object {
        /** Пауза между опросами без записи лога. */
        private const val PAUSE_MS = 10_000L
        /** Период цикла при записи лога (CAN + ячейки). */
        private const val LOG_PERIOD_MS = 10_000L
        private const val CELLS = 100
        /** Живые датчики температуры модулей в 0x584. */
        const val SENSORS = 9
        /** Попыток чтения 0x594, пока не соберутся все ячейки. */
        private const val CELLS_ATTEMPTS = 3
    }

    override fun onCleared() {
        CsvLogger.stop()
        pollJob?.cancel()
        SshManager.close()
        super.onCleared()
    }
}
