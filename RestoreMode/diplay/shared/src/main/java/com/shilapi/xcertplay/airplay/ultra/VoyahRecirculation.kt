package com.shilapi.xcertplay.airplay.ultra

/** AirConditionFrontFragment.updateView in the reviewed H97C APK: 0=inner, 1=outer, 2=auto.
 * Its voice listener labels 0/1 the opposite way; use the actual climate screen's mapping.
 */
object VoyahRecirculation {
    data class State(val on: Boolean, val automatic: Boolean)
    fun decode(mode: Number?): State? = when (mode?.toDouble()) {
        0.0 -> State(true, false)
        1.0 -> State(false, false)
        // No manual recirculation request in AUTO. This does not claim a physical flap position.
        2.0 -> State(false, true)
        else -> null
    }
    fun matchesManual(mode: Number?, on: Boolean): Boolean =
        mode?.toDouble() == if (on) 0.0 else 1.0
}
