package ru.big.town.restoremode.settings.sections.apps;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ru.big.town.common.ApplicationStartupSettings;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.apps.ApplicationStartupStore;
import ru.big.town.restoremode.apps.ApplicationStartupStore.Entry;
import ru.big.town.restoremode.apps.split.SplitStore;
import ru.big.town.restoremode.settings.ui.controls.SettingsChoiceGroup;
import ru.big.town.restoremode.settings.ui.dialogs.SettingsAppPicker;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ApplicationStartupEditor {
    private final Context context;
    private final SharedPreferences preferences;
    private final Runnable refresh;

    ApplicationStartupEditor(Context context, SharedPreferences preferences, Runnable refresh) {
        this.context = context;
        this.preferences = preferences;
        this.refresh = refresh;
    }

    void addRows(List<SettingsList.Row> rows) {
        rows.add(new SettingsList.Row("startup:section", parent -> section()));
    }

    private View section() {
        LinearLayout panel = controls();
        List<Entry> entries = ApplicationStartupStore.load(preferences);
        for (int position = 0; position < entries.size(); position++) {
            addElement(panel, entryRow(entries, position));
        }
        addElement(panel, button("＋ Добавить приложение", () -> pickApp(-1)));
        addElement(panel, button("＋ Добавить сплит", () -> pickSplit(-1)));
        SettingsDesign.styleTree(panel);
        return panel;
    }

    private LinearLayout controls() {
        LinearLayout panel = panel();
        TextView title = new TextView(context);
        title.setText("Автозапуск приложений");
        title.setTextSize(27);
        addElement(panel, title);
        addElement(panel, enabledChoices());
        TextView description = new TextView(context);
        description.setText(
                "Запуск по порядку после инициализации Native. Первый — сразу, остальные — с"
                    + " выбранной паузой. Сплиты настраиваются ниже. При включении этот список"
                    + " заменяет запуск главного экрана и последнего плеера при старте Native.");
        description.setTextSize(17);
        addElement(panel, description);
        addElement(
                panel,
                button(
                        "Пауза между запусками: "
                                + ApplicationStartupStore.delaySeconds(preferences)
                                + " с",
                        this::pickDelay));
        return panel;
    }

    private View enabledChoices() {
        SettingsChoiceGroup choices = new SettingsChoiceGroup(context, null);
        choices.setContentDescription("Автозапуск приложений");
        RadioButton disabled = new RadioButton(context);
        disabled.setId(View.generateViewId());
        disabled.setText("Выключен");
        RadioButton enabled = new RadioButton(context);
        enabled.setId(View.generateViewId());
        enabled.setText("Включён");
        choices.addView(disabled);
        choices.addView(enabled);
        choices.check(
                preferences.getBoolean(ApplicationStartupSettings.ENABLED, false)
                        ? enabled.getId()
                        : disabled.getId());
        choices.setOnCheckedChangeListener(
                (group, checkedId) ->
                        preferences
                                .edit()
                                .putBoolean(
                                        ApplicationStartupSettings.ENABLED,
                                        checkedId == enabled.getId())
                                .apply());
        return choices;
    }

    private void addElement(LinearLayout panel, View element) {
        LinearLayout.LayoutParams layout =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        if (panel.getChildCount() > 0) layout.topMargin = SettingsDesign.dp(panel, 16);
        panel.addView(element, layout);
    }

    private View entryRow(List<Entry> entries, int position) {
        Entry entry = entries.get(position);
        String label = entry.label;
        if (entry.target.startsWith("split:")) {
            label = "Сплит удалён или не настроен";
            for (SplitStore.Preset preset : SplitStore.load(preferences)) {
                if (entry.target.equals("split:" + preset.id) && preset.ready()) {
                    label = splitLabel(preset);
                    break;
                }
            }
        }
        return button((position + 1) + ". " + label, () -> edit(position));
    }

    private void edit(int position) {
        new MaterialAlertDialogBuilder(context, R.style.SettingsDialog)
                .setTitle("Элемент " + (position + 1))
                .setItems(
                        new String[] {
                            "Выбрать приложение",
                            "Выбрать сплит",
                            "Переместить выше",
                            "Переместить ниже",
                            "Удалить"
                        },
                        (dialog, choice) -> {
                            if (choice == 0) pickApp(position);
                            else if (choice == 1) pickSplit(position);
                            else changeOrder(position, choice);
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void changeOrder(int position, int choice) {
        List<Entry> entries = ApplicationStartupStore.load(preferences);
        if (position >= entries.size()) return;
        if (choice == 4) entries.remove(position);
        else {
            int destination = position + (choice == 2 ? -1 : 1);
            if (destination < 0 || destination >= entries.size()) return;
            Collections.swap(entries, position, destination);
        }
        ApplicationStartupStore.save(preferences, entries);
        refresh.run();
    }

    private void pickDelay() {
        String[] labels = new String[ApplicationStartupSettings.MAX_DELAY_SECONDS + 1];
        for (int seconds = 0; seconds < labels.length; seconds++) labels[seconds] = seconds + " с";
        new MaterialAlertDialogBuilder(context, R.style.SettingsDialog)
                .setTitle("Пауза между запусками")
                .setSingleChoiceItems(
                        labels,
                        ApplicationStartupStore.delaySeconds(preferences),
                        (dialog, seconds) -> {
                            preferences
                                    .edit()
                                    .putInt(ApplicationStartupSettings.DELAY, seconds)
                                    .apply();
                            dialog.dismiss();
                            refresh.run();
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickApp(int position) {
        SettingsAppPicker.show(
                context,
                "Приложение для автозапуска",
                true,
                (packageName, label) ->
                        saveEntry(position, new Entry("app:" + packageName, label)));
    }

    private void pickSplit(int position) {
        List<SplitStore.Preset> presets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (SplitStore.Preset preset : SplitStore.load(preferences)) {
            if (preset.ready()) {
                presets.add(preset);
                labels.add(splitLabel(preset));
            }
        }
        MaterialAlertDialogBuilder dialog =
                new MaterialAlertDialogBuilder(context, R.style.SettingsDialog)
                        .setTitle("Сплит для автозапуска")
                        .setNegativeButton("Отмена", null);
        if (presets.isEmpty())
            dialog.setMessage(
                    "Сначала создайте сплит и выберите оба приложения в разделе «Разделение экрана»"
                            + " ниже.");
        else
            dialog.setItems(
                    labels.toArray(new String[0]),
                    (selected, index) ->
                            saveEntry(
                                    position,
                                    new Entry(
                                            "split:" + presets.get(index).id, labels.get(index))));
        dialog.show();
    }

    private String splitLabel(SplitStore.Preset preset) {
        return "Сплит: "
                + (preset.ll.isEmpty() ? preset.l : preset.ll)
                + " / "
                + (preset.rl.isEmpty() ? preset.r : preset.rl);
    }

    private void saveEntry(int position, Entry entry) {
        List<Entry> entries = ApplicationStartupStore.load(preferences);
        if (position < 0) entries.add(entry);
        else if (position < entries.size()) entries.set(position, entry);
        ApplicationStartupStore.save(preferences, entries);
        refresh.run();
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams layout =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.bottomMargin = SettingsDesign.dp(panel, 16);
        panel.setLayoutParams(layout);
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackgroundResource(R.drawable.settings_card);
        return panel;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        return button;
    }
}
