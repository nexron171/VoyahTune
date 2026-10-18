package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
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
