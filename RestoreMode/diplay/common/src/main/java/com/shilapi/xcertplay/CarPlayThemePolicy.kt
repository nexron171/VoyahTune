package com.shilapi.xcertplay

/** Vehicle light controls the iPhone projection only when explicitly selected. */
internal fun projectionNight(mode: CarPlayNightMode, vehicleNight: Boolean?, fallbackNight: Boolean): Boolean =
    if (mode == CarPlayNightMode.VEHICLE) vehicleNight ?: fallbackNight else fallbackNight
