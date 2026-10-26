package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CarPlayHomeUiTest {
    @After
    fun cleanup() {
        CarPlayBackgroundSession.clear()
        VoyahCarPlayIntegration.install(null)
    }

    @Test
    fun exitOpensSystemHomeOnTheResolvedPhysicalDisplayAndNeverOpensDashboard() {
        for (physicalDisplay in listOf(0, 1)) {
            val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = lifecycle.get()
            VoyahCarPlayIntegration.install(object : VoyahCarPlayIntegration.Host {
                override fun start() {}
                override fun stop() {}
                override fun physicalDisplayId(displayId: Int): Int {
                    assertEquals(activity.windowManager.defaultDisplay.displayId, displayId)
                    return physicalDisplay
                }
                override fun openDashboard(displayId: Int): Boolean {
                    throw AssertionError("Exit must use the system home")
                }
            })
            try {
                CarPlayHomeUi.exitToHome(activity)
                val started = shadowOf(activity).nextStartedActivityForResult
                assertEquals(Intent.ACTION_MAIN, started.intent.action)
                assertTrue(started.intent.hasCategory(Intent.CATEGORY_HOME))
                assertNull(started.intent.component)
                assertTrue(started.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                val options = ActivityOptions::class.java.getDeclaredMethod("fromBundle", Bundle::class.java)
                    .invoke(null, started.options) as ActivityOptions
                assertEquals(physicalDisplay, options.launchDisplayId)
                assertTrue(activity.isFinishing)
            } finally {
                lifecycle.pause().stop().destroy()
            }
        }
    }

    @Test
    fun exitOpensSystemHomeWithoutVehicleIntegration() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = lifecycle.get()
        try {
            CarPlayHomeUi.exitToHome(activity)
            val home = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_MAIN, home.action)
            assertTrue(home.hasCategory(Intent.CATEGORY_HOME))
            assertTrue(activity.isFinishing)
        } finally {
            lifecycle.pause().stop().destroy()
        }
    }

    @Test
    fun exitRemovesBothCarPlayTasksAndPreservesOtherApplicationScreensAndSession() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = lifecycle.get()
        try {
            val settingsTask = task(activity, DiPlayActivity::class.java, activity.taskId + 1)
            val projectionTask = task(activity, CarPlayHostActivity::class.java, activity.taskId + 2)
            val otherTask = task(activity, Activity::class.java, activity.taskId + 3)
            val currentTask = task(activity, DiPlayActivity::class.java, activity.taskId)
            val removedTask = mock(ActivityManager.AppTask::class.java)
            shadowOf(activity.getSystemService(ActivityManager::class.java)).setAppTasks(
                listOf(settingsTask, projectionTask, otherTask, currentTask, removedTask),
            )
            val controller = mock(CarPlayController::class.java)
            val sink = mock(AndroidMediaSink::class.java)
            var stopped = false
            CarPlayBackgroundSession.store(
                controller, sink, 1280, 720, activity,
                CarPlaySessionDisplay(1280, 720, 0, false, false, 1280, 720),
            ) {
                stopped = true
                it()
            }
            CarPlayBackgroundSession.active = true

            CarPlayHomeUi.finishActivities(activity)

            verify(settingsTask).finishAndRemoveTask()
            verify(projectionTask).finishAndRemoveTask()
            verify(otherTask, never()).finishAndRemoveTask()
            verify(currentTask, never()).finishAndRemoveTask()
            verify(removedTask, never()).finishAndRemoveTask()
            assertTrue(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertFalse(stopped)
            assertTrue(CarPlayBackgroundSession.active)
            assertSame(controller, CarPlayBackgroundSession.snapshot()!!.controller)
            verifyNoInteractions(controller, sink)
        } finally {
            lifecycle.pause().stop().destroy()
        }
    }

    private fun task(activity: Activity, target: Class<*>, taskId: Int): ActivityManager.AppTask {
        val task = mock(ActivityManager.AppTask::class.java)
        val info = ActivityManager.RecentTaskInfo().apply {
            this.taskId = taskId
            baseIntent = Intent().setComponent(ComponentName(activity, target))
        }
        `when`(task.taskInfo).thenReturn(info)
        return task
    }
}
