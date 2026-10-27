package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class AudioCodecKind {
    AAC_LC,
    OPUS,
    LPCM,
}

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
    /**
     * The rate of the stream's RTP timestamps. Opus is always decoded at 48 kHz, but audioFormat
     * 0x10000000 and 0x20000000 count at 16 and 24 kHz (see [MicrophoneConfig.opusClockRate]).
     */
    val rtpClockRate: Int = sampleRate,
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
    private val expectedPeer: InetAddress? = null,
    private val recoveryBudgetMillis: (() -> Long)? = null,
    remoteControlPort: Int? = null,
    private val isCurrent: () -> Boolean = { true },
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}

        fun onRtp(rtp: ByteArray, sample: Int) {}

        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private val closed = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var started = false
    private val deliveryLock = Any()
    private var listener: Listener? = null
    private var requestSequence = 0
    private var remoteControl =
        remoteControlPort
            ?.takeIf { it in 1..65535 }
            ?.let { port -> expectedPeer?.let { InetSocketAddress(it, port) } }
    private val recovery = recoveryBudgetMillis?.let { budget ->
        AudioPacketRecovery(budget, ::requestResend)
    }
    private var lastRecoveryReportNanos = 0L
    private var resendConfirmed = false

    fun listen(listener: Listener): Pair<Int, Int> {
        check(this.listener == null && !closed.get())
        this.listener = listener
        val data = bindAnyPort()
        // Keep short Wi-Fi bursts in the kernel while decrypting or scheduling pauses
        // the receive thread. The platform may cap this request; log the actual size.
        val originalBufferBytes = runCatching { data.receiveBufferSize }.getOrDefault(0)
        if (originalBufferBytes < AUDIO_RECEIVE_BUFFER_BYTES) {
            runCatching { data.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES }
        }
        onDiagnostic(
            "Audio UDP receive buffer type=$streamType original=$originalBufferBytes requested=$AUDIO_RECEIVE_BUFFER_BYTES actual=${runCatching { data.receiveBufferSize }.getOrDefault(0)}",
        )
        val control = bindAnyPort()
        if (recovery != null) data.soTimeout = RECOVERY_POLL_MILLIS
        dataSocket = data
        controlSocket = control
        dataThread =
            Thread({ runData(data, listener) }, "airplay-audio-rx").apply {
                isDaemon = true
                start()
            }
        controlThread =
            Thread({ runControl(control, listener) }, "airplay-rtcp-rx").apply {
                isDaemon = true
                start()
            }
        return data.localPort to control.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataSocket?.close()
        controlSocket?.close()
        dataThread?.interrupt()
        controlThread?.interrupt()
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        // When the head unit opens its 360° camera, default-priority audio threads wait behind it
        // (#737).
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        }
        val stats = StreamReceiveStats("audio type=$streamType", onDiagnostic)
        val buffer = ByteArray(DATAGRAM_BYTES)
        try {
            while (!closed.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    stats.reading()
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    drainRecovery()
                    continue
                } catch (_: Exception) {
                    if (closed.get()) return else continue
                }
                if (expectedPeer != null && packet.address != expectedPeer) continue
                stats.received(
                    size = packet.length,
                    sequence =
                        if (packet.length >= RTP_HEADER_LEN) {
                            ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                        } else null,
                    timestamp = if (packet.length >= RTP_HEADER_LEN) readU32Be(buffer, 4) else null,
                )
                val wire = packet.data.copyOf(packet.length)
                val response = AudioResendPacket.audioResponse(wire)
                processPacket(response ?: wire, listener, retransmitted = response != null)
                stats.processed()
            }
        } finally {
            stats.flush(ended = true)
            synchronized(deliveryLock) {
                recovery?.let {
                    onDiagnostic("Audio: recovery type=$streamType ${it.diagnostic()} ended=true")
                }
            }
        }
    }

    private fun processPacket(
        wire: ByteArray,
        listener: Listener,
        retransmitted: Boolean = false,
    ): Boolean {
        if (closed.get() || !isCurrent()) return false
        receivedPackets.incrementAndGet()
        if (wire.size < RTP_HEADER_LEN + TAIL_LEN || wire[0].toInt() and 0xc0 != 0x80) {
            listener.onPacket(wire, null, null, IOException("invalid audio RTP packet"))
            return false
        }
        val sample = readU32Be(wire, 4)
        val sealedEnd = wire.size - NONCE_LEN
        val nonce = ByteArray(12).also { wire.copyInto(it, 4, sealedEnd, wire.size) }
        val payload =
            try {
                AirPlayCrypto.chachaOpen(
                    key,
                    nonce,
                    wire.copyOfRange(RTP_HEADER_LEN, sealedEnd),
                    wire.copyOfRange(4, RTP_HEADER_LEN),
                )
            } catch (error: Exception) {
                authenticationFailures.incrementAndGet()
                listener.onPacket(wire, null, sample, error)
                return false
            }
        val rtp = wire.copyOf(RTP_HEADER_LEN) + payload
        decryptedPackets.incrementAndGet()
        synchronized(deliveryLock) {
            if (closed.get() || !isCurrent()) return false
            listener.onPacket(wire, rtp, sample, null)
            val packetRecovery = recovery
            if (packetRecovery == null) {
                deliver(rtp, listener)
            } else {
                val previousRecovered = packetRecovery.recoveredPackets
                packetRecovery.offer(rtp, System.nanoTime())
                drainRecovery()
                if (
                    retransmitted &&
                        packetRecovery.recoveredPackets > previousRecovered &&
                        !resendConfirmed
                ) {
                    resendConfirmed = true
                    onDiagnostic("Audio: resend confirmed type=$streamType authenticated=true")
                }
            }
        }
        return true
    }

    private fun deliver(rtp: ByteArray, listener: Listener) {
        if (closed.get() || !isCurrent()) return
        val sample = readU32Be(rtp, 4)
        if (!started) {
            started = true
            listener.onStarted(sample)
        }
        if (!closed.get() && isCurrent()) listener.onRtp(rtp, sample)
    }

    private fun drainRecovery() =
        synchronized(deliveryLock) {
            if (closed.get() || !isCurrent()) return@synchronized
            val packetRecovery = recovery ?: return@synchronized
            val receiver = listener ?: return@synchronized
            val nowNanos = System.nanoTime()
            packetRecovery.drain(nowNanos).forEach { deliver(it, receiver) }
            if (nowNanos - lastRecoveryReportNanos >= RECOVERY_REPORT_NANOS) {
                lastRecoveryReportNanos = nowNanos
                onDiagnostic(
                    "Audio: recovery type=$streamType ${packetRecovery.diagnostic()} confirmed=$resendConfirmed",
                )
            }
        }

    private fun requestResend(firstSequence: Int, count: Int): Boolean {
        val endpoint = remoteControl ?: return false
        val socket = controlSocket ?: return false
        if (closed.get() || !isCurrent()) return false
        val message = AudioResendPacket.request(requestSequence++, firstSequence, count)
        return runCatching { socket.send(DatagramPacket(message, message.size, endpoint)) }
            .isSuccess
    }

    private fun runControl(socket: DatagramSocket, listener: Listener) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        while (!closed.get()) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(datagram)
            } catch (_: Exception) {
                if (closed.get()) return else continue
            }
            val peer = expectedPeer ?: continue
            if (datagram.address != peer || datagram.length < 4) continue
            val packet = datagram.data.copyOf(datagram.length)
            val type = packet[1].toInt() and 0x7f
            val response = AudioResendPacket.audioResponse(packet)
            val authenticated =
                response?.let { processPacket(it, listener, retransmitted = true) } == true
            val timing =
                packet.size >= 20 &&
                    packet[0].toInt() and 0xc0 == 0x80 &&
                    (type == 0x54 || type == 0x57)
            if (authenticated || timing) {
                synchronized(deliveryLock) {
                    if (remoteControl == null) {
                        remoteControl = InetSocketAddress(peer, datagram.port)
                        onDiagnostic("Audio: recovery control available type=$streamType")
                    }
                }
            }
        }
    }

    private fun bindAnyPort(): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        return socket
    }

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val TAG = "xcertplay-usb"
        const val DATAGRAM_BYTES = 4_096
        const val AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        const val RECOVERY_POLL_MILLIS = 10
        const val RECOVERY_REPORT_NANOS = 5_000_000_000L
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        val isAacLc = (bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO)) != 0L
        val isOpus = (bits and OPUS_MONO) != 0L
        val pcm = PCM_FORMAT[bits]
        return when {
            isOpus ->
                AudioFormat(
                    AudioCodecKind.OPUS,
                    48_000,
                    1,
                    payloadType,
                    audioType,
                    rtpClockRate = MicrophoneConfig.opusClockRate(bits),
                )
            isAacLc ->
                AudioFormat(
                    AudioCodecKind.AAC_LC,
                    if ((bits and AAC_LC_48K_STEREO) != 0L) 48_000 else 44_100,
                    2,
                    payloadType,
                    audioType,
                )
            pcm != null ->
                AudioFormat(AudioCodecKind.LPCM, pcm.first, pcm.second, payloadType, audioType)
            else -> AudioFormat(AudioCodecKind.LPCM, 44_100, 2, payloadType, audioType)
        }
    }

    private const val AAC_LC_44K_STEREO = 0x400000L
    private const val AAC_LC_48K_STEREO = 0x800000L
    private const val OPUS_MONO = 0x10000000L or 0x20000000L or 0x40000000L

    private val PCM_FORMAT =
        mapOf(
            0x4L to (8_000 to 1),
            0x8L to (8_000 to 2),
            0x10L to (16_000 to 1),
            0x20L to (16_000 to 2),
            0x40L to (24_000 to 1),
            0x80L to (24_000 to 2),
            0x100L to (32_000 to 1),
            0x200L to (32_000 to 2),
            0x400L to (44_100 to 1),
            0x800L to (44_100 to 2),
            0x4000L to (48_000 to 1),
            0x8000L to (48_000 to 2),
        )
}
