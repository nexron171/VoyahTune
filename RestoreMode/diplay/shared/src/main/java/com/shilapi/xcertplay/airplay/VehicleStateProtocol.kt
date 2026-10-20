// SPDX-License-Identifier: GPL-3.0-or-later
// Climate schema adapted from f-io/LIVI, Copyright 2026 Lasse Heitgres.
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One phone's VSP configuration, subscriptions and acknowledged Native commands. */
internal class VehicleStateProtocol(private val send: (Boolean, ByteArray) -> Unit) : Closeable {
    private val subscribed = LinkedHashSet<Long>()
    private val writes = LinkedHashMap<Long, PendingWrite>()
    private var lastValues = observed()

    @Volatile
    private var closed = false

    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "CarPlayClimateDeadline").apply { isDaemon = true }
    }
    private val observation = VehicleClimate.observe { synchronized(this) { update(it) } }

    private class PendingWrite(val request: ByteArray, val errors: MutableMap<Long, Int>) {
        var response: ByteArray? = null
        var remaining = 0
    }

    @Synchronized
    fun receive(highPriority: Boolean, bytes: ByteArray) {
        if (closed) return
        val outer = OpackCodec.decode(bytes) as? Map<*, *> ?: return
        if (outer["pluginID"] != 0L) return
        val data = outer["pluginData"] as? ByteArray ?: return
        val request = OpackCodec.decode(data) as? Map<*, *> ?: return
        val transaction = request["transactionID"] as? Long ?: return
        val command = request["command"] as? String ?: return
        val values = request["values"]
        when (command) {
            "configRequest" -> answer(
                highPriority,
                "configResponse",
                transaction,
                mapOf("accessories" to accessories()),
            )

            "registerRequest", "readRequest" -> {
                val identifiers = values as? List<*> ?: return
                require(identifiers.size <= 64) { "Too many VSP characteristics" }
                val current = observed()
                val available = LinkedHashMap<Long, Any?>()
                val errors = LinkedHashMap<Long, Int>()
                for (identifier in identifiers) {
                    val id = identifier as? Long ?: continue
                    if (command == "registerRequest" && characteristics.any { it.id == id }) {
                        subscribed.add(id)
                    }
                    if (current[id] != null) {
                        available[id] = current[id]
                    } else {
                        errors[id] = NO_VALUE
                    }
                }
                answer(highPriority, command.replace("Request", "Response"), transaction, available, errors)
            }

            "unregisterRequest" -> {
                val identifiers = (values as? List<*>)?.filterIsInstance<Long>() ?: subscribed.toList()
                subscribed.removeAll(identifiers.toSet())
                answer(highPriority, "unregisterResponse", transaction, errors = identifiers.associateWith { 0 })
            }

            "writeRequest" -> write(highPriority, transaction, data, values as? Map<*, *> ?: return)

            else -> send(
                highPriority,
                wrap(mapOf("command" to "generalError", "transactionID" to transaction, "error" to -6735)),
            )
        }
    }

    private fun write(highPriority: Boolean, transaction: Long, bytes: ByteArray, values: Map<*, *>) {
        require(values.size in 1..16) { "Invalid climate write size" }
        writes[transaction]?.let { previous ->
            require(previous.request.contentEquals(bytes)) { "Reused climate transaction" }
            previous.response?.let { send(highPriority, it) }
            return
        }
        if (writes.count { it.value.response == null } >= 16) {
            answer(
                highPriority,
                "writeResponse",
                transaction,
                errors = values.keys.filterIsInstance<Long>().associateWith { -6721 },
            )
            return
        }

        val errors = LinkedHashMap<Long, Int>()
        val accepted = LinkedHashMap<Long, Pair<Characteristic, Double>>()
        values.forEach { (key, value) ->
            val identifier = key as? Long ?: return@forEach
            val characteristic = characteristics.find { it.id == identifier }
            val normalized = characteristic?.normalize(value)
            if (normalized == null || characteristic.field == null) {
                errors[identifier] = -6705
            } else {
                accepted[identifier] = characteristic to normalized
            }
        }
        val pending = PendingWrite(bytes.copyOf(), errors).apply { remaining = accepted.size }
        writes[transaction] = pending
        while (writes.size > 64) {
            val completed = writes.entries.firstOrNull { it.value.response != null } ?: break
            writes.remove(completed.key)
        }

        fun finish() {
            if (closed || pending.response != null || pending.remaining != 0) return
            val response = wrap(
                mapOf(
                    "command" to "writeResponse",
                    "transactionID" to transaction,
                    "errors" to errors.toMap(),
                ),
            )
            pending.response = response
            send(highPriority, response)
            notifyValues(observed().filterKeys { it in subscribed && it in values.keys })
        }

        if (accepted.isEmpty()) {
            finish()
            return
        }
        val timeout = deadlines.schedule(
            {
                synchronized(this) {
                    if (pending.response == null && !closed) {
                        accepted.keys.filter { it !in errors }.forEach { errors[it] = -6722 }
                        pending.remaining = 0
                        finish()
                    }
                }
            },
            4,
            TimeUnit.SECONDS,
        )
        accepted.forEach { (identifier, target) ->
            VehicleClimate.write(target.first.field!!, target.second, { !closed }) { error ->
                synchronized(this) {
                    if (!closed && pending.response == null && identifier !in errors) {
                        errors[identifier] = error
                        pending.remaining--
                        if (pending.remaining == 0) timeout.cancel(false)
                        finish()
                    }
                }
            }
        }
    }

    private fun answer(
        highPriority: Boolean,
        command: String,
        transaction: Long,
        values: Any? = null,
        errors: Map<Long, Int> = emptyMap(),
    ) {
        val response = linkedMapOf<String, Any?>("command" to command, "transactionID" to transaction)
        if (values != null) response["values"] = values
        if (errors.isNotEmpty()) response["errors"] = errors
        send(highPriority, wrap(response))
    }

    private fun update(snapshot: Map<String, Double>) {
        if (closed) return
        val next = observed(snapshot)
        val changed = next.filter { (identifier, value) ->
            identifier in subscribed && value != lastValues[identifier]
        }
        lastValues = next
        notifyValues(changed)
    }

    private fun notifyValues(values: Map<Long, Any?>) {
        if (values.isNotEmpty()) {
            send(true, wrap(mapOf("command" to "updateNotify", "values" to values)))
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        observation.close()
        deadlines.shutdownNow()
        subscribed.clear()
        writes.clear()
    }

    private data class Characteristic(
        val id: Long,
        val type: Long,
        val format: Int,
        val field: String? = null,
        val fixed: Any? = null,
        val minimum: Double? = null,
        val maximum: Double? = null,
        val step: Double? = null,
    ) {
        fun normalize(value: Any?): Double? {
            if (fixed != null) return null
            if (format == 0) return (value as? Boolean)?.let { if (it) 1.0 else 0.0 }
            val number = (value as? Number)?.toDouble() ?: return null
            if (!number.isFinite() || number < minimum!! || number > maximum!!) return null
            val steps = (number - minimum) / step!!
            return number.takeIf { kotlin.math.abs(steps - Math.rint(steps)) < 1e-8 }
        }

        fun value(snapshot: Map<String, Double>): Any? {
            fixed?.let { return it }
            val raw = snapshot[field] ?: return null
            return when (format) {
                0 -> when (raw) {
                    0.0 -> false
                    1.0 -> true
                    else -> null
                }

                1, 7 -> raw.toLong().takeIf { normalize(raw) != null }
                else -> raw.takeIf { normalize(raw) != null }
            }
        }

        fun describe(snapshot: Map<String, Double>): Map<String, Any?> {
            val result = linkedMapOf<String, Any?>(
                "iid" to id,
                "type" to type,
                "format" to format,
                "mutable" to (fixed == null),
                "writable" to (fixed == null),
                "oemWritable" to true,
                "priority" to 1,
            )
            if (fixed != null) {
                result["maximumLength"] = 65535
            } else {
                result["supportsInvalid"] = true
            }
            if (minimum != null) {
                result["minimumValue"] = if (format == 9) minimum else minimum.toLong()
                result["maximumValue"] = if (format == 9) maximum else maximum!!.toLong()
                result["stepValue"] = if (format == 9) step else step!!.toLong()
            }
            if (format == 9) result["units"] = 1082
            value(snapshot)?.let { result["initialValue"] = it }
            return result
        }
    }

    companion object {
        const val FEATURE = "vehicleStateProtocol"
        const val NORMAL_UUID = "3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E"
        const val HIGH_UUID = "FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634"
        private const val NO_VALUE = -6727

        private fun flag(id: Long, type: Long, field: String) = Characteristic(id, type, 0, field)

        private fun layout(id: Long, name: String) = Characteristic(id, 0x36000065, 10, fixed = name)

        private fun temperature(id: Long, field: String) = Characteristic(
            id,
            0x31000017,
            9,
            field,
            minimum = 15.5,
            maximum = 30.5,
            step = 0.5,
        )

        private data class SeatControl(
            val id: Int,
            val fieldPrefix: String,
            val position: String,
            val ventilation: Boolean = false,
        ) {
            val type: Int get() = if (ventilation) 0x22000002 else 0x22000001

            fun characteristics(): List<Characteristic> = listOf(
                Characteristic(
                    id + 1L,
                    if (ventilation) 0x31000012 else 0x31000021,
                    if (ventilation) 1 else 7,
                    "${fieldPrefix}Level",
                    minimum = 0.0,
                    maximum = 3.0,
                    step = 1.0,
                ),
                flag(id + 2L, 0x30000002, "${fieldPrefix}On"),
                layout(id + 3L, position),
            )
        }

        private data class VentControl(val id: Int, val field: String, val direction: Long) {
            fun characteristics(): List<Characteristic> = listOf(
                flag(id + 1L, 0x30000002, field),
                flag(id + 2L, 0x3000005f, "auto"),
                Characteristic(id + 3L, 0x31000025, 13, fixed = listOf(direction)),
                layout(id + 4L, "seat_front"),
            )
        }

        private val vents = listOf(
            VentControl(548, "ventUpper", 2),
            VentControl(553, "ventLower", 4),
            VentControl(558, "ventMiddle", 16),
        )

        private val seats = listOf(
            SeatControl(1025, "driverSeatHeating", "seat_front_left"),
            SeatControl(1029, "passengerSeatHeating", "seat_front_right"),
            SeatControl(1033, "rearLeftSeatHeating", "seat_2ndRow_left"),
            SeatControl(1037, "rearRightSeatHeating", "seat_2ndRow_right"),
            SeatControl(1041, "driverSeatVentilation", "seat_front_left", ventilation = true),
            SeatControl(1045, "passengerSeatVentilation", "seat_front_right", ventilation = true),
        )

        private val characteristics = listOf(
            flag(514, 0x31000002, "power"),
            flag(515, 0x31000003, "compressor"),
            flag(519, 0x30000002, "power"),
            temperature(520, "driverTemperature"),
            layout(522, "seat_front_left"),
            flag(533, 0x30000002, "power"),
            temperature(534, "passengerTemperature"),
            layout(536, "seat_front_right"),
            flag(524, 0x30000002, "power"),
            Characteristic(525, 0x31000012, 1, "fan", minimum = 0.0, maximum = 7.0, step = 1.0),
            flag(526, 0x3000005f, "auto"),
            layout(531, "seat_front"),
            flag(528, 0x30000002, "recirculation"),
            flag(530, 0x30000002, "auto"),
            flag(538, 0x30000002, "sync"),
            layout(539, "seat_front"),
            Characteristic(
                564,
                0x31000021,
                7,
                "steeringWheelHeatingLevel",
                minimum = 0.0,
                maximum = 1.0,
                step = 1.0,
            ),
            Characteristic(770, 0x48000004, 10, fixed = "5Seater"),
        ) + vents.flatMap { it.characteristics() } + seats.flatMap { it.characteristics() }

        private fun observed(snapshot: Map<String, Double> = VehicleClimate.snapshot()): Map<Long, Any?> =
            characteristics.associate { it.id to it.value(snapshot) }

        private fun accessories(): List<Map<String, Any?>> {
            val snapshot = VehicleClimate.snapshot()
            fun service(id: Int, type: Int, vararg identifiers: Long): Map<String, Any?> = mapOf(
                "iid" to id,
                "type" to type,
                "characteristics" to identifiers.map { identifier ->
                    characteristics.first { it.id == identifier }.describe(snapshot)
                },
            )

            return listOf(
                mapOf(
                    "iid" to 512,
                    "type" to 0x01000001,
                    "version" to "1.0",
                    "services" to listOf(
                        service(513, 0x11000001, 514, 515),
                        service(518, 0x11000002, 519, 520, 522),
                        service(532, 0x11000002, 533, 534, 536),
                        service(523, 0x11000007, 524, 525, 526, 531),
                        service(527, 0x11000011, 528),
                        service(529, 0x11000008, 530),
                        service(537, 0x11000012, 538, 539),
                        service(563, 0x11000003, 564),
                    ) + vents.map {
                        service(it.id, 0x11000006, it.id + 1L, it.id + 2L, it.id + 3L, it.id + 4L)
                    },
                ),
                mapOf(
                    "iid" to 768,
                    "type" to 0x01200002,
                    "version" to "1.0",
                    "services" to listOf(service(769, 0x20000001, 770)),
                ),
                mapOf(
                    "iid" to 1024,
                    "type" to 0x01500001,
                    "version" to "1.0",
                    "services" to seats.map { service(it.id, it.type, it.id + 1L, it.id + 2L, it.id + 3L) },
                ),
            )
        }

        fun descriptor(): Map<String, Any?> = mapOf(
            "protocolVersion" to "1.0",
            "pluginCount" to 1,
            "pluginMapping" to mapOf("VoyahTune" to 0),
            "pluginConfigs" to listOf(mapOf("name" to "VoyahTune", "pluginID" to 0, "accessories" to accessories())),
        )

        private fun wrap(value: Map<String, Any?>): ByteArray =
            OpackCodec.encode(mapOf("pluginID" to 0, "pluginData" to OpackCodec.encode(value)))
    }
}
