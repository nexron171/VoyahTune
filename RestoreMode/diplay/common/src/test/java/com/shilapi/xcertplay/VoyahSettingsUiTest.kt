package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "ru-w1280dp-h720dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class VoyahSettingsUiTest {
    private var lifecycle: ActivityController<DiPlayActivity>? = null

    @Before
    fun prepare() {
        for (name in listOf("xcertplay_airplay", "voyah_carplay", "diplay")) {
            RuntimeEnvironment.getApplication().getSharedPreferences(name, 0).edit().clear().commit()
        }
        VoyahCarPlayIntegration.install(mock(VoyahCarPlayIntegration.Host::class.java))
        CarPlayBackgroundSession.clear()
    }

    @After
    fun cleanup() {
        lifecycle?.pause()?.stop()?.destroy()
        VoyahCarPlayIntegration.install(null)
        CarPlayBackgroundSession.clear()
    }

    @Test
    fun freshSettingsSelectSwReasonAndWifiDirectInVisibleControls() {
        val activity = open()
        val display = LinearLayout(activity)
        section(activity, "displaySettings", display)
        activity.setContentView(display)
        val theme = views(display).filterIsInstance<Button>().single {
            it.text.startsWith(activity.getString(R.string.carplay_night_mode))
        }
        assertTrue(theme.text.toString(), theme.text.contains("SWReason"))
        theme.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(CarPlayNightMode.entries.indexOf(CarPlayNightMode.VEHICLE), dialog.listView.checkedItemPosition)
        assertTrue(dialog.listView.adapter.getItem(dialog.listView.checkedItemPosition).toString().contains("SWReason"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        val connection = LinearLayout(activity)
        section(activity, "wirelessLinkControls", connection)
        activity.setContentView(connection)
        val selected = views(connection).filterIsInstance<Button>().filter { it.text.startsWith("✓") }.toList()
        assertEquals(listOf("✓  ${activity.getString(R.string.wifi_direct)}"), selected.map { it.text.toString() })
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(activity))
    }

    @Test
    fun locationReportingIsCheckedByDefaultAndPreservesExplicitDisable() {
        val activity = open()
        fun locationSwitch(): Switch = views(activity.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == activity.getString(R.string.report_location_to_iphone) }
        assertTrue(locationSwitch().isChecked)
        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(activity))
        AirPlayPersistence.saveLocationReportingEnabled(activity, false)
        lifecycle!!.recreate()
        val recreated = lifecycle!!.get()
        val control = views(recreated.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == recreated.getString(R.string.report_location_to_iphone) }
        assertFalse(control.isChecked)
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(recreated))
    }

    @Test
    fun hiddenSettingsAreAbsentFromEveryVisibleCategoryAndSearch() {
        val activity = open()
        val forbidden = listOf(R.string.audio, R.string.settings_advanced, R.string.settings_wheel_keys,
            R.string.settings_ambient_title, R.string.connect_when_iphone_bluetooth_connects,
            R.string.settings_navigation_wheel_volume, R.string.settings_hide_byd_call_popup,
            R.string.byd_adb_features, R.string.byd_navigation, R.string.adb_cluster_activity_mode,
            R.string.settings_background_update_checks, R.string.update_check).map(activity::getString)
        for (category in SettingsInformationArchitecture.visibleCategories) {
            ReflectionHelpers.setField(activity, "settingsCategory", category)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val labels = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            for (label in forbidden) assertFalse("$category exposes $label", labels.any { it == label || it.startsWith("$label\n") })
        }
        val index = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(activity, "buildSettingsSearchIndex")
        assertFalse(index.any { it.category in listOf(SettingsCategory.AUDIO, SettingsCategory.ADVANCED) })
        for (label in forbidden) assertFalse("Search exposes $label", index.any { it.title == label })
    }

    @Test
    fun restoringHiddenCategoryFallsBackToOverview() {
        for (category in listOf(SettingsCategory.AUDIO, SettingsCategory.ADVANCED)) {
            val activity = open(Bundle().apply {
                putString("page", "settings")
                putString("settings_category", category.name)
            })
            assertEquals(SettingsCategory.OVERVIEW, ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"))
            lifecycle!!.pause().stop().destroy()
            lifecycle = null
        }
    }

    @Test
    fun explicitTransportAndThemeChoicesSurviveInitialization() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.DAY)
        val activity = open()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(activity))
        assertEquals(CarPlayNightMode.DAY, AirPlayPersistence.loadCarPlayNightMode(activity))
    }

    private fun open(state: Bundle? = null): DiPlayActivity {
        lifecycle = Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", "settings"))
            .create(state).start().resume().visible()
        return lifecycle!!.get()
    }

    private fun section(activity: DiPlayActivity, name: String, target: LinearLayout) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, name,
            ReflectionHelpers.ClassParameter(LinearLayout::class.java, target))
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
