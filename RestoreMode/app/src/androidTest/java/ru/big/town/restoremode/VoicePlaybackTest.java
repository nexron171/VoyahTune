package ru.big.town.restoremode;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Exercises the real microphone/result/playback lifecycle without the vehicle service. */
@RunWith(AndroidJUnit4.class)
public class VoicePlaybackTest {
    @Test public void canPlayFinishedRecordingAndClosingDeletesIt() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), android.Manifest.permission.RECORD_AUDIO);
        Activity activity = instrumentation.startActivitySync(new Intent(context, VoiceActivity.class)
                .putExtra(VoiceActivity.TEST_ONLY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            AtomicReference<Button> play = new AtomicReference<>();
            long deadline = SystemClock.elapsedRealtime() + 45000;
            while (SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync(() -> play.set(findButton(activity.getWindow().getDecorView(), "Прослушать запись")));
                if (play.get() != null && play.get().isEnabled()) break;
                SystemClock.sleep(100);
            }
            assertNotNull("Playback control missing", play.get());
            assertTrue("No recording became available", play.get().isEnabled());
            File directory = new File(context.getCacheDir(), VoiceRecording.DIRECTORY);
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".wav"));
            assertNotNull(files); assertEquals(1, files.length);
            assertTrue(files[0].length() > 44 && files[0].length() <= 320044);
            instrumentation.runOnMainSync(() -> play.get().performClick());
            SystemClock.sleep(250);
            instrumentation.runOnMainSync(() -> assertEquals("Остановить запись", play.get().getText().toString()));
            instrumentation.runOnMainSync(() -> play.get().performClick());
            instrumentation.runOnMainSync(() -> assertEquals("Прослушать запись", play.get().getText().toString()));
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            assertFalse("Recording survives screen close", files[0].exists());
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
    private static Button findButton(View view, String title) {
        if (view instanceof Button && title.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button result = findButton(group.getChildAt(i), title);
                if (result != null) return result;
            }
        }
        return null;
    }
}
