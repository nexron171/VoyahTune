package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.uisync.*
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec as AP
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface UltraClimateSource : Closeable {
    val supportedControls: Set<UltraClimateControl> get() = emptySet()
    val supportedHvac: Set<UltraHvacControl> get() = emptySet()
    fun start(onSample: (UltraClimateSample?) -> Unit)
    fun write(control: UltraClimateControl, level: Int): UltraClimateWriteResult = UltraClimateWriteResult(false, "Unavailable")
    fun writeHvac(control: UltraHvacControl, value: Any): UltraClimateWriteResult = UltraClimateWriteResult(false, "Unavailable")
    /** True when the phone's volume slider may set the Android volume. */
    val supportsVolume: Boolean get() = false
    /** Native Android stream indices; one CAF step must be one actual head-unit step. */
    val volumeRange: IntRange get() = 0..100
    /** Sets a level in [volumeRange] and/or mute; confirmed only by a matching read-back. */
    fun writeVolume(level: Int?, mute: Boolean?): UltraClimateWriteResult = UltraClimateWriteResult(false, "Unavailable")
    val supportsRadio: Boolean get() = false
    fun writeRadio(request: UltraRadioRequest): UltraClimateWriteResult = UltraClimateWriteResult(false, "Unavailable")
    val supportedVehicleControls: Set<UltraVehicleControl> get() = emptySet()
    fun writeVehicleControl(control: UltraVehicleControl, value: Int): UltraClimateWriteResult = UltraClimateWriteResult(false, "Unavailable")
}

/** VSP/CAF extension with VoyahTune climate and vehicle services. The existing iAP2, video, audio and pairing paths stay in charge. */
class VoyahUltraProtocol(
    private val sourceFactory: () -> UltraClimateSource,
    private val clock: () -> Long,
    private val trace: (String) -> Unit,
    /** Debug probe: accept fileTransfer and only record what the iPhone sends on it. */
    private val assetProbe: Boolean = false,
    private val dump: (String, ByteArray) -> Unit = { _, _ -> },
    /** /info is answered at most this long after the first vehicle reading was requested. */
    private val infoWaitMillis: Long = 1_500,
    /** Diagnostic opt-in: a logical instrument plugin without an additional video stream. */
    private val panelProbe: Boolean = false,
    /** Names verified in the diagnostic catalog package; requires matching iAP2 theme announcement. */
    private val themeProbe: Boolean = false,
    private val vehicleDataText: UltraVehicleDataText = UltraVehicleDataText(),
    private val vehicleControlsText: UltraVehicleControlsText = UltraVehicleControlsText(),
) : AirPlayProtocolExtension, Closeable {
    @Volatile private var sample: UltraClimateSample? = null
    @Volatile private var negotiated = false
    @Volatile private var uiSyncNegotiated = false
    @Volatile private var vehicleId: String? = null
    @Volatile private var probeFeatures: Set<String> = emptySet()
    @Volatile private var airPlaySession: AirPlaySession? = null
    private val radioAudio = VoyahRadioAudioSync(
        { airPlaySession?.takeAudioForRadio() == true }, { airPlaySession?.releaseRadioAudio() }, ::log)
    private val dumpedBytes = AtomicLong()
    private val infoCaptured = AtomicBoolean(false)
    private val firstSample = java.util.concurrent.CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    private val sourceStarted = AtomicBoolean(false)
    private val channels = ConcurrentHashMap<Long, Link>()
    // Keep closed extension stream identities: TEARDOWN may arrive after EOF, including UI Sync.
    private val extensionConnections = ConcurrentHashMap.newKeySet<String>()
    private val nextId = AtomicLong(10_000)
    private val source by lazy(sourceFactory)

    override fun forSession(): AirPlayProtocolExtension = VoyahUltraProtocol(sourceFactory, clock, trace, assetProbe, dump, infoWaitMillis, panelProbe, themeProbe, vehicleDataText, vehicleControlsText)

    override fun displayIdentifier(streamType: Int): String? = if (!themeProbe) null else when (streamType) {
        110 -> "Center_Display"
        111 -> "Cluster_Display"
        else -> null
    }

    override fun decorateInfo(info: MutableMap<String, Any?>) {
        // The phone reads /info right after SETUP; describe the climate with real values, as working
        // receivers do, instead of an empty description sent before the first OEM read returns.
        val waited = awaitFirstSample()
        val climate = VehicleStateProtocol.descriptor()
        val vehicle = linkedMapOf<String, Any?>("name" to VoyahClimateAccessory.PLUGIN_NAME,
            "pluginID" to VoyahClimateAccessory.PLUGIN_ID).apply { putAll(newAccessory().config()) }
        info["vehicleStateProtocolInfo"] = linkedMapOf(
            "protocolVersion" to "1.0", "pluginCount" to 2,
            "pluginConfigs" to ((climate["pluginConfigs"] as List<*>) + vehicle),
            "pluginMapping" to ((climate["pluginMapping"] as Map<String, Any?>) +
                (VoyahClimateAccessory.PLUGIN_NAME to VoyahClimateAccessory.PLUGIN_ID)),
        )
        // UI Sync versions and identity travel in reset envelopes. Declare its info sidecar even
        // before SETUP, as /info may be requested before feature negotiation.
        info["uiSyncInfo"] = emptyMap<String, Any?>()
        vehicleId = info["deviceID"] as? String
        info["displayPanels"] = VoyahUltraDisplayPanels.build((info["displays"] as? List<*>).orEmpty(), panelProbe || themeProbe, themeProbe)
        log("INFO PANEL_PROBE=$panelProbe")
        log("INFO VSP=1 UISYNC_ADVERTISED=1 UISYNC_NEGOTIATED=${if (uiSyncNegotiated) 1 else 0} RADIO_CAF=${source.supportsRadio} plugin=0 WIRE_REV=$WIRE_REV hvac=${source.supportedHvac.joinToString()} comfort=${source.supportedControls.joinToString()} panels=${(info["displayPanels"] as List<*>).size} sample=${sample?.driverTargetCelsius} waitedMs=$waited")
        log("INFO PANELS " + ProtocolTraceFormatter.pretty(info["displayPanels"]))
        log("INFO UISYNC_INFO " + ProtocolTraceFormatter.pretty(info["uiSyncInfo"]))
        // Preserve the actual plist the phone first sees, including the complete CAF description.
        // capture.txt limits individual lines to 3000 chars, so its pretty/hex previews cannot do this.
        if (infoCaptured.compareAndSet(false, true)) runCatching {
            val bytes = BplistCodec.encode(info)
            if (dumpedBytes.addAndGet(bytes.size.toLong()) <= MAX_DUMP_BYTES) {
                dump("receiver-info", bytes)
                log("INFO CAPTURE name=receiver-info bytes=${bytes.size}")
            } else log("INFO CAPTURE skipped: session dump limit")
        }.onFailure { log("INFO CAPTURE ERROR ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** Waits (bounded) for the first non-empty vehicle reading; returns the milliseconds spent. */
    private fun awaitFirstSample(): Long {
        if (hasVehicleReading(sample) || closed.get()) return 0
        if (negotiated) beginSource()
        if (!sourceStarted.get()) return 0
        val started = System.nanoTime()
        runCatching { firstSample.await(infoWaitMillis, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun hasVehicleReading(value: UltraClimateSample?) = value != null &&
        (value.driverTargetCelsius.isFinite() || value.sampledAtMillis > 0 || value.hvacSampledAtMillis > 0 || value.vehicleSampledAtMillis > 0)

    /** MFiSAP sends 33 bytes without x-apple-at; another type (0x20 is MFi4) shows what the phone attempts. */
    override fun onAuthSetup(headers: Map<String, String>, body: ByteArray, status: Int) {
        val type = headers.entries.firstOrNull { it.key.equals("x-apple-at", ignoreCase = true) }?.value ?: "none"
        log("AUTH SETUP x-apple-at=$type bytes=${body.size} head=${body.hexHead(24)} answered=$status " +
            "headers=${headers.keys.sorted().joinToString(",")}")
    }

    override fun enabledFeatures(proposed: List<*>?): List<String> {
        val offered = proposed.orEmpty().filterIsInstance<String>()
        log("FEATURES proposed=" + offered.joinToString(",").take(1500))
        val accepted = mutableListOf<String>()
        if (FEATURE in offered) accepted += FEATURE
        if (UI_SYNC_FEATURE in offered) accepted += UI_SYNC_FEATURE
        if (assetProbe) {
            val probe = PROBE_FEATURES.filter { it in offered }
            accepted += probe
            log("ASSET PROBE accepting=${probe.joinToString(",").ifEmpty { "none offered" }}")
        }
        return accepted
    }

    override fun onInfoRequest(body: ByteArray) {
        log("INFO REQUEST " + if (body.isEmpty()) "empty" else runCatching {
            ProtocolTraceFormatter.pretty(BplistCodec.decode(body))
        }.getOrElse { "decode error: ${it.message}" })
    }

    override fun onFeaturesNegotiated(features: List<String>) {
        negotiated = FEATURE in features
        uiSyncNegotiated = UI_SYNC_FEATURE in features
        probeFeatures = if (assetProbe) PROBE_FEATURES.filter { it in features }.toSet() else emptySet()
        log("NEGOTIATED VSP=${if (negotiated) 1 else 0} UISYNC=${if (uiSyncNegotiated) 1 else 0}; enabled=${features.joinToString(",")} WIRE_REV=$WIRE_REV ASSET_PROBE=${probeFeatures.joinToString(",").ifEmpty { "off" }}")
        if (negotiated) beginSource()
    }

    override fun openDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        val uuid = stream["clientTypeUUID"] as? String ?: run {
            log("RCS REJECT reason=missing clientTypeUUID stream=${ProtocolTraceFormatter.pretty(stream)}")
            return null
        }
        val client = RcsClientTypes.findByUuid(uuid)
        log("RCS SETUP client=${client?.name ?: "unknown"} stream=${ProtocolTraceFormatter.pretty(stream)}")
        if (assetProbe && (client == null || client in PROBE_CLIENTS || client == RcsClientTypes.CAR_PLAY_LOGGING_DATA))
            log("ASSET PROBE SETUP stream client=${client?.name ?: "unknown"} " + ProtocolTraceFormatter.pretty(stream).take(1500))
        if (client == null) { log("RCS DELEGATE clientTypeUUID=$uuid reason=unknown to Ultra"); return null }
        if (client == RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL) {
            if (!uiSyncNegotiated || closed.get()) {
                log("RCS REJECT client=${client.name} reason=${if (closed.get()) "session closed" else "uiSync not negotiated"}")
                return null
            }
            return withKeys(session, stream) { read, write, address -> openUiSync(read, write, address, stream) }
        }
        if (client in PROBE_CLIENTS) {
            if (client.feature?.wireName !in probeFeatures || closed.get()) {
                log("RCS REJECT client=${client.name} reason=probe inactive or feature not negotiated")
                return null
            }
            return withKeys(session, stream) { read, write, address -> openProbe(client, read, write, address, stream) }
        }
        if (client != RcsClientTypes.CAR_PLAY_PROTOCOL_DATA && client != RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2) {
            log("RCS DELEGATE client=${client.name} reason=not an Ultra stream")
            return null
        }
        if (!negotiated || closed.get()) { log("RCS rejected: VSP not negotiated or session closed"); return null }
        return withKeys(session, stream) { read, write, address -> openChannel(client, read, write, address, stream) }
    }

    private fun withKeys(session: AirPlaySession, stream: Map<String, Any?>,
        open: (ByteArray, ByteArray, InetAddress) -> Map<String, Any?>): Map<String, Any?>? {
        val client = (stream["clientTypeUUID"] as? String)?.let(RcsClientTypes::findByUuid)?.name ?: "unknown"
        val shared = session.sharedSecret ?: run {
            log("RCS REJECT client=$client reason=missing AirPlay shared secret")
            return null
        }
        val seed = unsignedPlistDecimal(stream["seed"]) ?: run {
            log("RCS REJECT client=$client reason=missing or invalid seed")
            return null
        }
        fun key(label: String) = AirPlayCrypto.hkdfSha512(shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII), label.toByteArray(Charsets.US_ASCII), 32)
        return open(key("DataStream-Output-Encryption-Key"), key("DataStream-Input-Encryption-Key"),
            session.localAddress ?: InetAddress.getByName("::"))
    }

    internal fun openProbe(client: com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType,
        readKey: ByteArray, writeKey: ByteArray, address: InetAddress, stream: Map<String, Any?>): Map<String, Any?> {
        check(assetProbe && client.feature?.wireName in probeFeatures && !closed.get()) { "Asset probe is not active" }
        return register(client, RcsDataStream.open(client, readKey, writeKey, address, ::log), stream) { data, id -> Probe(data, id) }
    }

    internal fun openChannel(client: com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType,
        readKey: ByteArray, writeKey: ByteArray, address: InetAddress, stream: Map<String, Any?>): Map<String, Any?> {
        check(negotiated && !closed.get()) { "Ultra VSP is not active" }
        require(client == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA || client == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2)
        return register(client, RcsDataStream.open(client, readKey, writeKey, address, ::log), stream) { data, id -> Channel(data, id) }
    }

    internal fun openUiSync(readKey: ByteArray, writeKey: ByteArray, address: InetAddress,
        stream: Map<String, Any?>): Map<String, Any?> {
        check(uiSyncNegotiated && !closed.get()) { "Ultra UI Sync is not active" }
        val client = RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL
        return register(client, RcsDataStream.open(client, readKey, writeKey, address, ::log), stream) { data, id -> UiSync(data, id) }
    }

    private fun register(client: com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType, data: RcsDataStream,
        stream: Map<String, Any?>, create: (RcsDataStream, String?) -> Link): Map<String, Any?> {
        channels.entries.filter { it.value.data.clientType == client }.forEach { (id, previous) ->
            channels.remove(id, previous)
            previous.close()
        }
        val channel = create(data, unsignedPlistDecimal(stream["streamConnectionID"]))
        channel.connectionId?.let { extensionConnections.add(it) }
        val id = nextId.getAndIncrement()
        val port = try { data.listen() } catch (error: Throwable) {
            log("RCS LISTEN ERROR client=${client.name} ${error.javaClass.simpleName}: ${error.message}")
            channel.close(); throw error
        }
        channels[id] = channel
        log("RCS LISTEN client=${client.name} streamID=$id port=$port")
        return linkedMapOf<String, Any?>("type" to 130, "streamID" to id, "dataPort" to port).apply {
            stream["streamConnectionID"]?.let { put("streamConnectionID", unsignedPlistInteger(it)) }
        }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        if (!closed.get()) { airPlaySession = session; radioAudio.attach() }
        attachPending()
    }

    internal fun attachPending() {
        channels.values.forEach { channel ->
            if (channel.attached.compareAndSet(false, true)) channel.data.attach(channel)
        }
    }

    override fun mediaTeardownTypes(body: Map<String, Any?>?, original: List<Int>?): List<Int>? {
        val entries = body?.get("streams") as? List<*> ?: return original
        if (entries.isEmpty()) return original
        val media = entries.filterNot { value ->
            val entry = value as? Map<*, *> ?: return@filterNot false
            if ((entry["type"] as? Number)?.toInt() != 130) return@filterNot false
            val client = (entry["clientTypeUUID"] as? String)?.let(RcsClientTypes::findByUuid)
            val connection = unsignedPlistDecimal(entry["streamConnectionID"])
            client == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA || client == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2 ||
                client == RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL ||
                (assetProbe && client in PROBE_CLIENTS) || (connection != null && connection in extensionConnections)
        }
        if (media.size == entries.size) return original
        return if (media.isEmpty()) emptyList() else teardownStreamTypes(mapOf("streams" to media)).orEmpty()
    }

    override fun onTeardown(session: AirPlaySession, body: Map<String, Any?>?) {
        teardownChannels(body)
    }

    internal fun teardownChannels(body: Map<String, Any?>?) {
        val entries = body?.get("streams") as? List<*>
        if (entries.isNullOrEmpty()) { closeChannels(); return }
        entries.filterIsInstance<Map<*, *>>().forEach { entry ->
            if ((entry["type"] as? Number)?.toInt() != 130) return@forEach
            val streamId = (entry["streamID"] as? Number)?.toLong()
            val connectionId = unsignedPlistDecimal(entry["streamConnectionID"])
            val uuid = entry["clientTypeUUID"] as? String
            channels.entries.filter { (id, channel) ->
                when {
                    streamId != null -> streamId == id
                    connectionId != null -> connectionId == channel.connectionId
                    uuid != null -> RcsClientTypes.findByUuid(uuid) == channel.data.clientType
                    else -> true
                }
            }.forEach { (id, channel) -> channels.remove(id, channel); channel.close() }
        }
    }

    override fun onSessionClosed(session: AirPlaySession) {
        close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        radioAudio.close()
        if (climateProtocolDelegate.isInitialized()) climateProtocol.close()
        airPlaySession = null
        closeChannels()
        source.close()
        extensionConnections.clear()
        log("SESSION CLOSED")
    }

    private fun closeChannels() {
        // Kotlin toList() has a size == 1 fast path that calls next() without hasNext(). The peer
        // can remove that last entry on EOF between those calls. CHM's iterator tolerates removal.
        channels.entries.forEach { (id, channel) -> channels.remove(id, channel); channel.close() }
    }

    private fun beginSource() {
        if (closed.get() || !sourceStarted.compareAndSet(false, true)) return
        log("VEHICLE SOURCE START WIRE_REV=$WIRE_REV")
        source.start { value ->
            if (closed.get()) return@start
            val changed = sample?.driverTargetCelsius != value?.driverTargetCelsius || sample?.hvacValues != value?.hvacValues ||
                sample?.comfortLevels != value?.comfortLevels || sample?.readings != value?.readings || sample?.vehicle != value?.vehicle ||
                sample?.radio != value?.radio || sample?.vehicleControlValues != value?.vehicleControlValues
            sample = value
            radioAudio.observe(value?.radio)
            // A radio-only callback must not end /info's initial wait for actual vehicle readings.
            if (hasVehicleReading(value)) firstSample.countDown()
            if (changed) log("VEHICLE driverTargetC=${value?.driverTargetCelsius ?: "unavailable"} hvac=${value?.hvacValues} readings=${value?.readings} comfort=${value?.comfortLevels} vehicle=${value?.vehicle} controls=${value?.vehicleControlValues}")
            channels.values.forEach { (it as? Channel)?.publish() }
        }
    }

    /** What the phone wrote for route sharing in this session, seen by both data streams. */
    private val phoneValues = UltraPhoneValues()
    private val writeTransactions = UltraWriteTransactions()
    private val climateProtocolDelegate = lazy {
        VehicleStateProtocol { highPriority, body ->
            val client = if (highPriority) RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2
                else RcsClientTypes.CAR_PLAY_PROTOCOL_DATA
            (channels.values.firstOrNull { it.data.clientType == client } as? Channel)?.sendClimate(body)
        }
    }
    private val climateProtocol by climateProtocolDelegate

    private fun newAccessory() = VoyahClimateAccessory({ sample }, clock, source.supportedControls,
        { control, level ->
            if (closed.get() || !negotiated) UltraClimateWriteResult(false, "Session closed") else {
                log("OEM WRITE request comfort=$control value=$level")
                source.write(control, level).also { log("OEM WRITE result confirmed=${it.confirmed} ${it.message}") }
            }
        }, source.supportedHvac, { control, value ->
            if (closed.get() || !negotiated) UltraClimateWriteResult(false, "Session closed") else {
                log("OEM WRITE request hvac=$control value=$value")
                source.writeHvac(control, value).also { log("OEM WRITE result confirmed=${it.confirmed} ${it.message}") }
            }
        }, volumeWriter = if (!source.supportsVolume) null else { level: Int?, mute: Boolean? ->
            if (closed.get() || !negotiated) UltraClimateWriteResult(false, "Session closed") else {
                log("VOLUME WRITE request level=$level mute=$mute")
                source.writeVolume(level, mute).also { log("VOLUME WRITE result confirmed=${it.confirmed} ${it.message}") }
            }
        }, volumeRange = source.volumeRange, phoneValues = phoneValues, radioSupported = source.supportsRadio,
        radioWriter = if (!source.supportsRadio) null else { request ->
            radioAudio.control(request.command != VoyahRadioOem.Command.PAUSE) {
                if (closed.get() || !negotiated) UltraClimateWriteResult(false, "Session closed") else {
                    log("RADIO CONTROL request=$request")
                    val needsAudio = request.command != VoyahRadioOem.Command.PAUSE
                    val granted = !needsAudio || airPlaySession?.takeAudioForRadio() == true
                    if (needsAudio) log("RADIO AUDIO mainAudio accessoryGranted=$granted")
                    if (!granted || closed.get()) UltraClimateWriteResult(false, "iPhone mainAudio not granted; radio not started")
                    else runCatching { source.writeRadio(request) }.getOrElse {
                        UltraClimateWriteResult(false, "${it.javaClass.simpleName}: ${it.message}")
                    }.also {
                        if (needsAudio && !it.confirmed) airPlaySession?.releaseRadioAudio()
                        log("RADIO CONTROL result confirmed=${it.confirmed} ${it.message}")
                    }
                }
            }
        }, vehicleDataText = vehicleDataText, supportedVehicleControls = source.supportedVehicleControls,
        vehicleControlWriter = { control, value ->
            if (closed.get() || !negotiated) UltraClimateWriteResult(false, "Session closed") else {
                log("VEHICLE CONTROL request=$control value=$value")
                source.writeVehicleControl(control, value).also {
                    log("VEHICLE CONTROL result confirmed=${it.confirmed} ${it.message}")
                }
            }
        }, vehicleControlsText = vehicleControlsText, writeTransactions = writeTransactions)

    private fun log(text: String) { runCatching { trace(text.take(3000)) } }

    private abstract inner class Link(val data: RcsDataStream, val connectionId: String?) : RcsDataStreamHandler, Closeable {
        val attached = AtomicBoolean(false)
    }

    private inner class UiSync(data: RcsDataStream, connectionId: String?) : Link(data, connectionId) {
        private val stopped = AtomicBoolean(false)
        private val ui = CarPlayUiSyncSession(::log)
        override fun onStreamOpened(stream: RcsDataStream) {
            log("UI SYNC CONNECTED WIRE_REV=$WIRE_REV")
            log("UI SYNC waiting for phone RESET")
        }
        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            if (stopped.get() || message.kind == AP.KIND_RPLY) return
            try {
                require(message.body.size <= 262_144) { "UI Sync body exceeds limit" }
                require(message.messageType == AP.MESSAGE_TYPE_COMM || message.messageType == AP.MESSAGE_TYPE_CMND) {
                    "Expected UI Sync comm/cmnd package"
                }
                if (message.kind == AP.KIND_SYNC) data.send(message.reply())
                ui.receive(message.body).forEach(::send)
            } catch (error: Exception) {
                log("UI SYNC ERROR ${error.javaClass.simpleName}: ${error.message}")
                close()
            }
        }
        private fun send(body: ByteArray) {
            data.send(RcsMessage(AP.MESSAGE_TYPE_CMND, body, AP.KIND_ASYN))
        }
        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            log("UI SYNC CLOSED reason=${cause?.message ?: "EOF"}")
            close()
        }
        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            channels.entries.removeIf { it.value === this }
            // Do not acquire UI Sync state under RcsDataStream's callback lock during TEARDOWN.
            data.close()
            ui.close()
        }
    }

    /** Records whatever the iPhone sends on fileTransfer; it only acknowledges sync frames. */
    private inner class Probe(data: RcsDataStream, connectionId: String?) : Link(data, connectionId) {
        private val stopped = AtomicBoolean(false)
        private var count = 0
        private var bytes = 0L
        private val name = data.clientType.name
        override fun onStreamOpened(stream: RcsDataStream) { log("ASSET PROBE CONNECTED client=$name") }
        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            val transport = com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
            count++; bytes += message.body.size
            if (message.kind == transport.KIND_SYNC) data.send(message.reply())
            log("ASSET PROBE RX client=$name n=$count kind=${fourCc(message.kind)} type=${fourCc(message.messageType)} " +
                "token=${message.token} bytes=${message.body.size} total=$bytes head=${message.body.hexHead(64)} decoded=${describe(message.body)}")
            val body = message.body
            if (body.isNotEmpty() && dumpedBytes.addAndGet(body.size.toLong()) <= MAX_DUMP_BYTES) runCatching { dump("$name-$count", body) }
        }
        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            log("ASSET PROBE CLOSED client=$name messages=$count bytes=$bytes reason=${cause?.message ?: "EOF"}")
            close()
        }
        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            channels.entries.removeIf { it.value === this }
            data.close()
        }
    }

    private fun fourCc(value: Int): String {
        val chars = (0 until 4).map { ((value ushr (24 - it * 8)) and 0xff).toChar() }
        return if (chars.all { it in ' '..'~' }) chars.joinToString("") else "0x" + Integer.toHexString(value)
    }
    private fun ByteArray.hexHead(limit: Int) = take(limit).joinToString("") { "%02x".format(it) } + if (size > limit) "…" else ""
    private fun describe(body: ByteArray): String {
        if (body.isEmpty()) return "empty"
        val value = runCatching {
            if (CafRcsCodec.encoding(body) == CafBodyEncoding.BPLIST_WRAPPED) BplistCodec.decode(body) else OpackCodec.decode(body)
        }.getOrElse { return "opaque (${it.javaClass.simpleName})" }
        return ProtocolTraceFormatter.pretty(value).take(1200)
    }

    private inner class Channel(data: RcsDataStream, connectionId: String?) : Link(data, connectionId) {
        private val stopped = AtomicBoolean(false)
        private val accessory = newAccessory()
        @Volatile var encoding = CafBodyEncoding.OPACK
        private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(64),
            { runnable -> Thread(runnable, "voyah-ultra-caf").apply { isDaemon = true } })

        override fun onStreamOpened(stream: RcsDataStream) {
            log("RCS CONNECTED client=${stream.clientType.name}")
            beginSource()
        }

        fun sendClimate(body: ByteArray) {
            val response = if (encoding == CafBodyEncoding.BPLIST_WRAPPED)
                BplistCodec.encode(mapOf("params" to mapOf("data" to body))) else body
            // Native replies and observations arrive on the UI thread.
            enqueue { data.send(RcsMessage(AP.MESSAGE_TYPE_CMND, response, AP.KIND_ASYN)) }
        }

        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            val receivedAt = System.nanoTime()
            if (message.body.size > 262_144) { log("RCS body exceeds pilot limit"); close(); return }
            val transport = com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
            if (message.kind == transport.KIND_RPLY) { log("CAF transport reply token=${message.token}"); return }
            if (message.kind == transport.KIND_SYNC) data.send(message.reply())
            enqueue {
                val startedAt = System.nanoTime()
                if (message.messageType != transport.MESSAGE_TYPE_COMM && message.messageType != transport.MESSAGE_TYPE_CMND)
                    throw CafProtocolException("Expected CAF comm/cmnd package")
                encoding = CafRcsCodec.encoding(message.body)
                val request = CarAccessoryMessages.reader(message.body)
                log("CAF transport kind=${message.kind} token=${message.token} encoding=$encoding")
                log("CAF RX plugin=${request.pluginId} command=${request.command.wireName} values=${ProtocolTraceFormatter.pretty(request.values())}")
                val decodedAt = System.nanoTime()
                if (request.pluginId == 0L) {
                    val body = if (encoding == CafBodyEncoding.OPACK) message.body else
                        CafRcsCodec.encode(CafRcsCodec.decode(message.body)).rcsBody
                    climateProtocol.receive(stream.clientType == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, body)
                    return@enqueue
                }
                val response = accessory.handle(request)
                val handledAt = System.nanoTime()
                if (request.command == CafCommand.CONFIG_REQUEST) {
                    val tree = (response?.envelope?.message?.values as? Map<*, *>)?.get("accessories") as? List<*>
                    log("CAF CONFIG WIRE_REV=$WIRE_REV accessories=${tree?.size ?: 0} RADIO_CAF=${source.supportsRadio} hvac=${source.supportedHvac} comfort=${source.supportedControls} driver=${sample?.driverTargetCelsius}")
                }
                response?.let(::send)
                // Echo on this subscribed channel now, before a later request can block its worker.
                // Other channels own their subscription state and publish on their own workers.
                if (request.command == CafCommand.WRITE_REQUEST || request.command == CafCommand.CONTROL_REQUEST) {
                    accessory.changed()?.let(::send)
                    channels.values.forEach { if (it !== this) (it as? Channel)?.publish() }
                }
                val finishedAt = System.nanoTime()
                log("CAF TIMING command=${request.command.wireName} transaction=${request.transactionId} " +
                    "queueMs=${elapsedMs(receivedAt, startedAt)} decodeMs=${elapsedMs(startedAt, decodedAt)} " +
                    "handleMs=${elapsedMs(decodedAt, handledAt)} responseMs=${elapsedMs(handledAt, finishedAt)} " +
                    "totalMs=${elapsedMs(receivedAt, finishedAt)}")
            }
        }

        fun publish() = enqueue { accessory.changed()?.let(::send) }
        private fun send(frame: CafRcsFrame) {
            val startedAt = System.nanoTime()
            // Factories already encode OPACK. Only the legacy plist carrier needs conversion.
            val wire = if (encoding == CafBodyEncoding.OPACK) frame else CafRcsCodec.encode(frame.envelope, encoding)
            val encodedAt = System.nanoTime()
            data.send(wire.asRcsMessage())
            val sentAt = System.nanoTime()
            val message = frame.envelope.message
            val values = if (message.command == CafCommand.CONFIG_RESPONSE) {
                val tree = (message.values as? Map<*, *>)?.get("accessories") as? List<*>
                "accessories(count=${tree?.size ?: 0})"
            } else ProtocolTraceFormatter.pretty(message.values)
            log("CAF TX command=${message.command.wireName} values=$values errors=${message.errors} " +
                "bytes=${wire.rcsBody.size} encodeMs=${elapsedMs(startedAt, encodedAt)} transportMs=${elapsedMs(encodedAt, sentAt)}")
        }
        private fun elapsedMs(start: Long, end: Long) = (end - start) / 1_000_000
        private fun enqueue(action: () -> Unit) {
            if (stopped.get()) return
            try { worker.execute { runCatching(action).onFailure { log("CAF ERROR ${it.javaClass.simpleName}: ${it.message}"); close() } } }
            catch (_: java.util.concurrent.RejectedExecutionException) { if (!stopped.get()) { log("CAF queue full"); close() } }
        }
        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            log("RCS CLOSED client=${stream.clientType.name} reason=${cause?.message ?: "EOF"}")
            close()
        }
        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            channels.entries.removeIf { it.value === this }
            worker.shutdownNow()
            data.close()
        }
    }

    companion object {
        const val FEATURE = "vehicleStateProtocol"; const val WIRE_REV = "opack-47-ultra-profile"
        const val UI_SYNC_FEATURE = "uiSync"
        val PROBE_FEATURES = listOf("fileTransfer")
        val PROBE_CLIENTS = setOf(RcsClientTypes.CAR_PLAY_UPDATE_DATA)
        const val MAX_DUMP_BYTES = 4L * 1024 * 1024
    }
}
