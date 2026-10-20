package com.bms.monitor

import android.app.Application
import android.content.Context

class BmsApp : Application() {

    companion object {
        lateinit var instance: BmsApp
            private set

        /** Контекст приложения, null, если Application ещё не создан. */
        fun probeCtx(): Context? =
            if (::instance.isInitialized) instance.appContext else null
    }

    lateinit var appContext: Context
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        appContext = applicationContext
    }
}
