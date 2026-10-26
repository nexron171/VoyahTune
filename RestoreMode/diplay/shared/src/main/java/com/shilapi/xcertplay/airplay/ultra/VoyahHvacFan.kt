package com.shilapi.xcertplay.airplay.ultra

/** H97C cache is 0..7; TX31 AC_BLOWER is 1..8, as in OEM onAdjustWindVolume. */
object VoyahHvacFan {
    const val MAX_LEVEL = 7L
    fun wireValue(level: Long): Int {
        require(level in 0..MAX_LEVEL) { "Invalid H97C fan level" }
        return level.toInt() + 1
    }
}
