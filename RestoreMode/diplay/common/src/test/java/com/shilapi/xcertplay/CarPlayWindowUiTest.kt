package com.shilapi.xcertplay

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CarPlayWindowUiTest {
    @After fun reset() { CarPlayWindowUi.install(null) }

    @Test fun embeddingResizesTheWholeContentAndConsumesInsetsOnce() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        CarPlayWindowUi.install(object : CarPlayWindowUi.Policy {
            override fun insets(activity: Activity, view: View, system: Insets) =
                Insets.of(145, maxOf(45, system.top), system.right, system.bottom)
            override fun authenticationError(activity: Activity) = "host auth error"
        })
        val content = FrameLayout(activity)
        val video = View(activity)
        content.addView(video, FrameLayout.LayoutParams(-1, -1))
        var childTop = -1
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            childTop = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            insets
        }
        val root = CarPlayWindowUi.wrap(activity, content)
        activity.setContentView(root)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 45, 0, 0)).build()
        repeat(2) {
            ViewCompat.dispatchApplyWindowInsets(root, insets)
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 720)
            assertEquals(145, content.left)
            assertEquals(45, content.top)
            assertEquals(1775, video.width)
            assertEquals(675, video.height)
            assertTrue(childTop <= 0)
        }
        assertEquals("host auth error", CarPlayWindowUi.authenticationError(activity))
        controller.pause().stop().destroy()
    }

    @Test fun standaloneLibraryKeepsItsOriginalContentAndWindowPolicy() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val content = View(activity)
        assertSame(content, CarPlayWindowUi.wrap(activity, content))
        assertFalse(CarPlayWindowUi.configureWindow(activity))
        assertNull(CarPlayWindowUi.authenticationError(activity))
    }
}
