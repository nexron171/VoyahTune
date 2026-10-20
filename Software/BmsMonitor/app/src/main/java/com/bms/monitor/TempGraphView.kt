package com.bms.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * График X-Y: по оси X — время (окно 30 минут), по оси Y — температура (°C).
 * Серии: max, min, inlet, outlet — левая ось °C; soc и dcl/100 (drivingCurrentLimit, А / 100) — правая ось
 * (своя шкала, общая сетка). Цвета — @color/series_*, совпадают с подписями
 * карточек. Новое время — справа.
 */
class TempGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<TempPoint> = emptyList()

    private val windowMs = 30L * 60 * 1000 // 30 минут

    private val paintBg = Paint().apply { color = Color.parseColor("#0d0f14") }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#232833"); strokeWidth = 1f
    }
    private val paintAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5a6472"); strokeWidth = 2f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9aa4b2"); textSize = 12f
    }
    private class Series(
        val name: String,
        colorRes: Int,
        context: Context,
        /** true — правая ось (%), иначе левая (°C). */
        val rightAxis: Boolean = false,
        val value: (TempPoint) -> Double?
    ) {
        private val color = context.getColor(colorRes)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@Series.color; strokeWidth = 2f; style = Paint.Style.STROKE
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@Series.color; style = Paint.Style.FILL; textSize = 12f
        }
    }

    private val series = listOf(
        Series("max", R.color.series_max, context) { it.max },
        Series("min", R.color.series_min, context) { it.min },
        Series("inlet", R.color.series_inlet, context) { it.inlet },
        Series("outlet", R.color.series_outlet, context) { it.outlet },
        Series("soc", R.color.series_soc, context, rightAxis = true) { it.soc },
        // drivingCurrentLimit / 100 — правая ось вместе с SOC, не сжимает шкалу °C
        Series("dcl/100", R.color.label_iso, context, rightAxis = true) { p -> p.dcl?.let { it / 100.0 } }
    )

    fun setPoints(p: List<TempPoint>) {
        points = p
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, paintBg)

        val left = 46f
        val right = w - 40f // место под подписи правой оси (%)
        val top = 32f // сверху — строка легенды и единиц осей
        val bottom = h - 30f
        val plotW = right - left
        val plotH = bottom - top
        if (plotW <= 0f || plotH <= 0f) return

        // Окно времени: now - 30min (лево) .. now (право).
        // Точки вне окна не рисуем и в шкалу не берём: после сна ГУ стенные
        // часы прыгают и в истории остаются метки «из будущего»/«из прошлого».
        val now = System.currentTimeMillis()
        val tStart = now - windowMs
        val visible = points.filter { it.timeMs in tStart..now }.sortedBy { it.timeMs }

        // Диапазоны осей по данным: левая — температуры (мин. размах 4 °C),
        // правая — SOC (мин. размах 4 %, в пределах 0..100)
        val (tMin, tMax) = range(visible, series.filter { !it.rightAxis }, minSpan = 4.0, empty = 0.0 to 100.0)
        val (pMin, pMax) = range(visible, series.filter { it.rightAxis }, minSpan = 4.0, empty = 0.0 to 100.0)
            .let { (a, b) -> a.coerceAtLeast(0.0) to b.coerceAtMost(100.0) }

        fun xOf(time: Long): Float = (left + ((time - tStart).toDouble() / windowMs) * plotW).toFloat()
        fun yOf(temp: Double): Float = (bottom - ((temp - tMin) / (tMax - tMin)) * plotH).toFloat()
        fun yOfP(pct: Double): Float = (bottom - ((pct - pMin) / (pMax - pMin)) * plotH).toFloat()

        // Вертикальная сетка + подписи времени (ось X), каждые 5 минут
        val mSteps = 6
        for (i in 0..mSteps) {
            val frac = i.toDouble() / mSteps
            val x = (left + plotW * frac).toFloat()
            canvas.drawLine(x, top, x, bottom, paintGrid)
            val minsAgo = (30 - 30 * frac).toInt()
            val label = if (minsAgo == 0) "now" else "-${minsAgo}m"
            canvas.drawText(label, x - paintText.measureText(label) / 2, bottom + 18, paintText)
        }

        // Горизонтальная сетка + подписи: слева °C, справа % (те же линии)
        val tSteps = 5
        for (i in 0..tSteps) {
            val t = tMin + (tMax - tMin) * i / tSteps
            val y = yOf(t)
            canvas.drawLine(left, y, right, y, paintGrid)
            canvas.drawText("%.1f".format(t), 2f, y + 4, paintText)
            val p = pMin + (pMax - pMin) * i / tSteps
            canvas.drawText("%.0f".format(p), right + 4, y + 4, paintText)
        }

        // Оси
        canvas.drawLine(left, top, left, bottom, paintAxis)
        canvas.drawLine(right, top, right, bottom, paintAxis)
        canvas.drawLine(left, bottom, right, bottom, paintAxis)

        if (visible.isEmpty()) {
            val msg = "нет данных"
            canvas.drawText(msg, left + plotW / 2 - paintText.measureText(msg) / 2, top + plotH / 2, paintText)
            return
        }

        for (sr in series) drawSeries(
            canvas, visible, sr.value, sr.line, sr.dot, { t -> xOf(t) },
            if (sr.rightAxis) { v -> yOfP(v) } else { v -> yOf(v) }
        )

        // Легенда
        var lx = left + 8
        for (sr in series) {
            canvas.drawText(sr.name, lx, top - 14, sr.dot)
            lx += sr.dot.measureText(sr.name) + 14
        }
        canvas.drawText("°C", 2f, top - 14, paintText)
        canvas.drawText("%", right + 4, top - 14, paintText)
    }

    /** Диапазон значений серий по [pts] с отступом 8 %; размах не меньше [minSpan]. */
    private fun range(
        pts: List<TempPoint>,
        ss: List<Series>,
        minSpan: Double,
        empty: Pair<Double, Double>
    ): Pair<Double, Double> {
        val v = pts.flatMap { p -> ss.mapNotNull { it.value(p) } }
        var lo = v.minOrNull() ?: empty.first
        var hi = v.maxOrNull() ?: empty.second
        if (hi - lo < minSpan) {
            val mid = (hi + lo) / 2.0
            lo = mid - minSpan / 2
            hi = mid + minSpan / 2
        }
        val pad = (hi - lo) * 0.08
        return (lo - pad) to (hi + pad)
    }

    private fun drawSeries(
        canvas: Canvas,
        pts: List<TempPoint>,
        value: (TempPoint) -> Double?,
        linePaint: Paint,
        dotPaint: Paint,
        xOf: (Long) -> Float,
        yOf: (Double) -> Float
    ) {
        var prev: TempPoint? = null
        var prevX: Float? = null
        var prevY: Float? = null
        for (p in pts) {
            val v = value(p) ?: continue
            val x = xOf(p.timeMs)
            val y = yOf(v)
            val px = prevX
            val py = prevY
            // Линию не тянем через разрыв данных (пауза опроса, сон ГУ)
            if (px != null && py != null && p.timeMs - prev!!.timeMs <= maxGapMs) {
                canvas.drawLine(px, py, x, y, linePaint)
            }
            canvas.drawCircle(x, y, 2.5f, dotPaint)
            prev = p
            prevX = x
            prevY = y
        }
    }

    companion object {
        /** Разрыв во времени больше этого — данные не соединять линией. */
        private const val maxGapMs = 2L * 60 * 1000 // 2 минуты
    }
}
