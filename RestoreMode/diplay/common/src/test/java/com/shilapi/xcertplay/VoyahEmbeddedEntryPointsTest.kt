package com.shilapi.xcertplay

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoyahEmbeddedEntryPointsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null

    @Before
    fun prepare() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }

    @After
    fun cleanup() {
        controller?.destroy()
        CarPlayBackgroundSession.clear()
    }

    @Test
    fun explicitStandaloneSetupIntentKeepsVoyahHomeAccessible() {
        val activity = open(Intent().putExtra("page", "setup"))
        assertEquals("home", ReflectionHelpers.getField<String>(activity, "page"))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openSetupGuide",
            ReflectionHelpers.ClassParameter(Boolean::class.javaPrimitiveType!!, true))
        assertEquals("home", ReflectionHelpers.getField<String>(activity, "page"))
    }

    @Test
    fun standaloneUpdateActionsCannotDownloadOrInstallAnotherApk() {
        val activity = open(Intent().putExtra("page", "about"))
        for (action in listOf("checkForUpdates", "downloadUpdate", "installUpdate")) {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, action)
        }
        assertEquals("IDLE", ReflectionHelpers.getField<Any>(activity, "updateStage").toString())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun bluetoothWakeExtraCannotBypassVoyahLaunchOwnership() {
        DiPlayPreferences.savePhone(app, "12:34:56:78:9A:BC", "iPhone")
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        val activity = open(Intent().putExtra("page", "home"))
        ReflectionHelpers.setField(activity, "setupError", null)
        val accepted = ReflectionHelpers.callInstanceMethod<Boolean>(activity, "consumeBluetoothAutoConnectIntent",
            ReflectionHelpers.ClassParameter(Intent::class.java,
                Intent().putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, true)))
        assertFalse(accepted)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun open(intent: Intent): DiPlayActivity {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent).create()
        return controller!!.get()
    }
}
