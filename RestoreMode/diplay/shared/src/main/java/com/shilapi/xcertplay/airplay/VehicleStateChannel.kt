// SPDX-License-Identifier: GPL-3.0-or-later
// DataStream framing adapted from f-io/LIVI, Copyright 2026 Lasse Heitgres.
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One encrypted VSP lane, with serialized ACK and command writes sharing a nonce counter. */
internal class VehicleStateChannel(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
    private val receive: (ByteArray) -> Unit,
    private val log: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    private val writeLock = Any()
    private val writer = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "CarPlayVehicleStateWriter").apply { isDaemon = true } },
    )
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    private var readCounter = 0L
    private var writeCounter = 0L

    fun listen(address: InetAddress): Int {
        val listener = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(address, 0))
        }
        server = listener
        Thread({ accept(listener) }, "CarPlayVehicleState").apply { isDaemon = true }.start()
        return listener.localPort
    }

    private fun accept(listener: ServerSocket) {
        try {
            val accepted = listener.accept()
            socket = accepted
            if (closed.get()) {
                accepted.close()
                return
            }
            accepted.tcpNoDelay = true
            val input = DataInputStream(accepted.getInputStream())
            var pending = ByteArray(0)
            while (!closed.get()) {
                val low = input.readUnsignedByte()
                val high = input.readUnsignedByte()
                val length = low or (high shl 8)
                require(length in 1..1024) { "Invalid encrypted VSP frame" }
                val sealed = ByteArray(length + 16)
                input.readFully(sealed)
                val plain = AirPlayCrypto.chachaOpen(
                    readKey, AirPlayCrypto.nonce64(readCounter++), sealed, byteArrayOf(low.toByte(), high.toByte()),
                )
                require(pending.size + plain.size <= OpackCodec.MAX_BYTES + 32 + 1024) { "VSP buffer limit" }
                pending += plain
                var offset = 0
                while (pending.size - offset >= 32) {
                    val header = ByteBuffer.wrap(pending, offset, 32)
                    val size = header.int
                    require(size in 32..OpackCodec.MAX_BYTES + 32) { "Invalid VSP packet size" }
                    if (pending.size - offset < size) break
                    val type = header.int
                    if (type == 0x73796e63) {
                        val reply = ByteBuffer.allocate(32).putInt(32).putInt(0x72706c79).array()
                        pending.copyInto(reply, 20, offset + 20, offset + 28)
                        sendPacket(reply)
                    }
                    if (type != 0x72706c79 && size > 32) receive(pending.copyOfRange(offset + 32, offset + size))
                    offset += size
                }
                pending = pending.copyOfRange(offset, pending.size)
            }
        } catch (error: Exception) {
            if (!closed.get()) log("vehicle state lane closed: ${error.javaClass.simpleName}")
        } finally {
            close()
        }
    }

    fun send(body: ByteArray) {
        require(body.size <= OpackCodec.MAX_BYTES)
        if (closed.get()) return
        val packet = ByteBuffer.allocate(32 + body.size)
        packet.putInt(packet.capacity()).putInt(0x6173796e)
        packet.position(16)
        packet.putInt(0x636d6e64)
        packet.position(32)
        packet.put(body)
        try {
            writer.execute {
                try {
                    sendPacket(packet.array())
                } catch (error: Exception) {
                    log("vehicle state send failed: ${error.javaClass.simpleName}")
                }
            }
        } catch (full: RejectedExecutionException) {
            close()
            log("vehicle state outgoing queue unavailable")
        }
    }

    private fun sendPacket(packet: ByteArray) = synchronized(writeLock) {
        if (closed.get()) return@synchronized
        val output = socket?.getOutputStream() ?: return@synchronized
        try {
            var offset = 0
            while (offset < packet.size) {
                val end = minOf(offset + 1024, packet.size)
                val size = end - offset
                val aad = byteArrayOf(size.toByte(), (size shr 8).toByte())
                val sealed = AirPlayCrypto.chachaSeal(
                    writeKey, AirPlayCrypto.nonce64(writeCounter++), packet.copyOfRange(offset, end), aad,
                )
                output.write(aad)
                output.write(sealed)
                offset = end
            }
            output.flush()
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        runCatching { server?.close() }
        writer.shutdownNow()
    }
}
