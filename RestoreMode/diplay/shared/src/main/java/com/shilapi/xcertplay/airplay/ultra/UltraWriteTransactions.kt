package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec

/** Shared by both VSP channels so a retransmitted action cannot advance a cycle twice. */
internal class UltraWriteTransactions {
    private data class Completed(val request: ByteArray, val results: Map<Long, Long>)

    private val completed = linkedMapOf<Long, Completed>()

    @Synchronized
    fun execute(
        transaction: Long,
        requested: Map<Long, Any?>,
        write: () -> Map<Long, Long>,
    ): Map<Long, Long>? {
        val encoded = OpackCodec.encode(requested.toSortedMap())
        completed[transaction]?.let {
            return if (it.request.contentEquals(encoded)) it.results else null
        }
        val results = write().toMap()
        completed[transaction] = Completed(encoded, results)
        if (completed.size > 256) completed.remove(completed.keys.first())
        return results
    }
}
