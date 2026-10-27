package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayProtocolExtension

/** The application supplies vehicle services without exposing its classes to the engine. */
object CarPlayUltraHost {
    @Volatile private var factory: (() -> AirPlayProtocolExtension)? = null

    fun install(factory: () -> AirPlayProtocolExtension) {
        this.factory = factory
    }

    fun create(): AirPlayProtocolExtension? = factory?.invoke()
}
