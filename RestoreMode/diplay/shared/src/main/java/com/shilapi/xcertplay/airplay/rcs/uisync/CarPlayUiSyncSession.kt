package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.ProtocolTraceFormatter
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec

/**
 * CarPlayClusterControl wire exchange, adapted from f-io/LIVI ui_sync.rs (GPL-3.0).
 * OPACK routes a panel; its data contains a binary plist header with numeric message types.
 * A command is another binary plist in header.data. Swift enum names are not wire values.
 * Calls are serialized by RcsDataStream. Transport sync/rply is handled by its owner.
 */
internal class CarPlayUiSyncSession(private val trace: (String) -> Unit = {}) {
    var ready = false
        private set
    var latestCommand: Map<String, Any?>? = null
        private set
    private var session = 0L
    private var nextTx = 1L
    private var lastRx = 0L
    private var awaiting = false
    private var phoneId: Any? = null
    private var panel = emptyMap<String, Any?>()
    private var closed = false

    // No unsolicited RESET on open: the phone selects the panel and supplies its session id.
    fun receive(body: ByteArray): List<ByteArray> {
        check(!closed) { "UI Sync session is closed" }
        require(body.size <= 262_144) { "UI Sync body exceeds limit" }
        val frame = dictionary(OpackCodec.decode(body), "frame")
        val header = dictionary(BplistCodec.decode(frame["data"] as? ByteArray
            ?: error("UI Sync frame requires binary data")), "header")
        val type = integer(header, "type")
        val ssn = integer(header, "ssn")
        val psn = integer(header, "psn")
        val route = frame - "data"
        trace("UI SYNC RX type=$type ssn=$ssn psn=$psn display=${route["displayUUID"]}")
        if (type == 0L) {
            if (awaiting) return emptyList()
            check(session < Long.MAX_VALUE) { "UI Sync session exhausted" }
            session++
            nextTx = 1L; lastRx = 0L; ready = false; awaiting = true
            latestCommand = null; panel = route; phoneId = header["id"]
            return listOf(message(0, extra = withId(mapOf("version" to 2L))))
        }
        if (ssn != session || route["displayUUID"] != panel["displayUUID"]) {
            trace("UI SYNC ignored other session/panel ssn=$ssn")
            return emptyList()
        }
        panel = route
        if (type == 1L && awaiting) {
            val version = minOf((header["version"]?.let { integer(header, "version") } ?: 1L), 2L)
            require(version >= 1) { "Invalid UI Sync version" }
            awaiting = false; ready = true; lastRx = psn
            trace("UI SYNC STATE ready ssn=$session version=$version")
            val fields = mapOf("version" to version)
            return listOf(message(1, extra = if (version >= 2) withId(fields) else fields),
                command(0x11, mapOf("allow" to true)), command(0x07, mapOf("tokenID" to 4711L)))
        }
        if (!ready) return emptyList()
        if (type == 3L) {
            lastRx = maxOf(lastRx, psn)
            return emptyList()
        }
        if (type != 2L) return emptyList()
        val noAck = header["noAck"]?.let { it as? Boolean ?: error("Invalid UI Sync noAck") } ?: false
        if (psn <= lastRx) return if (noAck) emptyList() else listOf(message(3))
        val receivedCommand = (header["data"] as? ByteArray)?.let { dictionary(BplistCodec.decode(it), "command") }
        val kind = receivedCommand?.let { integer(it, "type") }
        lastRx = psn
        latestCommand = receivedCommand
        trace("UI SYNC COMMAND type=$kind values=${ProtocolTraceFormatter.pretty(receivedCommand)}")
        val out = mutableListOf<ByteArray>()
        if (!noAck) out += message(3)
        if (kind == 0x08L) out += command(0x07, mapOf("tokenID" to 4711L))
        return out
    }

    private fun withId(fields: Map<String, Any?>) = LinkedHashMap(fields).apply {
        phoneId?.let { put("id", it) }
    }

    private fun command(type: Long, fields: Map<String, Any?>) = message(2,
        mapOf("type" to type, "data" to fields))

    private fun message(type: Long, command: Map<String, Any?>? = null,
        extra: Map<String, Any?> = emptyMap()): ByteArray {
        check(nextTx < Long.MAX_VALUE) { "UI Sync packet sequence exhausted" }
        val header = linkedMapOf<String, Any?>("type" to type, "ssn" to session,
            "psn" to nextTx++, "asn" to lastRx).apply {
            putAll(extra)
            command?.let { put("data", BplistCodec.encode(it)) }
        }
        trace("UI SYNC TX type=$type ssn=$session psn=${header["psn"]} asn=$lastRx")
        return OpackCodec.encode(LinkedHashMap(panel).apply { put("data", BplistCodec.encode(header)) })
    }

    fun close() { closed = true; ready = false; panel = emptyMap(); phoneId = null; latestCommand = null }

    private fun dictionary(value: Any?, name: String): Map<String, Any?> {
        val map = value as? Map<*, *> ?: error("UI Sync $name must be a dictionary")
        require(map.keys.all { it is String }) { "UI Sync $name keys must be strings" }
        return map.entries.associate { it.key as String to it.value }
    }

    private fun integer(map: Map<String, Any?>, name: String): Long {
        val value = map[name]
        require(value is Byte || value is Short || value is Int || value is Long) { "UI Sync $name must be an integer" }
        return (value as Number).toLong().also { require(it >= 0) { "UI Sync $name must be non-negative" } }
    }
}
