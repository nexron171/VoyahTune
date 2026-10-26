package com.shilapi.xcertplay.airplay

/** Optional per-session protocol adapter selected by the CarPlay profile. */
interface AirPlayProtocolExtension {
    fun forSession(): AirPlayProtocolExtension
    fun decorateInfo(info: MutableMap<String, Any?>) {}
    /** Optional catalog display id, shared by /info, HID binding and later display commands. */
    fun displayIdentifier(streamType: Int): String? = null
    fun onInfoRequest(body: ByteArray) {}
    /** Called after /auth-setup is answered; diagnostics only, it cannot change the answer. */
    fun onAuthSetup(headers: Map<String, String>, body: ByteArray, status: Int) {}
    fun enabledFeatures(proposed: List<*>?): List<String> = emptyList()
    fun onFeaturesNegotiated(features: List<String>) {}
    fun openDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onSetupResponseSent(session: AirPlaySession) {}
    fun mediaTeardownTypes(body: Map<String, Any?>?, original: List<Int>?): List<Int>? = original
    fun onTeardown(session: AirPlaySession, body: Map<String, Any?>?) {}
    fun onSessionClosed(session: AirPlaySession) {}
}
