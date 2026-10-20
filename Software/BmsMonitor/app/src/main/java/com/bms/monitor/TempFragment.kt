package com.bms.monitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bms.monitor.databinding.FragmentTempBinding

/** Фрейм 1: bms_packTempMax/Min (+ индексы модуля и датчика из 0x574), drivingCurrentLimit (оценка по packTempMax),
 *  bms_tempInletCoolant, bms_tempOutletCoolant, bms_SOH/SOC/SOE + график X-Y (X=время, окно 30 мин; Y=температура),
 *  снизу — датчики модулей bms_moduleXTempSensor1..9 (0x584) и bms_isolationResistance (0x574). */
class TempFragment : Fragment() {

    private var _b: FragmentTempBinding? = null
    private val b get() = _b!!
    private val vm: MainViewModel by activityViewModels()
    private val sensorValues = ArrayList<TextView>()
    private var isoValue: TextView? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentTempBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        vm.tempMax.observe(viewLifecycleOwner) { render(it, b.maxValue) }
        vm.tempMin.observe(viewLifecycleOwner) { render(it, b.minValue) }
        vm.tboxRequest.observe(viewLifecycleOwner) { r ->
            isoValue?.text = r?.let { getString(R.string.value_iso, it.isolationResistance) } ?: "—"
            b.maxIdx.text = r?.let { getString(R.string.value_idx, it.maxModuleIdx, it.maxSensorIdx) } ?: "—"
            b.minIdx.text = r?.let { getString(R.string.value_idx, it.minModuleIdx, it.minSensorIdx) } ?: "—"
        }
        vm.tempInlet.observe(viewLifecycleOwner) { render(it, b.inletValue) }
        vm.tempOutlet.observe(viewLifecycleOwner) { render(it, b.outletValue) }
        vm.metric.observe(viewLifecycleOwner) { m ->
            renderPercent(m.soh, b.sohValue, b.sohLabel, 70.0)
            renderPercent(m.soc, b.socValue, b.socLabel, 0.0)
            renderPercent(m.soe, b.soeValue, b.soeLabel, 0.0)
        }
        buildSensorRow()
        vm.sensors.observe(viewLifecycleOwner) { list ->
            sensorValues.forEachIndexed { i, tv -> render(list.getOrNull(i) ?: Parsers.Temp.NoData, tv) }
        }
        vm.drivingCurrentLimit.observe(viewLifecycleOwner) { a ->
            b.dclValue.text = a?.let { getString(R.string.value_dcl, it) } ?: "—"
        }
        vm.tempStatus.observe(viewLifecycleOwner) { b.status.text = it }

        // Запись CSV-лога: кнопка-переключатель, рядом — путь к файлу
        b.btnLog.setOnClickListener { vm.toggleLogging() }
        vm.logFile.observe(viewLifecycleOwner) { path ->
            b.btnLog.setText(if (path != null) R.string.btn_log_stop else R.string.btn_log_start)
            b.logFile.visibility = if (path != null) View.VISIBLE else View.GONE
            b.logFile.text = path?.let { "● $it" }
        }
        vm.tempHistory.observe(viewLifecycleOwner) { b.graph.setPoints(it) }
    }

    /**
     * Нижняя строка: плитки датчиков модулей «T1»..«T9», затем изоляция (шире,
     * подпись серая, значение белое). Подпись и значение в одну строку, значение
     * датчиков — как у карточек.
     */
    private fun buildSensorRow() {
        sensorValues.clear()
        b.sensorRow.removeAllViews()
        for (n in 1..MainViewModel.SENSORS) {
            sensorValues.add(addTile(getString(R.string.label_sensor, n), 1f))
        }
        isoValue = addTile(getString(R.string.label_iso_short), 1.5f).apply {
            setTextColor(0xffe6e6e6.toInt())
        }
    }

    /** Плитка нижней строки; возвращает TextView значения. */
    private fun addTile(label: String, weight: Float, labelColor: Int = 0xffcacaca.toInt()): TextView {
        val dp = resources.displayMetrics.density
        val tile = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.card_bg)
            val p = (6 * dp).toInt()
            setPadding(p, p, p, p)
        }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        tile.addView(TextView(requireContext()).apply {
            text = label
            setTextColor(labelColor)
            textSize = 14f
            isSingleLine = true
        }, LinearLayout.LayoutParams(wrap, wrap))
        val value = TextView(requireContext()).apply {
            text = "—"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            isSingleLine = true
        }
        tile.addView(value, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = (8 * dp).toInt() })
        b.sensorRow.addView(tile, LinearLayout.LayoutParams(0, wrap, weight).apply {
            if (b.sensorRow.childCount > 0) marginStart = (8 * dp).toInt()
        })
        return value
    }

    /** Текст температуры окрашивается градиентом -40..0..+40 (синий→зелёный→красный). */
    private fun render(t: Parsers.Temp, label: TextView) {
        val value: Double? = when (t) {
            is Parsers.Temp.Value -> t.celsius
            else -> null
        }
        val text = when (t) {
            is Parsers.Temp.Value -> String.format("%.1f °C", t.celsius)
            Parsers.Temp.NaN -> "N/A"
            Parsers.Temp.Fail -> "FAIL"
            Parsers.Temp.NoData -> "—"
        }
        label.text = text
        label.setTextColor(ColorUtils.tempColor(value))
    }

    /** «95.3 %», цвет значения и подписи — градиент [redAt] % (красный) … 100 % (зелёный). */
    private fun renderPercent(p: Parsers.Percent, label: TextView, name: TextView, redAt: Double) {
        label.text = when (p) {
            is Parsers.Percent.Value -> String.format("%.1f %%", p.percent)
            Parsers.Percent.NaN -> "N/A"
            Parsers.Percent.NoData -> "—"
        }
        val color = if (p is Parsers.Percent.Value)
            ColorUtils.cellColorT(((p.percent - redAt) / (100.0 - redAt)).toFloat())
        else ColorUtils.tempColor(null)
        label.setTextColor(color)
        name.setTextColor(color)
    }

    override fun onDestroyView() {
        super.onDestroyView(); sensorValues.clear(); isoValue = null; _b = null
    }
}
