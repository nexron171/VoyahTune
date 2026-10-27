package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.shilapi.xcertplay.host.R

internal data class CarPlayPhone(val address: String, val name: String)

internal fun pairedCarPlayPhones(context: Context): List<CarPlayPhone> = runCatching {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: BluetoothAdapter.getDefaultAdapter()
    if (adapter?.isEnabled != true) return@runCatching emptyList()
    adapter.bondedDevices.map { device ->
        CarPlayPhone(device.address, device.name ?: context.getString(R.string.paired_device))
    }.sortedWith(compareBy({ it.name.lowercase() }, { it.address }))
}.getOrDefault(emptyList())

/** The initial wireless start waits only when the paired-device list offers a choice. */
internal class CarPlayPhoneSelection(
    private val handler: Handler,
    private val phones: () -> List<CarPlayPhone>,
    private val savedAddress: () -> String?,
    private val save: (CarPlayPhone) -> Unit,
    private val render: (State?) -> Unit,
    private val ready: () -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    data class State(val phones: List<CarPlayPhone>, val selected: CarPlayPhone?, val seconds: Int?)

    private var waiting = false
    private var complete = false
    private var closed = false
    private var resumed = false
    private var choosing = false
    private var selected: CarPlayPhone? = null
    private var deadline: Long? = null
    private val tick = Runnable { update() }

    fun holdsConnection(): Boolean {
        if (closed) return true
        if (complete) return false
        if (!waiting) {
            val candidates = candidates()
            if (candidates.size <= 1) {
                candidates.singleOrNull()?.let(::select)
                complete = true
                render(null)
                return false
            }
            waiting = true
        }
        update()
        return !complete
    }

    fun resume() {
        if (closed) return
        resumed = true
        if (waiting && !complete) update()
    }

    fun pause() {
        resumed = false
        deadline = null
        handler.removeCallbacks(tick)
    }

    fun beginChoice(): State? {
        if (!waiting || complete || closed) return null
        choosing = true
        deadline = null
        handler.removeCallbacks(tick)
        val candidates = candidates()
        return State(candidates, selected, null)
    }

    fun choose(address: String) {
        if (closed || complete || !waiting) return
        candidates().firstOrNull { it.address.equals(address, ignoreCase = true) }?.let(::select)
        deadline = null
    }

    fun endChoice() {
        choosing = false
        if (waiting && !closed && !complete) update()
    }

    fun close() {
        closed = true
        handler.removeCallbacks(tick)
        render(null)
    }

    fun bypass() {
        complete = true
        handler.removeCallbacks(tick)
        render(null)
    }

    private fun candidates() = phones().distinctBy { it.address.uppercase() }

    private fun select(phone: CarPlayPhone) {
        selected = phone
        if (!phone.address.equals(savedAddress(), ignoreCase = true)) save(phone)
    }

    private fun update() {
        handler.removeCallbacks(tick)
        if (closed || complete || !waiting) return
        val candidates = candidates()
        val previous = selected?.address ?: savedAddress()
        val next = candidates.firstOrNull { it.address.equals(previous, ignoreCase = true) }
            ?: candidates.firstOrNull()
        if (next?.address != selected?.address) deadline = null
        if (next != null) select(next) else selected = null

        if (!resumed || choosing || next == null) {
            deadline = null
            render(State(candidates, next, null))
        } else {
            val end = deadline ?: (now() + 5000).also { deadline = it }
            val remaining = (end - now()).coerceAtLeast(0)
            if (remaining == 0L) {
                complete = true
                render(null)
                handler.post { if (!closed && resumed) ready() }
                return
            }
            render(State(candidates, next, ((remaining + 999) / 1000).toInt()))
        }
        if (resumed && !choosing) handler.postDelayed(tick, 1000)
    }
}
