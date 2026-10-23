package com.shilapi.xcertplay.network

import java.util.function.BooleanSupplier

/** The app supplies the OD transport without enabling the upstream ADB integration. */
object VoyahWifiScanPause {
    interface Host {
        fun acquireWifiScanPause(owner: Any, current: BooleanSupplier)
        fun releaseWifiScanPause(owner: Any)
    }

    @Volatile
    internal var host: Host? = null
        private set

    val available: Boolean get() = host != null

    @JvmStatic
    fun install(host: Host?) {
        this.host = host
    }
}
