package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.WeakHashMap

/** Explicit disconnect also retires hosts still waiting for permissions or phone selection. */
internal object CarPlaySessionHosts {
    private val hosts = Collections.newSetFromMap(WeakHashMap<CarPlayHostActivity, Boolean>())
    private val handler = Handler(Looper.getMainLooper())

    fun register(activity: CarPlayHostActivity) {
        hosts.add(activity)
    }

    fun unregister(activity: CarPlayHostActivity) {
        hosts.remove(activity)
    }

    fun disconnect(activity: CarPlayHostActivity? = null, completion: () -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { disconnect(activity, completion) }
            return
        }
        val closing = (hosts.toList() + listOfNotNull(activity)).distinct()
        val displays = closing.map { it.windowManager.defaultDisplay.displayId } +
            listOfNotNull(CarPlayBackgroundSession.snapshot()?.ownerDisplayId)
        displays.distinct().filter { it > 1 }.forEach(VoyahCarPlayIntegration::closeProjection)
        CarPlayBackgroundSession.stop { handler.post { completion() } }
        closing.forEach { it.finishDisconnectedSession() }
    }
}
