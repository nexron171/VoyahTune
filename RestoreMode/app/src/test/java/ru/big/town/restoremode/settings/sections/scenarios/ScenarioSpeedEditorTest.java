package ru.big.town.restoremode.settings.sections.scenarios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.NumberPicker;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.scenarios.ScenarioStore;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.ui.list.SettingsList;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class, qualifiers = "ru-w1280dp-h720dp-land")
public class ScenarioSpeedEditorTest {
    @Test
    public void editorSavesSpeedTriggersAndConditionsWithFiveKmhSteps() {
        var controller = Robolectric.buildActivity(AppCompatActivity.class);
        AppCompatActivity activity = controller.get();
        activity.setTheme(R.style.Theme_RestoreMode_Settings);
        controller.setup().visible();
        try {
            SharedPreferences preferences = activity.getSharedPreferences("speed-scenario-editor", 0);
            preferences.edit().clear().commit();
            ScenarioStore.Scenario scenario = ScenarioStore.create(preferences, "Скорость");
            assertNotNull(scenario);
            preferences.edit().putString("scenarioExpandedId", scenario.id).commit();
            SettingsList list = new SettingsList(activity, null);
            list.setTag(SettingsSection.SCENARIOS);
            activity.setContentView(list);
            ScenarioSettingsPage page = new ScenarioSettingsPage(activity, list, preferences, () -> {});
            page.refresh();

            int[] thresholds = {0, 5, 190};
            String[] operators = {"le", "ge", "eq"};
            for (int index = 0; index < thresholds.length; index++) {
                for (int section = 0; section < 2; section++) {
                    showRules(list, scenario.id);
                    List<View> buttons = speedButtons(list);
                    assertEquals(2, buttons.size());
                    buttons.get(section).performClick();
                    dialog().getListView().performItemClick(null, index, 0);
                    NumberPicker picker = findPicker(dialog().getWindow().getDecorView());
                    assertNotNull(picker);
                    assertEquals(0, picker.getMinValue());
                    assertEquals(38, picker.getMaxValue());
                    picker.setValue(thresholds[index] / 5);
                    dialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                }
            }

            ScenarioStore.Scenario saved = ScenarioStore.find(preferences, scenario.id);
            assertNotNull(saved);
            assertEquals(3, saved.triggers.size());
            assertEquals(3, saved.conditions.size());
            for (int index = 0; index < thresholds.length; index++) {
                assertEquals("speed", saved.triggers.get(index).type);
                assertEquals(String.valueOf(thresholds[index]), saved.triggers.get(index).value);
                assertEquals(operators[index], saved.triggers.get(index).edge);
                assertEquals("speed", saved.conditions.get(index).type);
                assertEquals(thresholds[index], saved.conditions.get(index).value);
                assertEquals(operators[index], saved.conditions.get(index).op);
            }
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private static AlertDialog dialog() {
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static void showRules(SettingsList list, String scenarioId) {
        list.scrollToKey(scenarioId + ":rules");
        list.measure(
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1280, 1200);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static List<View> speedButtons(View view) {
        List<View> result = new ArrayList<>();
        if (view instanceof MaterialButton
                && "Скорость".contentEquals(((MaterialButton) view).getText())) {
            result.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                result.addAll(speedButtons(group.getChildAt(index)));
            }
        }
        return result;
    }

    private static NumberPicker findPicker(View view) {
        if (view instanceof NumberPicker) return (NumberPicker) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                NumberPicker picker = findPicker(group.getChildAt(index));
                if (picker != null) return picker;
            }
        }
        return null;
    }
}
