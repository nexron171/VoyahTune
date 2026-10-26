package ru.big.town.restoremode.vehicle;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemProperties;

import ru.big.town.common.DoorMediaSettings;
import ru.big.town.restoremode.integration.config.SettingsContentProvider;

import java.util.concurrent.Executors;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class DoorMediaAccTest {
    private SharedPreferences preferences;

    @Before
    public void enableDoorPlayback() {
        ShadowSystemProperties.override("ro.build.product", "f6811");
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("door-acc-test", 0);
        preferences.edit().clear().putBoolean(DoorMediaSettings.PLAY_ON_DOOR, true).commit();
    }

    @Test
    public void onlyFirstClaimInAccCycleStartsPlayback() {
        acc(2, 10);
        assertTrue(claim(10));
        assertFalse(claim(10));
        acc(2, 10);
        assertFalse(claim(10));
        acc(0, 10);
        assertFalse(claim(10));
        acc(2, 10);
        assertTrue(claim(10));
    }

    @Test
    public void restartDoesNotRearmAndNewBootRequiresItsOwnAccObservation() {
        acc(2, 10);
        assertTrue(claim(10));
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("door-acc-test", 0);
        DriveSelectionPreferences.hook(preferences, "snapshot", null, 10);
        assertFalse(claim(10));
        assertFalse(claim(11));
        acc(2, 11);
        assertTrue(claim(11));
    }

    @Test
    public void disabledOptionDoesNotConsumePendingPlayback() {
        acc(2, 10);
        preferences.edit().putBoolean(DoorMediaSettings.PLAY_ON_DOOR, false).commit();
        assertFalse(claim(10));
        preferences.edit().putBoolean(DoorMediaSettings.PLAY_ON_DOOR, true).commit();
        assertTrue(claim(10));
    }

    @Test
    public void pendingFlagIsConsumedBeforeAnyPlaybackResult() {
        acc(2, 10);
        assertTrue(preferences.getBoolean(DoorMediaSettings.PLAY_PENDING, false));
        assertTrue(claim(10));
        assertFalse(preferences.getBoolean(DoorMediaSettings.PLAY_PENDING, true));
        assertFalse(claim(10));
    }

    @Test
    public void providerKeepsLegacyColumnsAndExposesTheNewOptionAndClaim() {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("DrivePreferences", 0)
                .edit()
                .putBoolean("pauseMediaOnDoor", true)
                .putBoolean("passengerAutoStart", true)
                .putBoolean("mediaAutoStartLastPlayer", true)
                .putBoolean(DoorMediaSettings.PLAY_ON_DOOR, true)
                .commit();
        Settings.Global.putInt(app.getContentResolver(), "boot_count", 42);
        SettingsContentProvider provider =
                Robolectric.buildContentProvider(SettingsContentProvider.class).create().get();
        try (Cursor cursor =
                provider.query(
                        Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                        null,
                        null,
                        null,
                        null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(18, cursor.getColumnIndex("pauseMediaOnDoor"));
            assertEquals(1, cursor.getInt(18));
            assertEquals(41, cursor.getColumnIndex("passengerAutoStart"));
            assertEquals(1, cursor.getInt(41));
            assertEquals(42, cursor.getColumnIndex("mediaAutoStartLastPlayer"));
            assertEquals(1, cursor.getInt(42));
            assertEquals(43, cursor.getColumnIndex(DoorMediaSettings.PLAY_ON_DOOR));
            assertEquals(1, cursor.getInt(43));
        }
        Bundle extras = new Bundle();
        extras.putInt("acc", 2);
        provider.call("driveHookV2", "acc", extras);
        assertTrue(
                provider.call("driveHookV2", DoorMediaSettings.CLAIM_PLAY, null)
                        .getBoolean("claimed"));
        assertFalse(
                provider.call("driveHookV2", DoorMediaSettings.CLAIM_PLAY, null)
                        .getBoolean("claimed"));
    }

    @Test
    public void concurrentOpenRequestsHaveOneWinner() throws Exception {
        acc(2, 10);
        java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Boolean> first = workers.submit(() -> claim(10));
            java.util.concurrent.Future<Boolean> second = workers.submit(() -> claim(10));
            assertNotEquals(first.get(), second.get());
        } finally {
            workers.shutdownNow();
        }
    }

    private void acc(int state, int boot) {
        Bundle extras = new Bundle();
        extras.putInt("acc", state);
        DriveSelectionPreferences.hook(preferences, "acc", extras, boot);
    }

    private boolean claim(int boot) {
        Bundle result =
                DriveSelectionPreferences.hook(
                        preferences, DoorMediaSettings.CLAIM_PLAY, null, boot);
        assertEquals(2, result.getInt("protocol"));
        return result.getBoolean("claimed");
    }
}
