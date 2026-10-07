package ru.big.town.restoremode.scenarios;

import android.content.SharedPreferences;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.controls.SettingsToggle;
import ru.big.town.restoremode.settings.ui.layout.SettingsRow;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.List;
import java.util.function.Consumer;

/**
 * Всплывающий список сценариев для карточки главного экрана.
 *
 * <p>Название запускает сценарий, чекбокс включает и выключает его. Кнопка «Настроить» открывает
 * раздел «Сценарии», где редактируются триггеры, условия и действия.
 */
public final class ScenarioQuickList {
    private ScenarioQuickList() {}

    public static void show(
            AppCompatActivity activity,
            SharedPreferences prefs,
            Consumer<ScenarioStore.Scenario> onRun,
            Runnable onChanged,
            Runnable onConfigure) {
        List<ScenarioStore.Scenario> scenarios = ScenarioStore.load(prefs);
        if (scenarios.isEmpty()) {
            // Нет сценариев — вести пользователя в список нечего, сразу открываем настройки.
            onConfigure.run();
            return;
        }
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 20);
        content.setPadding(padding, padding / 2, padding, padding / 2);
        TextView hint = label(activity, "Нажмите название, чтобы запустить сценарий.");
        hint.setTextColor(0xff97a6bc);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        content.addView(hint);

        androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                activity, R.style.SettingsDialog)
                        .setTitle("Сценарии")
                        .setView(scroll(activity, content))
                        .setNeutralButton("Настроить", (d, which) -> onConfigure.run())
                        .setNegativeButton("Закрыть", null)
                        .create();

        for (ScenarioStore.Scenario scenario : scenarios) {
            content.addView(row(activity, prefs, scenario, dialog, onRun, onChanged));
        }
        SettingsDesign.styleTree(content);
        dialog.show();
    }

    private static View row(
            AppCompatActivity activity,
            SharedPreferences prefs,
            ScenarioStore.Scenario scenario,
            androidx.appcompat.app.AlertDialog dialog,
            Consumer<ScenarioStore.Scenario> onRun,
            Runnable onChanged) {
        LinearLayout row = new SettingsRow(activity);
        row.setPadding(0, dp(activity, 14), 0, dp(activity, 14));
        row.setGravity(Gravity.CENTER_VERTICAL);

        Switch enabled = new SettingsToggle(activity, null);
        enabled.setContentDescription("Включить сценарий");
        enabled.setChecked(scenario.enabled);
        LinearLayout.LayoutParams toggle =
                new LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 32));
        toggle.rightMargin = dp(activity, 20);
        row.addView(enabled, toggle);

        TextView name = label(activity, scenario.name.isEmpty() ? "Сценарий" : scenario.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        name.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
        name.setContentDescription("Запустить сценарий");
        applyEnabledColour(name, scenario.enabled);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

        enabled.setOnCheckedChangeListener(
                (button, checked) -> {
                    scenario.enabled = checked;
                    ScenarioStore.update(prefs, scenario);
                    applyEnabledColour(name, checked);
                    onChanged.run();
                });
        name.setOnClickListener(
                v -> {
                    if (!scenario.enabled) {
                        Toast.makeText(activity, "Сценарий выключен", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    onRun.accept(scenario);
                });
        return row;
    }

    private static void applyEnabledColour(TextView view, boolean enabled) {
        view.setTextColor(enabled ? 0xfff3f5fa : 0xff97a6bc);
    }

    private static ScrollView scroll(AppCompatActivity activity, View content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        return scroll;
    }

    private static TextView label(AppCompatActivity activity, String value) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(0xfff3f5fa);
        return view;
    }

    private static int dp(AppCompatActivity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
