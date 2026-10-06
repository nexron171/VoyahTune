package ru.big.town.restoremode;

import android.app.Instrumentation;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicReference;

/** Emulates installer -g on the emulator; no production code grants location permissions. */
@RunWith(AndroidJUnit4.class)
public class TripLocationConsentInstrumentedTest {
    private void permission(Instrumentation i, String operation, String name) throws Exception {
        int user = Process.myUid() / 100000; // Android per-user UID range; emulator test only.
        try (ParcelFileDescriptor fd = i.getUiAutomation().executeShellCommand("pm " + operation + " --user " + user
                + " ru.big.town.restoremode android.permission." + name);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            while (input.read() >= 0) { }
        }
    }
    @Test public void autoGrantedPermissionsDoNotRecordWithoutConsentAndDeniedConsentSurvivesRegrant() throws Exception {
        Instrumentation i = InstrumentationRegistry.getInstrumentation(); Context c = i.getTargetContext();
        c.getSharedPreferences("TripLocationPermission", Context.MODE_PRIVATE).edit().clear().commit();
        permission(i, "grant", "ACCESS_COARSE_LOCATION"); permission(i, "grant", "ACCESS_FINE_LOCATION"); permission(i, "grant", "ACCESS_BACKGROUND_LOCATION");
        assertTrue(TripLocationPermission.fine(c)); assertTrue(TripLocationPermission.background(c));
        assertFalse("Installer permissions are not consent", TripLocationPermission.allowed(c));
        TripHistoryActivity a = (TripHistoryActivity)i.startActivitySync(new Intent(c, TripHistoryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            AtomicReference<AlertDialog> dialog = new AtomicReference<>();
            i.runOnMainSync(() -> dialog.set(TripLocationPermission.atStartup(a)));
            assertNotNull("Explicit consent even after installer grants", dialog.get());
            i.runOnMainSync(() -> dialog.get().getButton(DialogInterface.BUTTON_NEGATIVE).performClick());
            assertFalse(TripLocationPermission.allowed(c));
            i.runOnMainSync(() -> TripLocationPermission.request(a));
            assertTrue(TripLocationPermission.allowed(c));
            // Runtime revocation can kill instrumentation. Exercise persistent refusal here.
            i.runOnMainSync(() -> TripLocationPermission.disable(a));
            permission(i, "grant", "ACCESS_BACKGROUND_LOCATION");
            assertFalse("Update -g must not override refusal", TripLocationPermission.allowed(c));
        } finally { i.runOnMainSync(a::finish); i.waitForIdleSync(); }
    }
}
