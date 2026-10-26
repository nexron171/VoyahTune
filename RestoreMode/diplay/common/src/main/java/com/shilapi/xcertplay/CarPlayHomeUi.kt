package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

    @JvmStatic
    fun exitToHome(activity: Activity) {
        val displayId = activity.window.decorView.display?.displayId
            ?: activity.windowManager.defaultDisplay.displayId
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = VoyahCarPlayIntegration.physicalDisplayId(displayId)
        }
        runCatching { activity.startActivity(home, options.toBundle()) }
        finishActivities(activity)
    }

    @JvmStatic
    fun finishActivities(activity: Activity) {
        val components = setOf(
            ComponentName(activity, DiPlayActivity::class.java),
            ComponentName(activity, CarPlayHostActivity::class.java),
        )
        // Settings and projection have separate tasks; either can be underneath the other.
        for (task in activity.getSystemService(ActivityManager::class.java).appTasks) {
            val info = task.taskInfo ?: continue
            if (info.taskId != activity.taskId && info.baseIntent.component in components) {
                task.finishAndRemoveTask()
            }
        }
        activity.finishAndRemoveTask()
    }

    internal fun create(context: Context, actions: Actions): Screen? = factory?.create(context, actions)
}
