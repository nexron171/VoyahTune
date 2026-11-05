package ru.big.town.restoremode.apps.split;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class SplitHomeShortcutTest {
    @Test
    public void newSplitIsHiddenUntilEnabledAndCanBeHiddenAgain() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("split-home", 0);
        preferences.edit().clear().commit();
        SplitStore.Preset preset = new SplitStore.Preset();
        preset.l = "left.app";
        preset.r = "right.app";
        SplitStore.save(preferences, Collections.singletonList(preset));
        assertFalse(SplitStore.load(preferences).get(0).showOnHome);
        assertTrue(SplitStore.load(preferences).get(0).ready());
        assertEquals(0, shortcuts(context, preferences));
        preset.showOnHome = true;
        SplitStore.save(preferences, Collections.singletonList(preset));
        assertEquals(1, shortcuts(context, preferences));
        preset.showOnHome = false;
        SplitStore.save(preferences, Collections.singletonList(preset));
        assertEquals(0, shortcuts(context, preferences));
        assertEquals(1, SplitStore.load(preferences).size());
    }

    @Test
    public void existingPresetsKeepTheirShortcutsDuringMigration() {
        SharedPreferences preferences =
                RuntimeEnvironment.getApplication().getSharedPreferences("split-legacy", 0);
        preferences
                .edit()
                .clear()
                .putString(
                        SplitStore.KEY, "[{\"id\":\"old\",\"l\":\"left.app\",\"r\":\"right.app\"}]")
                .commit();
        assertTrue(SplitStore.load(preferences).get(0).showOnHome);
    }

    private static long shortcuts(Context context, SharedPreferences preferences) {
        TileOrderStore.sync(preferences, context.getPackageManager());
        return TileOrderStore.load(preferences).stream()
                .filter(tile -> TileOrderStore.Tile.TYPE_SPLIT.equals(tile.type))
                .count();
    }
}
