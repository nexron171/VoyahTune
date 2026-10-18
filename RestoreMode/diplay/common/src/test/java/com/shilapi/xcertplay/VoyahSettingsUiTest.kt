package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
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
        val forbidden = listOf(
            R.string.settings_advanced,
            R.string.settings_wheel_keys,
            R.string.settings_ambient_title,
            R.string.connect_when_iphone_bluetooth_connects,
            R.string.settings_navigation_wheel_volume,
            R.string.settings_hide_byd_call_popup,
            R.string.byd_adb_features,
            R.string.byd_navigation,
            R.string.adb_cluster_activity_mode,
            R.string.settings_background_update_checks,
            R.string.update_check,
            R.string.contrib_audio_home_media_channel_label,
            R.string.contrib_audio_home_nav_channel_label,
            R.string.advanced_audio_channel_mapping,
            R.string.phone_audio_route,
            R.string.phone_audio_route_apps,
        ).map(activity::getString)
        for (category in SettingsInformationArchitecture.visibleCategories) {
            ReflectionHelpers.setField(activity, "settingsCategory", category)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val labels = views(activity.window.decorView)
                .filterIsInstance<TextView>()
                .map { it.text.toString() }
                .toList()
            for (label in forbidden) {
                assertFalse("$category exposes $label", labels.any {
                    it == label || it.startsWith("$label\n") || it.startsWith("$label ·")
                })
            }
        }
        val index = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(
            activity, "buildSettingsSearchIndex",
        )
        assertFalse(index.any { it.category == SettingsCategory.ADVANCED })
        for (label in forbidden) {
            assertFalse("Search exposes $label", index.any { it.title == label })
        }
        for (resource in listOf(R.string.settings_music_buffer_wireless, R.string.settings_music_buffer_wired)) {
            assertTrue(index.any {
                it.category == SettingsCategory.AUDIO && it.title == activity.getString(resource)
            })
        }
    }

    @Test
    fun restoringHiddenCategoryFallsBackToOverview() {
        val activity = open(Bundle().apply {
            putString("page", "settings")
            putString("settings_category", SettingsCategory.ADVANCED.name)
        })
        assertEquals(
            SettingsCategory.OVERVIEW,
            ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"),
        )
    }

    @Test
    fun audioSettingsAreReachableAndKeepBufferChoicesAfterRecreation() {
        val activity = open()
        val audioTitle = activity.getString(R.string.audio)
        views(activity.window.decorView).first {
            it.contentDescription == activity.getString(R.string.settings_open_category, audioTitle)
        }.performClick()
        assertEquals(
            SettingsCategory.AUDIO,
            ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"),
        )
        for ((resource, millis) in listOf(
            R.string.settings_music_buffer_wireless to 500,
            R.string.settings_music_buffer_wired to 100,
        )) {
            val button = bufferButton(activity, resource)
            assertTrue(button.text.contains("1000 мс · по умолчанию"))
            button.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val options = dialog.listView.adapter
            assertEquals("1000 мс · по умолчанию", options.getItem(dialog.listView.checkedItemPosition))
            val selected = (0 until options.count).single { options.getItem(it) == "$millis мс" }
            dialog.listView.performItemClick(null, selected, options.getItemId(selected))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(activity, wireless = true))
        assertEquals(100, AirPlayPersistence.loadMediaBufferMillis(activity, wireless = false))

        lifecycle!!.recreate()
        val recreated = lifecycle!!.get()
        assertEquals(
            SettingsCategory.AUDIO,
            ReflectionHelpers.getField<SettingsCategory>(recreated, "settingsCategory"),
        )
        assertTrue(bufferButton(recreated, R.string.settings_music_buffer_wireless).text.endsWith("500 мс"))
        assertTrue(bufferButton(recreated, R.string.settings_music_buffer_wired).text.endsWith("100 мс"))
    }

    @Test
    fun audioProcessingControlsAreVisibleWithoutChangingSavedChannels() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveMediaAudioChannel(context, 4)
        AirPlayPersistence.saveNavigationAudioChannel(context, 7)
        val activity = open(Bundle().apply {
            putString("page", "settings")
            putString("settings_category", SettingsCategory.AUDIO.name)
        })
        val controls = views(activity.window.decorView).filterIsInstance<Switch>().toList()
        for (resource in listOf(
            R.string.settings_call_speakerphone,
            R.string.call_echo_cancellation,
            R.string.call_voice_filter,
            R.string.settings_disable_media_key_audio_focus,
            R.string.contrib_audio_home_toggle_audio_focus,
            R.string.audio_focus_auto_yield,
            R.string.main_buffered_audio,
            R.string.settings_speech_without_low_rates,
            R.string.settings_car_bluetooth_audio,
        )) {
            assertTrue(activity.getString(resource), controls.any {
                it.isShown && it.contentDescription == activity.getString(resource)
            })
        }
        controls.single {
            it.contentDescription == activity.getString(R.string.call_echo_cancellation)
        }.isChecked = true
        assertTrue(AirPlayPersistence.loadCallEchoCancellation(activity))
        assertEquals(4, AirPlayPersistence.loadMediaAudioChannel(activity))
        assertEquals(7, AirPlayPersistence.loadNavigationAudioChannel(activity))
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
        ReflectionHelpers.callInstanceMethod<Unit>(
            activity, name, ReflectionHelpers.ClassParameter(LinearLayout::class.java, target),
        )
    }

    private fun bufferButton(activity: DiPlayActivity, resource: Int): Button = views(activity.window.decorView)
        .filterIsInstance<Button>()
        .single { it.text.startsWith(activity.getString(resource) + " ·") }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                yieldAll(views(view.getChildAt(index)))
            }
        }
    }
}
