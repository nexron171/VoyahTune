package ru.big.town.restoremode.settings.sections.scenarios;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.scenarios.ScenarioStore;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.ui.list.SettingsList;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class, qualifiers = "ru-w1280dp-h720dp-land")
public class ScenarioRenameTest {
    @Test
    public void renameFocusedEditorAndRebindPreservesScenario() {
        var controller = Robolectric.buildActivity(AppCompatActivity.class);
        AppCompatActivity activity = controller.get();
        activity.setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup().visible();
        try {
            SharedPreferences preferences = activity.getSharedPreferences("rename", 0);
            ScenarioStore.Scenario scenario = ScenarioStore.create(preferences, "Утро");
            ScenarioStore.Step step = new ScenarioStore.Step();
            step.value = "headlights:on";
            scenario.steps.add(step);
            ScenarioStore.update(preferences, scenario);
            preferences.edit().putString("scenarioExpandedId", scenario.id).apply();
            SettingsList list = new SettingsList(activity, null);
            list.setTag(SettingsSection.SCENARIOS);
            activity.setContentView(list);
            ScenarioSettingsPage page =
                    new ScenarioSettingsPage(activity, list, preferences, () -> {});
            page.refresh();
            layout(list);
            EditText input = (EditText) find(list, "Название сценария");
            assertNotNull(input);
            input.requestFocus();
            ScenarioStore.create(preferences, "Ночь");
            for (String invalid : new String[] {"", "Ночь", "если дождь"}) {
                input.setText(invalid);
                assertTrue(find(list, "Сохранить").performClick());
                assertEquals("Утро", ScenarioStore.find(preferences, scenario.id).name);
            }
            input.setText("Вечер");
            assertTrue(find(list, "Сохранить").performClick());
            layout(list);
            ScenarioStore.Scenario saved = ScenarioStore.find(preferences, scenario.id);
            assertEquals("Вечер", saved.name);
            assertEquals("headlights:on", saved.steps.get(0).value);
            assertEquals(
                    "Вечер", ((EditText) find(list, "Название сценария")).getText().toString());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static void layout(View view) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1280, 1200);
    }

    private static View find(View view, String label) {
        if (label.contentEquals(
                view.getContentDescription() == null ? "" : view.getContentDescription()))
            return view;
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText()))
            return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
