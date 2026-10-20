package com.bms.monitor

/** Одна точка истории: время + температуры (°C), SOC (%) и drivingCurrentLimit (А); null — не читается. */
data class TempPoint(
    val timeMs: Long,
    val min: Double?,
    val max: Double?,
    val inlet: Double?,
    val outlet: Double?,
    val soc: Double?,
    val dcl: Double?
)

/**
 * Скользящее окно истории температур (последние 30 минут).
 * Тред-безопасно: добавление из IO-корутины, снимок для UI.
 */
class TempHistory {

    private val points = ArrayDeque<TempPoint>()
    private val windowMs = 30L * 60 * 1000 // 30 минут

    @Synchronized
    fun add(p: TempPoint) {
        // Стенные часы шагнули назад (ГУ уснуло и при пробуждении время
        // скорректировалось): точки «будущего» относительно новой — мусор,
        // иначе график соединяет кластеры до и после скачка часов.
        while (points.isNotEmpty() && points.last().timeMs > p.timeMs) {
            points.removeLast()
        }
        points.addLast(p)
        val cutoff = p.timeMs - windowMs
        while (points.isNotEmpty() && points.first().timeMs < cutoff) {
            points.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): List<TempPoint> = points.toList()
}
