package com.shilapi.xcertplay.airplay.mfi4

import com.shilapi.xcertplay.mfi.MfiAuthenticator

/**
 * Accessory side of MFi 4.0 mutual authentication (/auth-setup with x-apple-at 0x20), ported
 * from LIVI session.rs. The phone proves itself with its attestation certificate; we answer with
 * the same 3.0 identity and signer that already serve MFiSAP. Blocking: call off the main thread.
 */
class Mfi4Session(private val log: (String) -> Unit = {}) {
    private enum class Step { FRESH, SET_UP, AWAITING_PHONE_CERT, FINISHED, PROVEN, ENDED }
    private class Fault(message: String, val operation: Boolean = false) : Exception(message)

    private var step = Step.FRESH
    private var ourNonce = 0
    private var sessionId = 0
    private var ephemeral: Mfi4Crypto.Ephemeral? = null
    private var phonePoint = ByteArray(Mfi4Crypto.POINT)
    private var version = 0
    private var phoneAuthTypes = ByteArray(0)
    private var phoneCapabilities = ByteArray(0)
    private var keys: Mfi4Crypto.Keys? = null
    private var phoneHash = ByteArray(HASH)
    private var phoneSignature = ByteArray(SIGNATURE)
    private var ourPrefix = ByteArray(PREFIX)
    private var ourCert = ByteArray(0)
    private var ourHash = ByteArray(HASH)
    private var leafWaiting: Mfi4Certificate? = null
    private var tunnelOut = ByteArray(0)
    /** Set when the phone asked (x-apple-pairingtranscript) to bind pair-verify to this run. */
    @Volatile var bindsPairing = false

    val proven: Boolean get() = step == Step.PROVEN

    fun handle(body: ByteArray, signer: MfiAuthenticator, tunnel: (ByteArray) -> ByteArray): ByteArray {
        val asked = Mfi4Wire.peek(body)?.second ?: 0
        return try {
            answer(body, signer, tunnel).also { out ->
                log("mfi4 ${hex(asked)} -> ${hex(Mfi4Wire.peek(out)?.second ?: 0)} ($step)")
            }
        } catch (e: Exception) {
            log("mfi4 ${hex(asked)}: ${e.message}"); throw e
        }
    }

    private fun answer(body: ByteArray, signer: MfiAuthenticator, tunnel: (ByteArray) -> ByteArray): ByteArray {
        if (step == Step.ENDED) throw Mfi4Exception("the authentication on this connection is over")
        val (start, msgId) = Mfi4Wire.peek(body) ?: throw Mfi4Exception("not one whole message")
        if (msgId in setOf(Id.PHONE_SETUP_FAILED, Id.PHONE_FAILED, Id.PHONE_GAVE_UP)) {
            step = Step.ENDED
            throw Mfi4Exception("the phone gave up (${hex(msgId)})")
        }
        val msg = when {
            start == Mfi4Wire.CLEAR -> Mfi4Wire.decode(body)
            sessionId != 0 && start == sessionId -> open(msgId, body) ?: run {
                log("mfi4: ${hex(msgId)} does not open"); return clear(Id.RESET)
            }
            else -> { log("mfi4: ${hex(msgId)} under start ${hex(start)}"); return clear(Id.FAILED) }
        }
        val result: Mfi4Message = try {
            when {
                msgId == Id.PHONE_SETUP && step == Step.FRESH -> {
                    if (signer.protocolMajor() != OUR_VERSION) throw Mfi4Exception("mutual authentication needs a 3.0 identity")
                    return try { setup(msg) } catch (e: Mfi4Exception) { log("mfi4: setup refused: ${e.message}"); clear(Id.SETUP_FAILED) }
                }
                msgId == Id.PHONE_START && step == Step.SET_UP -> start(msg, signer)
                msgId == Id.PHONE_CERT && step == Step.AWAITING_PHONE_CERT -> phoneCert(msg, signer)
                msgId == Id.PHONE_WANTS_CERT && step == Step.FINISHED -> ourCertificate(msg)
                msgId == Id.PHONE_SUCCEEDED && step == Step.FINISHED -> {
                    log("mfi4: the phone and VoyahPlay proved each other")
                    step = Step.PROVEN
                    Mfi4Message(sessionId, Id.SUCCEEDED)
                }
                msgId == Id.PHONE_TUNNEL_DATA && step == Step.PROVEN -> return tunnelIn(msg, tunnel)
                msgId == Id.PHONE_TUNNEL_READ && step == Step.PROVEN -> {
                    val out = tunnelOut; tunnelOut = ByteArray(0)
                    val inner = Mfi4Message(Mfi4Wire.TUNNELED, Id.TUNNELED_FROM_US)
                        .with(0, byteArrayOf(PAIRING_DATA)).with(1, out).encode()
                    Mfi4Message(sessionId, Id.TUNNEL_DATA).with(0, inner)
                }
                else -> throw Fault("${hex(msgId)} while $step")
            }
        } catch (e: Fault) {
            log("mfi4: ${e.message}")
            return clear(if (e.operation) Id.GAVE_UP else Id.FAILED)
        } catch (e: Mfi4Exception) {
            if (msgId == Id.PHONE_SETUP) throw e
            log("mfi4: ${e.message}")
            return clear(Id.FAILED)
        }
        return seal(result)
    }

    private fun setup(msg: Mfi4Message): ByteArray {
        val lowest = msg.u8(0) ?: throw Mfi4Exception("no lowest version")
        val highest = msg.u8(1) ?: throw Mfi4Exception("no highest version")
        if (lowest < LOWEST_VERSION) throw Mfi4Exception("the phone starts at version $lowest")
        if (msg.u8(2) != PHONE_PROVES_AS_DEVICE) throw Mfi4Exception("certificate chain ${msg.u8(2)}")
        val authTypes = singleBytes(msg, 3)
        val capabilities = singleBytes(msg, 4)
        if (authTypes.isEmpty() || authTypes.size > MAX_AUTH_TYPES || capabilities.size > MAX_CAPABILITIES)
            throw Mfi4Exception("${authTypes.size} kinds, ${capabilities.size} capabilities")
        if (WE_RESPOND !in authTypes) throw Mfi4Exception("the phone offers ${authTypes.toList()}, we only respond")
        version = minOf(highest, OUR_VERSION)
        phoneAuthTypes = authTypes
        phoneCapabilities = capabilities
        ourNonce = generateSequence { Mfi4Wire.u16(Mfi4Crypto.randomBytes(2), 0) }.first { it != 0 && it != 0xffff }
        val generated = Mfi4Crypto.Ephemeral.generate()
        ephemeral = generated
        step = Step.SET_UP
        return Mfi4Message(Mfi4Wire.CLEAR, Id.SETUP).with(0, byteArrayOf(version.toByte()))
            .with(1, byteArrayOf(WE_RESPOND)).with(2, byteArrayOf(OUR_CHAIN_IS_ACCESSORY))
            .with(3, Mfi4Wire.be16(ourNonce)).with(4, generated.public).encode()
    }

    private fun start(msg: Mfi4Message, signer: MfiAuthenticator): Mfi4Message {
        val nonce = msg.u16(0) ?: throw Fault("AuthStart: no nonce")
        val point = msg.exact(1, Mfi4Crypto.POINT) ?: throw Fault("AuthStart: no 33 byte key")
        val sealed = msg.exact(2, HASH + SIGNATURE + Mfi4Crypto.TAG) ?: throw Fault("AuthStart: seal")
        if (nonce == 0 || nonce == 0xffff) throw Fault("AuthStart: a reserved nonce")
        sessionId = nonce xor ourNonce
        if (sessionId == 0) throw Fault("AuthStart: both nonces are the same")
        val ours = ephemeral ?: throw Fault("AuthStart before setup")
        val shared = try { ours.shared(point) } catch (e: Mfi4Exception) { throw Fault("AuthStart: ${e.message}") }
        val negotiation = byteArrayOf(OUR_VERSION.toByte()) + phoneAuthTypes + phoneCapabilities +
            byteArrayOf(version.toByte(), WE_RESPOND)
        val context = byteArrayOf(Mfi4Crypto.SIGMA) + ours.public + point + negotiation
        val derived = Mfi4Crypto.derive(shared, context)
        val opened = derived.start.open(ByteArray(0), sealed) ?: throw Fault("AuthStart: the seal does not open")
        phoneHash = opened.copyOfRange(0, HASH)
        phoneSignature = opened.copyOfRange(HASH, HASH + SIGNATURE)
        phonePoint = point
        keys = derived
        ourPrefix = Mfi4Crypto.randomBytes(PREFIX)
        ourCert = try { signer.readCertificate() } catch (e: Exception) { throw Fault("certificate: ${e.message}", operation = true) }
        ourHash = Mfi4Crypto.sha256(ourPrefix, ourCert)
        step = Step.AWAITING_PHONE_CERT
        return Mfi4Message(sessionId, Id.WANT_CERT).with(0, phoneHash)
    }

    private fun phoneCert(msg: Mfi4Message, signer: MfiAuthenticator): Mfi4Message {
        val kind = msg.u8(0) ?: throw Fault("phone certificate: no kind")
        val packed = msg.param(1) ?: throw Fault("phone certificate: none")
        val ids = msg.ids().sorted()
        val unpacked = try { Mfi4Certificate.unpack(packed) } catch (e: Mfi4Exception) { throw Fault("phone certificate: ${e.message}") }
        if (kind == PHONE_LEAF) {
            if (ids != listOf(0, 1, 2)) throw Fault("phone certificate: leaf parameters $ids")
            if (!Mfi4Crypto.sha256(msg.param(2)!!, unpacked.der).contentEquals(phoneHash))
                throw Fault("phone certificate: it is not the one the phone signed for")
            return finish(unpacked, signer)
        }
        if (kind !in PHONE_INTERMEDIATE || ids != listOf(0, 1)) throw Fault("phone certificate: kind $kind with $ids")
        // Without Apple's accessory root no intermediate is requested, so none can be waited for.
        val leaf = leafWaiting ?: throw Fault("phone certificate: no leaf before")
        leafWaiting = null
        return finish(leaf, signer)
    }

    private fun finish(leaf: Mfi4Certificate, signer: MfiAuthenticator): Mfi4Message {
        if (leaf.leaf && leaf.property?.last() != Mfi4Certificate.HELD_BY_APPLE_DEVICE)
            throw Fault("phone leaf: a leaf not held by an Apple device")
        val derived = keys ?: throw Fault("finish before start")
        val ours = ephemeral!!.public
        val phoneMac = Mfi4Crypto.cmac(derived.phoneMac, phoneHash)
        if (!leaf.signed(Mfi4Crypto.signedData(ours, phonePoint, phoneMac), phoneSignature))
            throw Fault("the phone's signature does not verify")
        val ourMac = Mfi4Crypto.cmac(derived.ourMac, ourHash)
        val digest = Mfi4Crypto.sha256(Mfi4Crypto.signedData(ours, phonePoint, ourMac))
        val signature = try { signer.signChallenge(digest) } catch (e: Exception) { throw Fault("sign: ${e.message}", operation = true) }
        if (signature.size != SIGNATURE) throw Fault("a ${signature.size} byte signature", operation = true)
        step = Step.FINISHED
        return Mfi4Message(sessionId, Id.FINISH).with(0, ourHash).with(1, signature)
    }

    private fun ourCertificate(msg: Mfi4Message): Mfi4Message {
        val hash = msg.param(0); val keyId = msg.param(1)
        return when {
            hash != null && keyId == null && hash.contentEquals(ourHash) -> Mfi4Message(sessionId, Id.CERT)
                .with(0, byteArrayOf(OUR_CERT_IS_3_0_LEAF)).with(1, ourCert).with(2, ourPrefix)
            hash != null && keyId == null -> throw Fault("the phone asks for another hash", operation = true)
            hash == null && keyId != null -> throw Fault("the phone asks for an intermediate", operation = true)
            else -> throw Fault("certificate request without hash or key id")
        }
    }

    private fun tunnelIn(msg: Mfi4Message, tunnel: (ByteArray) -> ByteArray): ByteArray = try {
        if (!bindsPairing) throw Mfi4Exception("the phone did not say it binds the pairing")
        val outer = msg.params.firstOrNull()?.second ?: throw Mfi4Exception("an empty tunnel message")
        val inner = Mfi4Wire.decode(outer)
        if (inner.start != Mfi4Wire.TUNNELED || inner.id !in setOf(Id.TUNNELED_FROM_PHONE, Id.TUNNELED_FROM_US))
            throw Mfi4Exception("tunneled ${hex(inner.id)} under ${hex(inner.start)}")
        tunnelOut = tunnel(inner.param(1) ?: throw Mfi4Exception("tunneled message without data"))
        seal(Mfi4Message(sessionId, Id.TUNNEL_TAKEN))
    } catch (e: Exception) {
        log("mfi4: tunnel: ${e.message}")
        Mfi4Message(sessionId, Id.TUNNEL_FAILED).encode()
    }

    private fun channel(msgId: Int, ours: Boolean): Mfi4Crypto.Channel? {
        val k = keys ?: return null
        return when {
            msgId == Id.WANT_CERT || msgId == Id.PHONE_CERT -> k.phoneCert
            msgId == Id.CERT || msgId == Id.PHONE_WANTS_CERT -> k.ourCert
            msgId == Id.FINISH -> k.finish
            ours -> k.ours
            else -> k.fromPhone
        }
    }

    private fun open(msgId: Int, body: ByteArray): Mfi4Message? {
        val ch = channel(msgId, false) ?: return null
        val plain = ch.open(body.copyOf(Mfi4Wire.HEADER), body.copyOfRange(Mfi4Wire.HEADER, body.size)) ?: return null
        val params = try { Mfi4Wire.decodeParams(plain) } catch (_: Mfi4Exception) { return null }
        val (start, id) = Mfi4Wire.peek(body) ?: return null
        return Mfi4Message(start, id, params)
    }

    private fun seal(msg: Mfi4Message): ByteArray {
        val params = msg.encodeParams()
        val header = msg.header(params.size + Mfi4Crypto.TAG)
        return header + channel(msg.id, true)!!.seal(header, params)
    }

    private fun clear(msgId: Int) = Mfi4Message(Mfi4Wire.CLEAR, msgId).encode()
    private fun singleBytes(msg: Mfi4Message, param: Int): ByteArray = msg.all(param).map {
        if (it.size != 1) throw Mfi4Exception("parameter $param is ${it.size} bytes"); it[0]
    }.toByteArray()
    private fun hex(v: Int) = "0x%04x".format(v)

    internal object Id {
        const val PHONE_SETUP = 0x5100; const val PHONE_SETUP_FAILED = 0x5102; const val PHONE_START = 0x5111
        const val PHONE_WANTS_CERT = 0x5112; const val PHONE_CERT = 0x5113; const val PHONE_TUNNEL_READ = 0x5163
        const val PHONE_TUNNEL_DATA = 0x5164; const val PHONE_GAVE_UP = 0x51FA; const val PHONE_FAILED = 0x51FE
        const val PHONE_SUCCEEDED = 0x51FF
        const val SETUP = 0x5201; const val SETUP_FAILED = 0x5202; const val WANT_CERT = 0x5212; const val CERT = 0x5213
        const val FINISH = 0x5214; const val TUNNEL_DATA = 0x5264; const val TUNNEL_FAILED = 0x5265
        const val TUNNEL_TAKEN = 0x5266; const val GAVE_UP = 0x52FA; const val RESET = 0x52FD; const val FAILED = 0x52FE
        const val SUCCEEDED = 0x52FF; const val TUNNELED_FROM_PHONE = 0x5500; const val TUNNELED_FROM_US = 0x5501
    }

    companion object {
        const val AUTH_TYPE = 0x20
        private const val LOWEST_VERSION = 2
        private const val OUR_VERSION = 3
        private const val PHONE_PROVES_AS_DEVICE = 1
        internal const val WE_RESPOND: Byte = 2
        private const val OUR_CHAIN_IS_ACCESSORY: Byte = 0
        private const val OUR_CERT_IS_3_0_LEAF: Byte = 0
        internal val PHONE_INTERMEDIATE = setOf(4, 5)
        internal const val PHONE_LEAF = 6
        private const val MAX_AUTH_TYPES = 4
        private const val MAX_CAPABILITIES = 3
        internal const val HASH = 32
        internal const val SIGNATURE = 64
        internal const val PREFIX = 16
        private const val PAIRING_DATA: Byte = 1
    }
}
