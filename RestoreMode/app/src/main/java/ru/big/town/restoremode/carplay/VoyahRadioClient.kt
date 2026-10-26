package ru.big.town.restoremode.carplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import com.shilapi.xcertplay.airplay.ultra.*
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Vehicle radio interop verified against rdsapp-release-signed.apk; no OEM code is loaded. */
internal class VoyahRadioClient(context: Context, private val trace: (String) -> Unit,
    private val onChanged: () -> Unit,
    private val openBrowser: (Context, ComponentName, MediaBrowser.ConnectionCallback) -> MediaBrowser =
        { app, component, callback -> MediaBrowser(app, component, callback, null) },
    private val openController: (Context, android.media.session.MediaSession.Token) -> MediaController =
        { app, token -> MediaController(app, token) },
    private val send: (MediaController, VoyahRadioOem.Command, Int?) -> Unit = { remote, command, index ->
        val controls = remote.transportControls
        when (command) {
            VoyahRadioOem.Command.PLAY -> controls.play()
            VoyahRadioOem.Command.PAUSE -> controls.pause()
            VoyahRadioOem.Command.NEXT -> controls.skipToNext()
            VoyahRadioOem.Command.PREVIOUS -> controls.skipToPrevious()
            VoyahRadioOem.Command.TUNE -> controls.sendCustomAction("COMMAND_PLAY_BY_INDEX",
                Bundle().apply { putInt("DATA_INDEX", requireNotNull(index)) })
        }
    },
) : Closeable {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var radioText: VoyahRadioOem.RadioText? = null
    private var radioTextAt = 0L
    private val component = ComponentName(VoyahRadioOem.PACKAGE, VoyahRadioOem.SERVICE)
    val supported = runCatching {
        app.packageManager.resolveService(Intent().setComponent(component), 0)?.serviceInfo
            ?.let { it.enabled && it.exported && it.applicationInfo.enabled } == true
    }.getOrDefault(false)
    @Volatile private var closed = false
    @Volatile var snapshot = UltraRadioSnapshot()
        private set
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var listEpoch = 0L
    private var listing = false
    private var listedAt = 0L
    private val listWaiters = arrayListOf<(Boolean) -> Unit>()
    private var pending: Write? = null
    private val connectTimeout = Runnable { if (controller == null && browser != null) unavailable("connection timed out") }
    private val listTimeout = Runnable { finishList(false) }
    private val writeTimeout = Runnable { pending?.let { finishWrite(it, false, "OEM readback timed out") } }
    private val poll = object : Runnable {
        override fun run() {
            val remote = controller ?: return
            if (closed) return
            observe {
                publish(remote.metadata, remote.playbackState)
                if (SystemClock.elapsedRealtime() - listedAt >= 5_000) refreshList()
            }
            if (!closed && controller === remote) handler.postDelayed(this, 2_000)
        }
    }

    fun start() = handler.post {
        if (closed || browser != null) return@post
        if (!supported) { trace("RADIO UNAVAILABLE OEM service not installed/exported"); return@post }
        lateinit var candidate: MediaBrowser
        val connection = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                if (closed || browser !== candidate) return
                observe {
                    handler.removeCallbacks(connectTimeout)
                    clearController()
                    val remote = openController(app, candidate.sessionToken)
                    val observer = object : MediaController.Callback() {
                        private fun current() = !closed && controller === remote
                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            if (current()) observe { publish(metadata, remote.playbackState); refreshList() }
                        }
                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            if (current()) observe { publish(remote.metadata, state) }
                        }
                        override fun onSessionEvent(event: String, extras: Bundle?) {
                            if (current()) refreshList()
                        }
                        override fun onSessionDestroyed() { if (current()) unavailable("session destroyed") }
                    }
                    controller = remote
                    callback = observer
                    remote.registerCallback(observer, handler)
                    trace("RADIO CONNECTED component=${component.flattenToShortString()} CAF_MEDIA=available")
                    publish(remote.metadata, remote.playbackState)
                        refreshList()
                    handler.post(poll)
                }
            }
            override fun onConnectionFailed() {
                if (!closed && browser === candidate) unavailable("connection rejected or service unavailable")
            }
            override fun onConnectionSuspended() {
                if (!closed && browser === candidate) {
                    clearController()
                    trace("RADIO UNAVAILABLE connection suspended")
                    handler.postDelayed(connectTimeout, 10_000)
                }
            }
        }
        observe {
            candidate = openBrowser(app, component, connection)
            browser = candidate
            trace("RADIO CONNECT component=${component.flattenToShortString()}")
            handler.postDelayed(connectTimeout, 10_000)
            candidate.connect()
        }
    }

    private inline fun observe(read: () -> Unit) {
        try { read() } catch (e: Exception) { unavailable("${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun update(value: UltraRadioSnapshot) {
        if (snapshot == value) return
        snapshot = value
        val s = value.state
        trace("RADIO STATE connected=${value.connected} band=${s?.band} rawFrequency=${s?.rawFrequency} frequencyHz=${s?.frequencyHz} playing=${s?.playing} stations=${value.stations.size} title=${s?.title?.take(120)} station=${s?.stationName?.take(120)} rt=${s?.radioText?.take(256)}")
        onChanged()
    }

    fun updateRadioText(reading: VoyahRadioOem.RadioText?) = handler.post {
        if (!closed) {
            radioText = reading
            radioTextAt = SystemClock.elapsedRealtime()
            controller?.let { remote -> observe { publish(remote.metadata, remote.playbackState) } }
        }
    }

    private fun publish(metadata: MediaMetadata?, playback: PlaybackState?) {
        val values = linkedMapOf<String, Any?>()
        for (key in listOf(VoyahRadioOem.BAND, VoyahRadioOem.TITLE, VoyahRadioOem.STATION_NAME, VoyahRadioOem.FAVORITE))
            if (metadata?.containsKey(key) == true) values[key] = metadata.getString(key)
        if (metadata?.containsKey(VoyahRadioOem.FREQUENCY) == true)
            values[VoyahRadioOem.FREQUENCY] = metadata.getLong(VoyahRadioOem.FREQUENCY)
        val text = radioText.takeIf { SystemClock.elapsedRealtime() - radioTextAt in 0..6_000 }
        update(snapshot.copy(connected = controller != null,
            state = VoyahRadioOem.withRadioText(VoyahRadioOem.decode(values, playback?.state), text)))
        confirmWrite()
    }

    @Suppress("DEPRECATION")
    private fun refreshList(ready: ((Boolean) -> Unit)? = null) {
        if (ready != null) listWaiters.add(ready)
        if (listing) return
        val remote = controller
        if (remote == null || closed) { finishList(false); return }
        listing = true
        val epoch = ++listEpoch
        handler.postDelayed(listTimeout, 3_000)
        try {
            remote.sendCommand("MEDIA_ACTION_GET_CURRENT_RDS_PLAY_LIST", null, object : ResultReceiver(handler) {
                override fun onReceiveResult(code: Int, result: Bundle?) {
                    if (closed || controller !== remote || epoch != listEpoch || !listing) return
                    try {
                        if (code != 1 || result == null) { finishList(false); return }
                        result.classLoader = MediaSessionCompat.QueueItem::class.java.classLoader
                        val entries = result.getParcelableArrayList<MediaSessionCompat.QueueItem>("RESULT_DATA")
                        if (entries == null || entries.size > 512) { finishList(false); return }
                        val stations = entries.mapIndexedNotNull { index, entry ->
                            val description = entry.description
                            VoyahRadioOem.station(description.mediaId, description.title?.toString(),
                                description.subtitle?.toString(), index)
                        }.distinctBy { it.id }
                        update(snapshot.copy(stations = stations))
                        listedAt = SystemClock.elapsedRealtime()
                        trace("RADIO LIST stations=${stations.size} entries=${entries.size}")
                        finishList(true)
                    } catch (e: Exception) {
                        trace("RADIO LIST ERROR ${e.javaClass.simpleName}: ${e.message}")
                        finishList(false)
                    }
                }
            })
        } catch (e: Exception) { trace("RADIO LIST ERROR ${e.message}"); finishList(false) }
    }

    private fun finishList(ok: Boolean) {
        handler.removeCallbacks(listTimeout)
        listing = false
        val waiters = listWaiters.toList()
        listWaiters.clear()
        waiters.forEach { it(ok) }
    }

    private class Write(val request: UltraRadioRequest) {
        val done = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        @Volatile var result = UltraClimateWriteResult(false, "Radio request unavailable")
        var before: String? = null
        var dispatched = false
    }

    /** Blocks a CAF worker only; success requires the actual OEM metadata/playback readback. */
    fun write(request: UltraRadioRequest): UltraClimateWriteResult {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (closed || !supported) return UltraClimateWriteResult(false, "Radio closed or unsupported")
        val ticket = Write(request)
        handler.post {
            if (closed || ticket.cancelled.get() || controller == null || pending != null) {
                finishWrite(ticket, false, "Radio closed, disconnected or busy")
                return@post
            }
            pending = ticket
            ticket.before = snapshot.currentId
            handler.postDelayed(writeTimeout, 5_500)
            if (request.command == VoyahRadioOem.Command.TUNE) refreshList { ok ->
                if (pending === ticket && !ticket.cancelled.get()) dispatch(ticket, ok)
            } else dispatch(ticket, true)
        }
        try {
            if (ticket.done.await(7, TimeUnit.SECONDS)) return ticket.result
            ticket.cancelled.set(true)
            handler.post { if (pending === ticket) finishWrite(ticket, false, "Radio readback timed out") }
            return UltraClimateWriteResult(false, "Radio readback timed out; state unknown")
        } catch (_: InterruptedException) {
            ticket.cancelled.set(true)
            handler.post { if (pending === ticket) finishWrite(ticket, false, "Radio request interrupted") }
            Thread.currentThread().interrupt()
            return UltraClimateWriteResult(false, "Radio request interrupted; state unknown")
        }
    }

    private fun dispatch(ticket: Write, listFresh: Boolean) {
        val remote = controller
        if (closed || remote == null || ticket.cancelled.get()) { finishWrite(ticket, false, "Radio closed"); return }
        try {
            when (ticket.request.command) {
                VoyahRadioOem.Command.TUNE -> {
                    val wanted = ticket.request.stationId
                    if (wanted != null && wanted == snapshot.currentId) send(remote, VoyahRadioOem.Command.PLAY, null)
                    else {
                        val station = snapshot.stations.firstOrNull { it.id == wanted && it.oemIndex != null }
                        if (!listFresh || station == null) { finishWrite(ticket, false, "Station no longer in OEM list"); return }
                        send(remote, VoyahRadioOem.Command.TUNE, station.oemIndex)
                    }
                }
                else -> send(remote, ticket.request.command, null)
            }
            ticket.dispatched = true
            trace("RADIO DISPATCH request=${ticket.request} confirmed=false")
            publish(remote.metadata, remote.playbackState)
        } catch (e: Exception) { finishWrite(ticket, false, "${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun confirmWrite() {
        val ticket = pending ?: return
        if (!ticket.dispatched || ticket.cancelled.get() || !snapshot.connected) return
        val confirmed = when (ticket.request.command) {
            VoyahRadioOem.Command.PLAY -> snapshot.state?.playing == true
            VoyahRadioOem.Command.PAUSE -> snapshot.state?.playing == false
            VoyahRadioOem.Command.NEXT, VoyahRadioOem.Command.PREVIOUS ->
                ticket.before != null && snapshot.currentId != null && snapshot.currentId != ticket.before
            VoyahRadioOem.Command.TUNE -> snapshot.currentId == ticket.request.stationId && snapshot.state?.playing == true
        }
        if (confirmed) finishWrite(ticket, true, "OEM station=${snapshot.currentId} playing=${snapshot.state?.playing}")
    }

    private fun finishWrite(ticket: Write, confirmed: Boolean, reason: String) {
        if (pending === ticket) { pending = null; handler.removeCallbacks(writeTimeout) }
        ticket.result = UltraClimateWriteResult(confirmed, reason)
        ticket.done.countDown()
    }

    private fun clearController() {
        val old = controller
        val observer = callback
        controller = null
        callback = null
        radioText = null; radioTextAt = 0L
        handler.removeCallbacks(poll)
        ++listEpoch
        finishList(false)
        pending?.let { finishWrite(it, false, "Radio source disconnected") }
        update(UltraRadioSnapshot())
        if (old != null && observer != null) runCatching { old.unregisterCallback(observer) }
    }

    private fun unavailable(reason: String) {
        handler.removeCallbacks(connectTimeout)
        clearController()
        val old = browser
        browser = null
        runCatching { old?.disconnect() }
        trace("RADIO UNAVAILABLE $reason")
    }

    override fun close() {
        closed = true
        handler.post { unavailable("closed") }
    }
}
