package ru.big.town.restoremode.apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import ru.big.town.common.ApplicationStartupSettings;
import ru.big.town.restoremode.apps.ApplicationStartupStore.Entry;
import ru.big.town.restoremode.apps.split.SplitStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class ApplicationStartupStoreTest {
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        preferences =
                RuntimeEnvironment.getApplication()
                        .getSharedPreferences("startup-test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void persistsOrderAndPublishesEnabledDelayAndActions() {
        ApplicationStartupStore.save(
                preferences,
                Arrays.asList(new Entry("app:first", "Первое"), new Entry("app:second", "Второе")));
        preferences
                .edit()
                .putBoolean(ApplicationStartupSettings.ENABLED, true)
                .putInt(ApplicationStartupSettings.DELAY, 8)
                .commit();
        List<Entry> loaded = ApplicationStartupStore.load(preferences);
        assertEquals("Первое", loaded.get(0).label);
        try (Cursor cursor = ApplicationStartupStore.query(preferences)) {
            assertEquals(2, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("enabled")));
            assertEquals(8, cursor.getInt(cursor.getColumnIndexOrThrow("delaySeconds")));
            assertEquals("app:first", cursor.getString(cursor.getColumnIndexOrThrow("action")));
            assertTrue(cursor.moveToNext());
            assertEquals("app:second", cursor.getString(cursor.getColumnIndexOrThrow("action")));
        }
    }

    @Test
    public void splitIdentitySurvivesDeletionBeforeItAndUsesCurrentSettings() {
        SplitStore.Preset unrelated = new SplitStore.Preset();
        SplitStore.Preset selected = new SplitStore.Preset();
        selected.l = "left.app";
        selected.r = "right.app";
        SplitStore.save(preferences, Arrays.asList(unrelated, selected));
        Entry entry = new Entry("split:" + selected.id, "Сплит");
        String original = ApplicationStartupStore.resolve(entry, preferences);
        SplitStore.save(preferences, Collections.singletonList(selected));
        assertEquals(original, ApplicationStartupStore.resolve(entry, preferences));
        selected.r = "changed.app";
        SplitStore.save(preferences, Collections.singletonList(selected));
        assertTrue(
                ApplicationStartupStore.resolve(entry, preferences)
                        .startsWith("split:left.app,changed.app,"));
        SplitStore.save(preferences, Collections.emptyList());
        assertEquals("", ApplicationStartupStore.resolve(entry, preferences));
    }

    @Test
    public void defaultsAreDisabledAndMalformedEntriesDoNotLaunch() {
        preferences.edit().putString(ApplicationStartupSettings.ENTRIES, "broken json").commit();
        assertTrue(ApplicationStartupStore.load(preferences).isEmpty());
        try (Cursor cursor = ApplicationStartupStore.query(preferences)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("enabled")));
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("action")));
        }
        preferences
                .edit()
                .putString(
                        ApplicationStartupSettings.ENTRIES,
                        "[{\"target\":\"energy:ev\"},{\"target\":\"app:\"}]")
                .commit();
        assertTrue(ApplicationStartupStore.load(preferences).isEmpty());
    }

    @Test
    public void delayIsBoundedAndDeletedSplitIsSkipped() {
        preferences.edit().putInt(ApplicationStartupSettings.DELAY, -5).commit();
        assertEquals(0, ApplicationStartupStore.delaySeconds(preferences));
        preferences.edit().putInt(ApplicationStartupSettings.DELAY, Integer.MAX_VALUE).commit();
        assertEquals(60, ApplicationStartupStore.delaySeconds(preferences));
        ApplicationStartupStore.save(
                preferences,
                Arrays.asList(
                        new Entry("split:deleted", "Удалён"),
                        new Entry("app:remaining", "Осталось")));
        try (Cursor cursor = ApplicationStartupStore.query(preferences)) {
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            assertEquals("app:remaining", cursor.getString(cursor.getColumnIndexOrThrow("action")));
        }
    }
}
