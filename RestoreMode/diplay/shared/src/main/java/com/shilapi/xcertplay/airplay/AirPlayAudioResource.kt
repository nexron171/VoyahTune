package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A radio selection takes mainAudio from iOS; a successful socket write is not a grant. */
internal class AirPlayAudioResource(
    private val send: (Map<String, Any?>, (Int) -> Unit) -> Boolean,
) : Closeable {
    private class Request {
        val done = CountDownLatch(1)
        @Volatile var cseq: Int? = null
        @Volatile var granted = false
    }
    private val pending = AtomicReference<Request?>()
    @Volatile private var closed = false

    fun takeForRadio(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        val request = Request()
        if (closed || !pending.compareAndSet(null, request)) return false
        var submitted = false
        try {
            if (closed) return false
            submitted = send(takeCommand()) { request.cseq = it }
            if (!submitted) return false
            return request.done.await(timeoutMillis, TimeUnit.MILLISECONDS) && request.granted && !closed
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            // Cancel a delayed grant too: it must not leave iOS muted after CAF refused the start.
            if (submitted && !request.granted && !closed) releaseRadio()
            pending.compareAndSet(request, null)
        }
    }

    fun modesChanged(params: Map<*, *>) {
        val owner = mainAudioOwner(params) ?: return
        if (owner == ACCESSORY) pending.get()?.takeIf { it.cseq != null }?.let { finish(it, true) }
    }

    fun releaseRadio() {
        if (!closed) send(mapOf("type" to "changeModes", "params" to mapOf(
            "resources" to listOf(mapOf("resourceID" to 2, "transferType" to 2))))) {}
    }

    fun reply(message: RtspMessage.Request) {
        val request = pending.get() ?: return
        val cseq = message.headers["cseq"]?.toIntOrNull() ?: return
        if (cseq != request.cseq) return
        if (message.path.toIntOrNull() != 200) { finish(request, false); return }
        val body = runCatching { BplistCodec.decode(message.body) as? Map<*, *> }.getOrNull() ?: return
        if ("status" in body && (body["status"] as? Number)?.toDouble() != 0.0) { finish(request, false); return }
        val owner = (body["params"] as? Map<*, *>)?.let(::mainAudioOwner) ?: return
        finish(request, owner == ACCESSORY)
    }

    private fun finish(request: Request, granted: Boolean) = synchronized(request) {
        if (pending.get() !== request || request.done.count == 0L) return@synchronized
        request.granted = granted
        request.done.countDown()
    }

    override fun close() {
        closed = true
        pending.get()?.let { finish(it, false) }
    }

    companion object {
        private const val ACCESSORY = 2
        /** Take mainAudio only; permit subsequent user music, Siri and calls to take/borrow it. */
        fun takeCommand(): Map<String, Any?> = mapOf("type" to "changeModes", "params" to mapOf(
            "resources" to listOf(mapOf("resourceID" to 2, "transferType" to 1,
                "transferPriority" to 500, "takeConstraint" to 100, "borrowConstraint" to 100))))

        fun mainAudioOwner(params: Map<*, *>): Int? {
            val entries = params["resources"] as? List<*> ?: return null
            val audio = entries.filterIsInstance<Map<*, *>>().filter {
                it["resourceID"] == "mainAudio" || number(it["resourceID"]) == 2
            }.singleOrNull() ?: return null
            return when (val entity = audio["entity"]) {
                "controller" -> 1
                "accessory" -> ACCESSORY
                else -> number(entity)?.takeIf { it == 1 || it == ACCESSORY }
            }
        }
        private fun number(value: Any?): Int? = (value as? Number)?.toDouble()
            ?.takeIf { it == 1.0 || it == 2.0 }?.toInt()
    }
}
