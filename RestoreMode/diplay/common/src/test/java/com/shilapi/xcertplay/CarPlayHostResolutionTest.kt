package com.shilapi.xcertplay

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostResolutionTest {
    private lateinit var activity: CarPlayHostActivity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        set("airPlayIdentity", AirPlayIdentity.generate())
        set("hevcEnabled", false)
        ShadowMediaCodecList.reset()
    }

    @After
    fun tearDown() {
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        ShadowMediaCodecList.reset()
    }

    @Test
    fun unsupportedEnlargedResolutionFallsBackBeforeAdvertisingItToThePhone() {
        decoder(maxWidth = 1920, maxHeight = 1080)
        val display = config(160).main
        assertEquals(1920, display.widthPixels)
        assertEquals(990, display.heightPixels)
        assertEquals(100, get("displayScalePercent"))
        assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(activity))
    }

    @Test
    fun supportedEnlargedResolutionKeepsItsPrecisePercentAndPhysicalSize() {
        decoder(maxWidth = 3840, maxHeight = 2160)
        val native = config(100).main
        val display = config(160).main
        assertEquals(DisplayDiagnosticSnapshot.report(activity), 3072, display.widthPixels)
        assertEquals(1584, display.heightPixels)
        assertEquals(native.widthPhysicalMm, display.widthPhysicalMm)
        assertEquals(native.heightPhysicalMm, display.heightPhysicalMm)
        assertEquals(160, AirPlayPersistence.loadDisplayScalePercent(activity))
    }

    @Test
    fun decoderFrameRateAlsoGatesTheEnlargedStream() {
        decoder(maxWidth = 3840, maxHeight = 2160, maxFps = 30.0)
        assertEquals(1920, config(160).main.widthPixels)
    }

    @Test
    fun decoderAlignmentAlsoGatesTheEnlargedStream() {
        decoder(maxWidth = 3840, maxHeight = 2160, alignment = 16)
        val display = config(160, height = 978).main
        assertEquals(1920, display.widthPixels)
        assertEquals(978, display.heightPixels)
    }

    @Test
    fun failedSmallerUiCanvasCanKeepASupportedSupersampledResolution() {
        decoder(maxWidth = 3120, maxHeight = 1700)
        val display = config(150, uiPercent = 75).main
        assertEquals(DisplayDiagnosticSnapshot.report(activity), 2880, display.widthPixels)
        assertEquals(1486, display.heightPixels)
        assertEquals(100, get("uiScalePercent"))
        assertEquals(150, AirPlayPersistence.loadDisplayScalePercent(activity))
    }

    @Test
    fun capabilityCheckUsesTheActualCanvasIncludingLargerUiControls() {
        decoder(maxWidth = 2200, maxHeight = 1200)
        val display = config(130, uiPercent = 115).main
        assertEquals(DisplayDiagnosticSnapshot.report(activity), 2170, display.widthPixels)
        assertEquals(1120, display.heightPixels)
        assertEquals(130, get("displayScalePercent"))
        assertEquals(115, get("uiScalePercent"))
    }

    @Test
    fun supersamplingCannotBypassTheExistingFourKEnlargementLimit() {
        decoder(maxWidth = 8192, maxHeight = 8192)
        val display = config(160, width = 3840, height = 2160).main
        assertEquals(3840, display.widthPixels)
        assertEquals(2160, display.heightPixels)
        assertEquals(100, get("displayScalePercent"))
    }

    @Test
    fun normalResolutionKeepsTheExistingStartupPathWithoutDecoderMetadata() {
        val display = config(100).main
        assertEquals(1920, display.widthPixels)
        assertEquals(990, display.heightPixels)
        assertEquals(100, get("displayScalePercent"))
    }

    @Test
    fun savedLayoutsAdvertiseTheirSelectedEdgeAndUnknownLayoutsUseClassic() {
        for (savedLayout in listOf(null, "AUTOMATIC", "BOTTOM", "DRIVER_SIDE", "unknown")) {
            activity
                .getSharedPreferences("diplay_carplay_dock", 0)
                .edit()
                .putString("dock", savedLayout)
                .commit()
            val configuration = config(100, width = 1920, height = 720)
            assertNull(configuration.vehicleTheme)
            assertFalse(configuration.rightHandDrive)
            assertNotEquals("Center_Display", configuration.mainDisplayIdentifier)
            assertEquals(1920, configuration.main.widthPixels)
            assertEquals(720, configuration.main.heightPixels)
            val display = configuration.main
            assertEquals(
                if (savedLayout == "BOTTOM")
                    com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_BOTTOM
                else com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE,
                display.viewAreas!![display.initialViewArea].dockEdge,
            )
        }
    }

    @Test
    fun astonMartinUsesItsFixedProfileOnlyWhenSelectedWithUltra() {
        set("widthPhysicalMm", 300)
        CarPlaySettingsAccess.setDock(activity, CarPlayDock.DUAL_SIDES)
        val configuration = config(160, uiPercent = 75, width = 1700, height = 600)
        assertEquals(
            com.shilapi.xcertplay.airplay.AirPlayVehicleTheme.DUAL_SIDE_DOCKS,
            configuration.vehicleTheme,
        )
        assertEquals("Center_Display", configuration.mainDisplayIdentifier)
        assertEquals(1920, configuration.main.widthPixels)
        assertEquals(720, configuration.main.heightPixels)
        assertEquals(400, configuration.main.widthPhysicalMm)
        assertEquals(150, configuration.main.heightPhysicalMm)
        assertEquals(75, CarPlaySettingsAccess.uiScalePercent(activity))
        CarPlaySettingsAccess.setUltra(activity, false)
        assertNull(config(100).vehicleTheme)
        CarPlaySettingsAccess.setUltra(activity, true)
        assertNull(config(100).vehicleTheme)
    }

    @Test
    fun supersamplingDoesNotSilentlyFallBackToASoftwareDecoder() {
        decoder(maxWidth = 3840, maxHeight = 2160, hardware = false)
        val display = config(160).main
        assertEquals(1920, display.widthPixels)
        assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(activity))
        assertTrue(DisplayDiagnosticSnapshot.report(activity).contains("software_decoder"))
    }

    @Test
    fun settingsScaleIsReadForEachHandshakeAndKeepsTheVideoCanvas() {
        set("widthPhysicalMm", 300)
        val initial = config(100, height = 720).main
        val scales = listOf(75, 85, 100, 115)
        val widths = listOf(400, 353, 300, 261)
        val heights = listOf(151, 133, 113, 98)
        for (index in scales.indices) {
            CarPlaySettingsAccess.setUiScalePercent(activity, scales[index])
            val display = currentConfig(height = 720).main
            assertEquals(1920, display.widthPixels)
            assertEquals(720, display.heightPixels)
            assertEquals(widths[index], display.widthPhysicalMm)
            assertEquals(heights[index], display.heightPhysicalMm)
            assertEquals(initial.safeArea, display.safeArea)
            assertEquals(initial.viewAreas, display.viewAreas)
        }
        assertEquals(300, initial.widthPhysicalMm)

        activity
            .getSharedPreferences("diplay_carplay_dock", 0)
            .edit()
            .putString("dock", "DUAL_SIDES")
            .commit()
        val display = currentConfig(height = 720).main
        assertEquals(261, display.widthPhysicalMm)
        assertEquals(98, display.heightPhysicalMm)
        assertEquals(115, CarPlaySettingsAccess.uiScalePercent(activity))
    }

    private fun config(
        percent: Int,
        uiPercent: Int = 100,
        width: Int = 1920,
        height: Int = 990,
    ): AirPlayConfig {
        set("displayScalePercent", percent)
        set("uiScalePercent", uiPercent)
        AirPlayPersistence.saveDisplayScalePercent(activity, percent)
        AirPlayPersistence.saveUiScalePercent(activity, uiPercent)
        return currentConfig(width, height)
    }

    private fun currentConfig(width: Int = 1920, height: Int = 990): AirPlayConfig {
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size =
            sizeClass
                .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .newInstance(width, height)
        return activity.javaClass
            .getDeclaredMethod("createAirPlayConfig", sizeClass)
            .apply { isAccessible = true }
            .invoke(activity, size) as AirPlayConfig
    }

    private fun decoder(
        maxWidth: Int,
        maxHeight: Int,
        maxFps: Double = 120.0,
        alignment: Int = 2,
        hardware: Boolean = true,
    ) {
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, maxWidth, maxHeight)
                .apply {
                    setString("alignment", "${alignment}x$alignment")
                    setString("frame-rate-range", "1-${maxFps.toInt()}")
                }
        val level =
            MediaCodecInfo.CodecProfileLevel().apply {
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                this.level = MediaCodecInfo.CodecProfileLevel.AVCLevel52
            }
        val capabilities =
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(format)
                .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
                .setProfileLevels(arrayOf(level))
                .build()
        val codec =
            MediaCodecInfoBuilder.newBuilder()
                .setName("c2.test.hardware")
                .setIsHardwareAccelerated(hardware)
                .setIsSoftwareOnly(!hardware)
                .setCapabilities(capabilities)
                .build()
        ShadowMediaCodecList.addCodec(codec)
    }

    private fun get(name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun set(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
