package ru.big.town.restoremode.integration.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.stream.Collectors;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class SystemThemeSyncTest {
    private Application context;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void startupAndWakePublishSavedThemeWithoutBoundService() {
        for (int mode : new int[] {2, 1, 0}) {
            preferences.edit().putInt("themeOverride", mode).commit();
            new SavedSettingsSyncReceiver()
                    .onReceive(
                            context,
                            new Intent(SavedSettingsSyncReceiver.ACTION)
                                    .setClass(context, SavedSettingsSyncReceiver.class));
            List<Intent> broadcasts = themeBroadcasts();
            assertEquals(1, broadcasts.size());
            Intent broadcast = broadcasts.get(0);
            assertEquals(mode, broadcast.getIntExtra("themeOverride", -1));
            assertNotNull(broadcast.getComponent());
            assertEquals("ru.big.town.anative", broadcast.getComponent().getPackageName());
            assertEquals(
                    "ru.big.town.anative.SetModesConfigReceiver",
                    broadcast.getComponent().getClassName());
            shadowOf(context).clearBroadcastIntents();
        }
    }

    @Test
    public void absentChoiceLeavesOemThemeAlone() {
        SplitConfigSync.pushAll(context, preferences);
        assertTrue(themeBroadcasts().isEmpty());
    }

    private List<Intent> themeBroadcasts() {
        return shadowOf(context).getBroadcastIntents().stream()
                .filter(intent -> "ru.big.town.anative.THEME_CONFIG".equals(intent.getAction()))
                .collect(Collectors.toList());
    }
}
