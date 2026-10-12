package com.shilapi.xcertplay.update

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Android 9 and older have no MediaStore Downloads, so the copy goes to the app's external files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateApkExportTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun copiesTheApkAndKeepsOnlyTheNewestOne() {
        val directory = File(context.getExternalFilesDir(null), "update").apply { mkdirs() }
        File(directory, "DiPlay-0.2.14.apk").writeText("old")
        val apk = File(context.cacheDir, "DiPlay-0.2.15.apk").apply { writeText("new") }

        val path = UpdateApkExport.copy(context, apk)

        assertEquals(File(directory, "DiPlay-0.2.15.apk").absolutePath, path)
        assertEquals("new", File(path!!).readText())
        assertFalse(File(directory, "DiPlay-0.2.14.apk").exists())
        assertTrue(apk.exists())
    }
}
