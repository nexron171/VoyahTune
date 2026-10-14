package ru.big.town.restoremode.settings.sections.vehicle;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ru.big.town.common.ScreenBrightnessSettings;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.integration.config.SplitConfigSync;
import ru.big.town.restoremode.settings.ui.controls.SettingsNumberPicker;
import ru.big.town.restoremode.settings.ui.controls.SettingsToggle;
import ru.big.town.restoremode.settings.ui.layout.SettingsComponents;
import ru.big.town.restoremode.settings.ui.layout.SettingsFlow;
import ru.big.town.restoremode.settings.ui.layout.SettingsPanels;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Each rule is a separate virtualized row, independent of the size of the saved list. */
final class ScreenBrightnessSettingsPage {
    private final Activity activity;
    private final SharedPreferences preferences;
    private final Runnable changed;
    private final SettingsComponents components;
    private TextView statusView;
    private int sensor = -1;
    private String status = "Ожидание подключения к Native";

    ScreenBrightnessSettingsPage(
            Activity activity, SharedPreferences preferences, Runnable changed) {
        this.activity = activity;
        this.preferences = preferences;
        this.changed = changed;
        components = new SettingsComponents(activity);
    }

    void addRows(List<SettingsList.Row> rows) {
        rows.add(
                new SettingsList.Row(
                        "brightness:heading", parent -> components.heading("Яркость экрана")));
        rows.add(
                new SettingsList.Row(
                        "brightness:toggle",
                        parent -> {
                            SettingsToggle toggle = new SettingsToggle(activity, null);
                            toggle.setChecked(enabled());
                            toggle.setOnCheckedChangeListener(
                                    (button, checked) -> {
                                        preferences
                                                .edit()
                                                .putBoolean(
                                                        ScreenBrightnessSettings.ENABLED, checked)
                                                .apply();
                                        status =
                                                checked
                                                        ? "Ожидание подключения к Native"
                                                        : "Выключено";
                                        saveChanged();
                                    });
                            statusView = components.text(statusText(), 17, 0xff9aaac0);
                            LinearLayout card =
                                    components.card(
                                            components.row(
                                                    "Регулировать яркость экрана",
                                                    "Все экраны следуют датчику освещённости"
                                                            + " (0–7).",
                                                    toggle),
                                            statusView,
                                            components.text(
                                                    "Правила проверяются сверху вниз. Применяется"
                                                        + " первое совпадение. Если совпадений нет,"
                                                        + " яркость сохраняется.",
                                                    17,
                                                    0xff9aaac0));
                            SettingsDesign.bindRow(activity, card);
                            return SettingsPanels.spaced(card);
                        }));
        List<ScreenBrightnessSettings.Rule> rules = load();
        for (int index = 0; index < rules.size(); index++) {
            ScreenBrightnessSettings.Rule rule = rules.get(index);
            final int position = index;
            rows.add(
                    new SettingsList.Row(
                            "brightness:rule:" + rule.id,
                            parent -> {
                                SettingsFlow actions = new SettingsFlow(activity, null);
                                actions.addView(button("Изменить", () -> edit(rule)));
                                Button up = button("Выше", () -> move(rule.id, -1));
                                up.setEnabled(position > 0);
                                actions.addView(up);
                                Button down = button("Ниже", () -> move(rule.id, 1));
                                down.setEnabled(position + 1 < rules.size());
                                actions.addView(down);
                                actions.addView(button("Удалить", () -> delete(rule.id)));
                                LinearLayout card =
                                        components.card(
                                                components.text(
                                                        (position + 1) + ". " + rule.label(),
                                                        22,
                                                        0xfff3f5fa),
                                                actions);
                                SettingsDesign.bindRow(activity, card);
                                return SettingsPanels.spaced(card);
                            }));
        }
        rows.add(
                new SettingsList.Row(
                        "brightness:add",
                        parent -> {
                            Button add = button("Добавить правило", () -> edit(null));
                            SettingsDesign.bindRow(activity, add);
                            return add;
                        }));
    }

    void release(View row) {
        if (statusView != null && contains(row, statusView)) statusView = null;
    }

    private static boolean contains(View row, View child) {
        if (row == child) return true;
        for (android.view.ViewParent parent = child.getParent();
                parent instanceof View;
                parent = parent.getParent()) {
            if (parent == row) return true;
        }
        return false;
    }

    void update(Intent intent) {
        sensor = intent.getIntExtra("sensorLevel", -1);
        String message = intent.getStringExtra("status");
        if (message != null) status = message;
        if (statusView != null) statusView.setText(statusText());
    }

    private String statusText() {
        if (!enabled()) return "Выключено";
        return "Датчик: " + (sensor >= 0 && sensor <= 7 ? sensor : "—") + " · " + status;
    }

    private boolean enabled() {
        return preferences.getBoolean(ScreenBrightnessSettings.ENABLED, false);
    }

    private List<ScreenBrightnessSettings.Rule> load() {
        return new ArrayList<>(
                ScreenBrightnessSettings.decode(
                        preferences.getString(
                                ScreenBrightnessSettings.RULES,
                                ScreenBrightnessSettings.DEFAULT_RULES)));
    }

    private Button button(String title, Runnable action) {
        Button button = new Button(activity);
        button.setText(title);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void edit(ScreenBrightnessSettings.Rule existing) {
        Spinner operator = new Spinner(activity);
        operator.setAdapter(
                new ArrayAdapter<>(
                        activity,
                        android.R.layout.simple_spinner_dropdown_item,
                        ScreenBrightnessSettings.SYMBOLS));
        if (existing != null) {
            for (int index = 0; index < ScreenBrightnessSettings.OPERATORS.length; index++) {
                if (ScreenBrightnessSettings.OPERATORS[index].equals(existing.operator)) {
                    operator.setSelection(index);
                }
            }
        }
        SettingsNumberPicker threshold = new SettingsNumberPicker(activity, null);
        threshold.setContentDescription("Порог датчика");
        threshold.setMinValue(0);
        threshold.setMaxValue(7);
        threshold.setValue(existing == null ? 3 : existing.threshold);
        SettingsNumberPicker percent = new SettingsNumberPicker(activity, null);
        percent.setContentDescription("Яркость экранов, %");
        percent.setMinValue(1);
        percent.setMaxValue(100);
        percent.setValue(existing == null ? 80 : existing.percent);
        LinearLayout form =
                components.card(
                        components.row("Условие", "Сравнение с показанием датчика", operator),
                        components.row("Порог датчика", "От 0 до 7", threshold),
                        components.row("Яркость экранов", "От 1 до 100%", percent));
        new MaterialAlertDialogBuilder(activity, R.style.SettingsDialog)
                .setTitle(existing == null ? "Добавить правило" : "Изменить правило")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton(
                        "Сохранить",
                        (dialog, which) -> {
                            List<ScreenBrightnessSettings.Rule> rules = load();
                            ScreenBrightnessSettings.Rule replacement =
                                    new ScreenBrightnessSettings.Rule(
                                            existing == null
                                                    ? UUID.randomUUID().toString()
                                                    : existing.id,
                                            ScreenBrightnessSettings.OPERATORS[
                                                    operator.getSelectedItemPosition()],
                                            threshold.getValue(),
                                            percent.getValue());
                            if (existing == null) rules.add(replacement);
                            else {
                                for (int index = 0; index < rules.size(); index++) {
                                    if (rules.get(index).id.equals(existing.id))
                                        rules.set(index, replacement);
                                }
                            }
                            save(rules);
                        })
                .show();
    }

    private void move(String id, int offset) {
        List<ScreenBrightnessSettings.Rule> rules = load();
        for (int index = 0; index < rules.size(); index++) {
            if (rules.get(index).id.equals(id)) {
                int destination = index + offset;
                if (destination >= 0 && destination < rules.size()) {
                    Collections.swap(rules, index, destination);
                    save(rules);
                }
                return;
            }
        }
    }

    private void delete(String id) {
        List<ScreenBrightnessSettings.Rule> rules = load();
        rules.removeIf(rule -> rule.id.equals(id));
        save(rules);
    }

    private void save(List<ScreenBrightnessSettings.Rule> rules) {
        preferences
                .edit()
                .putString(ScreenBrightnessSettings.RULES, ScreenBrightnessSettings.encode(rules))
                .apply();
        saveChanged();
    }

    private void saveChanged() {
        SplitConfigSync.pushScreenBrightness(activity, preferences);
        changed.run();
    }
}
