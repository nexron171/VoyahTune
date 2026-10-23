package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** Both priority lanes belong to the same authenticated AirPlay session. */
internal class VehicleStateConnection(private val session: AirPlaySession) : Closeable {
    private val channels = ConcurrentHashMap<Boolean, VehicleStateChannel>()
    private val protocol = VehicleStateProtocol { high, bytes -> channels[high]?.send(bytes) }
    private var closed = false

    @Synchronized
    fun open(uuid: String, stream: Map<String, Any?>): Map<String, Any?>? {
        if (closed) return null
        val high = when (uuid) {
            VehicleStateProtocol.HIGH_UUID -> true
            VehicleStateProtocol.NORMAL_UUID -> false
            else -> return null
        }
        val secret = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        fun key(label: String) = AirPlayCrypto.hkdfSha512(
            secret, "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII), label.toByteArray(Charsets.US_ASCII), 32,
        )
        val channel = VehicleStateChannel(
            key("DataStream-Output-Encryption-Key"), key("DataStream-Input-Encryption-Key"),
            { protocol.receive(high, it) }, { session.logDebug(it) },
        )
        channels.put(high, channel)?.close()
        return try {
            val port = channel.listen(session.localAddress ?: InetAddress.getByName("::"))
            linkedMapOf<String, Any?>("type" to 130, "streamID" to if (high) 3002 else 3001, "dataPort" to port).apply {
                stream["streamConnectionID"]?.let { this["streamConnectionID"] = unsignedPlistInteger(it) }
            }
        } catch (error: Exception) {
            channels.remove(high, channel)
            channel.close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        protocol.close()
        channels.values.forEach { it.close() }
        channels.clear()
    }
}
