package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle

/** Chooses the session screen before constructing the standalone settings UI. */
class CarPlayEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (CarPlayBackgroundSession.hasSession()) {
            CarPlayHostActivity::class.java
        } else {
            DiPlayActivity::class.java
        }
        val launch = Intent(this, target)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = windowManager.defaultDisplay.displayId
        }.toBundle().apply {
            putInt("android.activity.windowingMode", 1)
        }
        try {
            startActivity(launch, options)
        } finally {
            finish()
        }
    }
}
