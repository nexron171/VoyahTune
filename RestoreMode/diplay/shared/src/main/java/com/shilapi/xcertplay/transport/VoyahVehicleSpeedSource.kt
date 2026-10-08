package com.shilapi.xcertplay.transport

/** Bounded, nonblocking buffer fed by Native's monotonic CAN samples. */
class VoyahVehicleSpeedSource(private val now: () -> Long) : VehicleSpeedSource {
    private var started = false
    private var gear: VehicleGear? = null
    private var lastTime = -1L
    private val samples = ArrayDeque<VehicleSpeedSample>()

    @Synchronized
    fun update(kmh: Int, gearValue: Int, elapsed: Long) {
        val nextGear = when (gearValue) {
            0 -> VehicleGear.PARK
            1 -> VehicleGear.REVERSE
            2 -> VehicleGear.NEUTRAL
            3 -> VehicleGear.DRIVE
            else -> null
        }
        if (nextGear != gear) samples.clear()
        gear = nextGear
        val age = now() - elapsed
        if (kmh !in 0..300 || nextGear == null || elapsed < 0 || age !in 0..3000) {
            samples.clear()
            return
        }
        if (!started || elapsed <= lastTime) return
        lastTime = elapsed
        if (samples.size == 32) samples.removeFirst()
        samples.addLast(VehicleSpeedSample(elapsed, kmh / 3.6))
    }

    @Synchronized fun reset() { samples.clear(); gear = null; lastTime = -1 }
    @Synchronized override fun start() { reset(); started = true }
    @Synchronized override fun stop() { started = false; reset() }
    @Synchronized override fun drain(): VehicleSpeedReading? {
        val currentGear = gear ?: return null
        val currentTime = now()
        val fresh = samples.filter { currentTime - it.elapsedMillis in 0..3000 }
        samples.clear()
        return fresh.takeIf { it.isNotEmpty() }?.let { VehicleSpeedReading(currentGear, it) }
    }
}
