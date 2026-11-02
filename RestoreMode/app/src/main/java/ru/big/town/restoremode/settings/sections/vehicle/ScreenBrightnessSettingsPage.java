package ru.big.town.restoremode.settings.sections.vehicle;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ru.big.town.common.ScreenBrightnessSettings;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.integration.config.SplitConfigSync;
import ru.big.town.restoremode.settings.ui.controls.SettingsToggle;
import ru.big.town.restoremode.settings.ui.layout.SettingsComponents;
import ru.big.town.restoremode.settings.ui.layout.SettingsPanels;
import ru.big.town.restoremode.settings.ui.layout.SettingsRow;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.ArrayList;
import java.util.List;

/** One editable brightness percentage for each ambient-light level. */
final class ScreenBrightnessSettingsPage {
    private static final int[] SENSOR_LEVELS = {0, 1, 2, 3, 4, 5, 6, 7};
    private static final int[] PERCENTAGES = percentages();
    private final Activity activity;
    private final SharedPreferences preferences;
    private final Runnable changed;
    private final SettingsComponents components;
    private TextView statusView;
    private int sensor = -1;
    private String status = "Ожидание подключения к Native";
    private String displayedRules;

    ScreenBrightnessSettingsPage(Activity activity, SharedPreferences preferences, Runnable changed) {
        this.activity = activity;
        this.preferences = preferences;
        this.changed = changed;
        components = new SettingsComponents(activity);
        displayedRules = encoded();
    }

    void addRows(List<SettingsList.Row> rows) {
        displayedRules = encoded();
        rows.add(new SettingsList.Row("brightness:heading", parent -> createCard()));
    }

    private View createCard() {
        List<View> contents = new ArrayList<>();
        TextView heading = components.text("Яркость экрана", 27, 0xfff3f5fa);
        heading.setPadding(0, 0, 0, components.dp(16));
        contents.add(heading);
        contents.add(components.row(
                "Регулировать яркость экрана",
                "Все экраны следуют датчику освещённости (0–7).",
                toggle(ScreenBrightnessSettings.ENABLED, false)));
        statusView = components.text(statusText(), 17, 0xff9aaac0);
        contents.add(statusView);
        contents.add(components.row(
                "Адаптивная яркость",
                "При включении этой настройки меняйте яркость экрана до комфортного уровня и VoyahTune автоматически запомнит эту яркость для текущей обстановки",
                toggle(ScreenBrightnessSettings.ADAPTIVE, true)));
        contents.add(components.row(
                "Ночной режим если яркость ниже или равна значению:",
                "Уровень датчика освещённости (0–7)",
                nightThreshold()));
        for (ScreenBrightnessSettings.Rule rule : ScreenBrightnessSettings.levels(encoded())) {
            contents.add(levelRow(rule));
        }
        Button add = button("Добавить уровень", () -> edit(-1, 80));
        LinearLayout footer = components.column(add);
        footer.setPadding(0, components.dp(18), 0, 0);
        contents.add(footer);
        return card(contents.toArray(new View[0]));
    }

    private Spinner nightThreshold() {
        Spinner threshold = new Spinner(activity);
        threshold.setAdapter(new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                new String[] {"0", "1", "2", "3", "4", "5", "6", "7"}));
        int saved = preferences.getInt(
                ScreenBrightnessSettings.NIGHT_THRESHOLD,
                ScreenBrightnessSettings.nightThreshold(encoded()));
        threshold.setSelection(saved);
        threshold.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    android.widget.AdapterView<?> parent, View view, int position, long id) {
                int current = preferences.getInt(
                        ScreenBrightnessSettings.NIGHT_THRESHOLD,
                        ScreenBrightnessSettings.nightThreshold(encoded()));
                if (position != current) {
                    preferences.edit()
                            .putInt(ScreenBrightnessSettings.NIGHT_THRESHOLD, position)
                            .apply();
                    saveChanged();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        return threshold;
    }

    private View levelRow(ScreenBrightnessSettings.Rule rule) {
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(
                button("Изменить", () -> edit(rule.threshold, rule.percent)),
                new LinearLayout.LayoutParams(-2, -2));
        Button delete = button("Удалить", () -> {
            List<ScreenBrightnessSettings.Rule> levels =
                    new ArrayList<>(ScreenBrightnessSettings.levels(encoded()));
            levels.removeIf(level -> level.threshold == rule.threshold);
            save(ScreenBrightnessSettings.encode(levels));
        });
        LinearLayout.LayoutParams deleteLayout = new LinearLayout.LayoutParams(-2, -2);
        deleteLayout.leftMargin = components.dp(16);
        actions.addView(delete, deleteLayout);

        SettingsRow row = new SettingsRow(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = components.text(
                "Датчик " + rule.threshold + " → " + rule.percent + "%", 22, 0xfff3f5fa);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams actionLayout = new LinearLayout.LayoutParams(-2, -2);
        actionLayout.leftMargin = components.dp(20);
        row.addView(actions, actionLayout);
        return row;
    }

    private SettingsToggle toggle(String key, boolean defaultValue) {
        SettingsToggle toggle = new SettingsToggle(activity, null);
        toggle.setChecked(preferences.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            saveChanged();
        });
        return toggle;
    }

    private View card(View... contents) {
        LinearLayout card = components.card(contents);
        SettingsDesign.bindRow(activity, card);
        return SettingsPanels.spaced(card);
    }

    void release(View row) {
        if (statusView == null) return;
        for (android.view.ViewParent parent = statusView.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent == row) {
                statusView = null;
                return;
            }
        }
    }

    void update(Intent intent) {
        sensor = intent.getIntExtra("sensorLevel", -1);
        status = intent.getStringExtra("status");
        if (status == null) status = "Ожидание подключения к Native";
        if (statusView != null) statusView.setText(statusText());
        if (!encoded().equals(displayedRules)) {
            displayedRules = encoded();
            changed.run();
        }
    }

    private String statusText() {
        if (!preferences.getBoolean(ScreenBrightnessSettings.ENABLED, false)) return "Выключено";
        return "Датчик: " + (sensor >= 0 && sensor <= 7 ? sensor : "—") + " · " + status;
    }

    private String encoded() {
        return preferences.getString(ScreenBrightnessSettings.RULES, ScreenBrightnessSettings.DEFAULT_RULES);
    }

    private Button button(String title, Runnable action) {
        Button button = new Button(activity);
        button.setText(title);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void edit(int existingLevel, int initialPercent) {
        RuleSlider level = new RuleSlider("Уровень датчика", "От 0 до 7 · шаг 1", "", SENSOR_LEVELS,
                existingLevel < 0 ? 0 : existingLevel);
        RuleSlider percent = new RuleSlider("Яркость экранов", "От 1 до 100% · шаг 1", "%", PERCENTAGES, initialPercent);
        new MaterialAlertDialogBuilder(activity, R.style.SettingsDialog)
                .setTitle(existingLevel < 0 ? "Добавить уровень" : "Изменить яркость")
                .setView(components.card(level, percent))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    List<ScreenBrightnessSettings.Rule> levels = new ArrayList<>(ScreenBrightnessSettings.levels(encoded()));
                    if (existingLevel >= 0) levels.removeIf(rule -> rule.threshold == existingLevel);
                    save(ScreenBrightnessSettings.calibrate(ScreenBrightnessSettings.encode(levels), level.getValue(), percent.getValue()));
                }).show();
    }

    private static int[] percentages() {
        int[] values = new int[100];
        for (int index = 0; index < values.length; index++) values[index] = index + 1;
        return values;
    }

    private final class RuleSlider extends LinearLayout {
        private int value;

        RuleSlider(
                String title, String description, String suffix, int[] choices, int initialValue) {
            super(activity);
            setOrientation(VERTICAL);
            value = initialValue;
            TextView label = components.text(value + suffix, 22, 0xffb2d3fb);
            label.setPadding(
                    components.dp(13), components.dp(9), components.dp(13), components.dp(9));
            label.setBackgroundResource(R.drawable.settings_pill);
            addView(components.row(title, description, label));

            SeekBar slider = new androidx.appcompat.widget.AppCompatSeekBar(activity);
            slider.setMax(choices.length - 1);
            slider.setKeyProgressIncrement(1);
            int nearest = 0;
            for (int index = 1; index < choices.length; index++) {
                if (Math.abs(choices[index] - initialValue)
                        < Math.abs(choices[nearest] - initialValue)) {
                    nearest = index;
                }
            }
            slider.setProgress(nearest);
            slider.setContentDescription(title);
            slider.setStateDescription(value + suffix);
            slider.setProgressTintList(ColorStateList.valueOf(0xffaccdff));
            slider.setThumbTintList(ColorStateList.valueOf(0xffaccdff));
            slider.setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(
                                SeekBar view, int progress, boolean fromUser) {
                            // Preserve saved off-step values until the user moves the slider.
                            if (fromUser) {
                                value = choices[progress];
                                label.setText(value + suffix);
                                view.setStateDescription(value + suffix);
                            }
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar view) {}

                        @Override
                        public void onStopTrackingTouch(SeekBar view) {
                            onProgressChanged(view, view.getProgress(), true);
                        }
                    });
            addView(
                    slider,
                    new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, components.dp(48)));
        }

        int getValue() {
            return value;
        }
    }

    private void save(String encoded) {
        int threshold = preferences.getInt(ScreenBrightnessSettings.NIGHT_THRESHOLD,
                ScreenBrightnessSettings.nightThreshold(this.encoded()));
        preferences.edit().putString(ScreenBrightnessSettings.RULES, encoded)
                .putInt(ScreenBrightnessSettings.NIGHT_THRESHOLD, threshold).apply();
        saveChanged();
    }

    private void saveChanged() {
        SplitConfigSync.pushScreenBrightness(activity, preferences);
        changed.run();
    }
}
