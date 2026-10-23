package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BooleanSupplier

/** App-owned Native IPC supplies observed values; protocol writes never mutate this snapshot. */
object VehicleClimate {
    fun interface Result { fun complete(error: Int) }
    interface Host { fun write(field: String, value: Double, current: BooleanSupplier, result: Result) }

    @Volatile private var host: Host? = null
    @Volatile private var values: Map<String, Double> = emptyMap()
    private val listeners = CopyOnWriteArrayList<(Map<String, Double>) -> Unit>()
    val available: Boolean get() = host != null

    @JvmStatic fun install(value: Host?) { host = value }
    @JvmStatic fun update(observed: Map<String, Double>) {
        values = observed.toMap()
        listeners.forEach { it(values) }
    }

    fun snapshot(): Map<String, Double> = values

    fun observe(listener: (Map<String, Double>) -> Unit): Closeable {
        listeners.add(listener)
        return Closeable { listeners.remove(listener) }
    }

    fun write(field: String, value: Double, current: BooleanSupplier, result: Result) {
        val target = host
        if (target == null || !current.asBoolean) result.complete(-6727) else target.write(field, value, current, result)
    }
}
