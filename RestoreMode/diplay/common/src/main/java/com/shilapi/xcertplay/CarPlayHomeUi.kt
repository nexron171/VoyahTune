package com.shilapi.xcertplay

import android.content.Context
import android.view.View

/** Lets the host own home styling while DiPlay owns connection and session behavior. */
object CarPlayHomeUi {
    fun interface Factory {
        fun create(context: Context, actions: Actions): Screen
    }

    interface Actions {
        fun wireless()
        fun usb()
        fun settings()
        fun back()
        fun choosePhone()
        fun disconnect()
        fun setAutoConnect(enabled: Boolean)
        fun hotspotSettings()
    }

    interface Screen {
        val view: View
        fun updateAutoConnect(enabled: Boolean, phoneSelected: Boolean)
        fun update(status: String, running: Boolean, enabled: Boolean, hint: String, problem: String?, hotspotProblem: Boolean)
    }

    private var factory: Factory? = null

    @JvmStatic
    fun install(factory: Factory) {
        this.factory = factory
    }

    internal fun create(context: Context, actions: Actions): Screen? = factory?.create(context, actions)
}
