package ru.big.town.restoremode.settings.sections.scenarios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import ru.big.town.common.ScenarioProtocol;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.scenarios.ScenarioStore;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.ui.list.SettingsList;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class, qualifiers = "ru-w1280dp-h720dp-land")
public class ScenarioWallpaperEditorTest {
    @Test
    public void editorPersistsDriveModeEventAndWallpaperActionWithoutConditions() {
        var controller = Robolectric.buildActivity(AppCompatActivity.class);
        AppCompatActivity activity = controller.get();
        activity.setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup().visible();
        try {
            SharedPreferences preferences = activity.getSharedPreferences("wallpaper-scenario-editor", 0);
            preferences.edit().clear().commit();
            ScenarioStore.Scenario scenario = ScenarioStore.create(preferences, "Обои");
            preferences.edit().putString("scenarioExpandedId", scenario.id).commit();
            SettingsList list = new SettingsList(activity, null);
            list.setTag(SettingsSection.SCENARIOS);
            activity.setContentView(list);
            ScenarioSettingsPage page = new ScenarioSettingsPage(activity, list, preferences, () -> {});
            page.refresh();
            show(list, scenario.id + ":rules");
            click(list, "Режим движения");
            dialog().getListView().performItemClick(
                    null, ScenarioProtocol.DRIVE_MODES.indexOf("SPORT"), 0);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            show(list, scenario.id + ":actions");
            click(list, "Добавить действие");
            click(dialog().getWindow().getDecorView(), "Оформление");
            click(dialog().getWindow().getDecorView(), "Сменить обои");
            click(dialog().getWindow().getDecorView(), "Бургунди");
            ScenarioStore.Scenario saved = ScenarioStore.find(preferences, scenario.id);
            assertNotNull(saved);
            assertEquals(ScenarioProtocol.TRIGGER_DRIVE_MODE, saved.triggers.get(0).type);
            assertEquals("SPORT", saved.triggers.get(0).value);
            assertEquals(0, saved.conditions.size());
            assertEquals("wallpaper:burgundy", saved.steps.get(0).value);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static androidx.appcompat.app.AlertDialog dialog() {
        return (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
    }

    private static void show(SettingsList list, String key) {
        list.scrollToKey(key);
        list.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1280, 1200);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static void click(View root, String label) {
        View target = find(root, label);
        assertNotNull(label, target);
        target.performClick();
    }

    private static View find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = find(group.getChildAt(index), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
