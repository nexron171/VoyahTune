package ru.big.town.restoremode.integration.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.common.DockIconStyle;
import ru.big.town.common.InfrastructureProfile;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.dialogs.DockIconPicker;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class DockIconConfigTest {
    private Application context;
    private SharedPreferences preferences;
    private Field cachedProfile;
    private Object previousProfile;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("dock-icons-test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        cachedProfile = InfrastructureProfile.class.getDeclaredField("cached");
        cachedProfile.setAccessible(true);
        previousProfile = cachedProfile.get(null);
        cachedProfile.set(null, InfrastructureProfile.OD);
    }

    @After
    public void restoreProfile() throws Exception {
        cachedProfile.set(null, previousProfile);
    }

    @Test
    public void publishesIndependentChoicesAndExplicitReset() {
        preferences
                .edit()
                .putString("dockOverride1", "same.app")
                .putString("dockOverride2", "same.app")
                .putString("dockOverride1Icon", "navigation")
                .putString("dockOverride2Icon", "music")
                .commit();
        Intent config = publish();
        assertEquals("navigation", config.getStringExtra("dock1Icon"));
        assertEquals("music", config.getStringExtra("dock2Icon"));
        assertEquals(
                "ru.big.town.anative.SetModesConfigReceiver", config.getComponent().getClassName());

        preferences.edit().remove("dockOverride1Icon").commit();
        config = publish();
        assertEquals("default", config.getStringExtra("dock1Icon"));
        assertEquals("music", config.getStringExtra("dock2Icon"));
    }

    @Test
    public void unknownChoiceFallsBackToDefault() {
        preferences.edit().putString("dockOverride1Icon", "unknown").commit();
        assertEquals("default", publish().getStringExtra("dock1Icon"));
    }

    @Test
    public void piDoesNotPublishIconOverrides() throws Exception {
        cachedProfile.set(null, InfrastructureProfile.PI);
        preferences.edit().putString("dockOverride1Icon", "music").commit();
        Intent config = publish();
        assertFalse(config.hasExtra("dock1Icon"));
        assertFalse(config.hasExtra("dock2Icon"));
    }

    @Test
    public void pickerShowsPreviewsAndReturnsEachSelectedStyle() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.get().setTheme(R.style.Theme_RestoreMode_Settings);
            Activity activity = controller.setup().get();
            for (DockIconStyle style : DockIconStyle.values()) {
                AtomicReference<DockIconStyle> selected = new AtomicReference<>();
                DockIconPicker.show(activity, 1, CarPlayTarget.ID, style, selected::set);
                AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                ListView choices = dialog.getListView();
                assertEquals(3, choices.getCount());
                assertEquals(style.ordinal(), choices.getCheckedItemPosition());
                TextView row =
                        (TextView) choices.getAdapter().getView(style.ordinal(), null, choices);
                assertNotNull(row.getCompoundDrawablesRelative()[0]);
                choices.performItemClick(row, style.ordinal(), style.ordinal());
                assertEquals(style, selected.get());
                assertFalse(dialog.isShowing());
            }
        }
    }

    private Intent publish() {
        SplitConfigSync.pushDock(context, preferences);
        List<Intent> broadcasts = shadowOf(context).getBroadcastIntents();
        return broadcasts.get(broadcasts.size() - 1);
    }
}
