package com.shilapi.xcertplay.airplay.ultra

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Shares native radio starts with iOS, including starts on the passenger display. */
internal class VoyahRadioAudioSync(
    private val takeAudio: () -> Boolean,
    private val releaseAudio: () -> Unit,
    private val trace: (String) -> Unit,
) : Closeable {
    private val state = Any()
    private val operation = Any()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "voyah-radio-audio").apply { isDaemon = true }
    }
    private var closed = false
    private var ready = false
    private var playing = false
    private var generation = 0L
    private var pending: Long? = null
    private var scheduled = false
    private var commands = 0
    private var startingCommands = 0

    /** OEM callbacks run on Android's main thread; never wait for the phone here. */
    fun observe(radio: UltraRadioSnapshot?) = synchronized(state) {
        if (closed) return@synchronized
        val actual = if (radio?.connected == true) radio.state?.playing else false
        // Buffering/unknown is not a stop followed by a new user selection.
        if (actual == null || actual == playing) return@synchronized
        playing = actual
        generation++
        pending = if (actual && startingCommands == 0) generation else null
        if (pending != null) trace("RADIO EXTERNAL playing=true station=${radio?.currentId}")
        schedule()
    }

    /** A source can report playing before AirPlay has attached its event connection. */
    fun attach() = synchronized(state) {
        if (!closed) { ready = true; schedule() }
    }

    /** Serialize with CAF selections; their OEM echo must not request the resource twice. */
    fun <T> control(startsRadio: Boolean, action: () -> T): T {
        synchronized(state) {
            commands++
            if (startsRadio) startingCommands++
            generation++
            pending = null
        }
        try {
            return synchronized(operation) { action() }
        } finally {
            synchronized(state) {
                commands--
                if (startsRadio) startingCommands--
                schedule()
            }
        }
    }

    // One worker and one replaceable pending transition: RDS/poll updates cannot grow a queue.
    private fun schedule() {
        if (closed || !ready || commands != 0 || pending == null || scheduled) return
        scheduled = true
        try { worker.execute(::drain) }
        catch (_: RejectedExecutionException) { scheduled = false }
    }

    private fun drain() {
        while (true) {
            val ticket = synchronized(state) {
                if (closed || !ready || commands != 0 || pending == null) {
                    scheduled = false
                    return
                }
                pending!!
            }
            synchronized(operation) {
                val current = synchronized(state) {
                    if (closed || commands != 0 || !playing || pending != ticket || generation != ticket) false
                    else { pending = null; true }
                }
                if (current) {
                    val granted = runCatching(takeAudio).getOrElse {
                        trace("RADIO EXTERNAL AUDIO error=${it.javaClass.simpleName}: ${it.message}")
                        false
                    }
                    val stillCurrent = synchronized(state) { !closed && playing && generation == ticket }
                    // A stop/new selection during the wait invalidates even a late grant.
                    if (granted && !stillCurrent) runCatching(releaseAudio)
                    trace("RADIO EXTERNAL AUDIO mainAudio accessoryGranted=$granted current=$stillCurrent")
                }
            }
        }
    }

    override fun close() {
        synchronized(state) { closed = true; generation++; pending = null }
        worker.shutdownNow()
    }
}
