package com.bms.monitor

import android.graphics.Color

object ColorUtils {

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)

    /**
     * Температура: тройной градиент -40 (синий) … 0 (зелёный) … +40 (красный),
     * за пределами — крайние цвета. NaN -> серый.
     */
    fun tempColor(value: Double?): Int {
        if (value == null || value.isNaN()) return Color.rgb(120, 120, 120)
        val v = value.coerceIn(-40.0, 40.0)
        // Светлые цвета — значения на чёрном фоне читаются ярче
        val blue = Color.rgb(60, 125, 255)
        val green = Color.rgb(60, 205, 90)
        val red = Color.rgb(245, 70, 70)
        val (from, to, t) = if (v < 0) Triple(blue, green, ((v + 40.0) / 40.0).toFloat())
        else Triple(green, red, (v / 40.0).toFloat())
        return Color.rgb(
            lerp(Color.red(from), Color.red(to), t),
            lerp(Color.green(from), Color.green(to), t),
            lerp(Color.blue(from), Color.blue(to), t)
        )
    }

    /**
     * Напряжение ячейки: градиент от 0 (красный) до 5 (зелёный).
     */
    fun cellColor(volts: Double?): Int {
        if (volts == null || volts.isNaN()) return Color.rgb(120, 120, 120)
        val t = ((volts / 5.0).coerceIn(0.0, 1.0)).toFloat()
        return cellColorT(t)
    }

    /**
     * Нормализованный градиент: t=0 (минимум пачки) — красный,
     * t=1 (максимум пачки) — зелёный. Значения ячеек отличаются
     * сотыми долями вольта, поэтому абсолютная шкала 0..5 В их не
     * различает — красим относительно min/max текущих данных.
     */
    fun cellColorT(t: Float): Int {
        val t = t.coerceIn(0f, 1f)
        val red = Color.rgb(230, 40, 40)
        val green = Color.rgb(40, 200, 70)
        return Color.rgb(
            lerp(Color.red(red), Color.red(green), t),
            lerp(Color.green(red), Color.green(green), t),
            lerp(Color.blue(red), Color.blue(green), t)
        )
    }

    /**
     * Дисбаланс (max-min): 0 В — зелёный, 0.040 В и выше — красный,
     * между — плавный градиент.
     */
    fun imbalanceColor(diff: Double): Int {
        val t = ((diff / 0.040).coerceIn(0.0, 1.0)).toFloat()
        val green = Color.rgb(40, 200, 70)
        val red = Color.rgb(230, 40, 40)
        return Color.rgb(
            lerp(Color.red(green), Color.red(red), t),
            lerp(Color.green(green), Color.green(red), t),
            lerp(Color.blue(green), Color.blue(red), t)
        )
    }
}
