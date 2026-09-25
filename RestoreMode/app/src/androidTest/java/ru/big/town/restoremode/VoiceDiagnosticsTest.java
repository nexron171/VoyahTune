package ru.big.town.restoremode;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoiceDiagnosticsTest {
    @Test public void diagnosticsAreVisibleOnlyWithoutExecution() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        for (boolean testOnly : new boolean[]{false, true}) {
            Activity activity = instrumentation.startActivitySync(
                    new Intent(instrumentation.getTargetContext(), VoiceActivity.class)
                            .putExtra(VoiceActivity.TEST_ONLY, testOnly).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                View details = activity.getWindow().getDecorView().findViewWithTag("voiceDiagnostics");
                assertNotNull("Diagnostics view missing", details);
                instrumentation.runOnMainSync(() -> assertEquals(
                        testOnly ? View.VISIBLE : View.GONE, details.getVisibility()));
            } finally {
                instrumentation.runOnMainSync(activity::finish);
                instrumentation.waitForIdleSync();
            }
        }
    }
}
