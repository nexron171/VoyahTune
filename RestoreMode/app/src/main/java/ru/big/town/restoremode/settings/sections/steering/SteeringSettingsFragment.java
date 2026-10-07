package ru.big.town.restoremode.settings.sections.steering;

import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.apps.split.SplitStore;
import ru.big.town.restoremode.integration.config.SplitConfigSync;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.settings.ui.dialogs.SettingsAppPicker;
import ru.big.town.restoremode.settings.ui.layout.SettingsComponents;
import ru.big.town.restoremode.settings.ui.layout.SettingsGrid;
import ru.big.town.restoremode.settings.ui.layout.SettingsPanels;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;
import ru.big.town.restoremode.vehicle.steering.SteeringActionStore;
import ru.big.town.restoremode.vehicle.steering.SteeringCanCommandPolicy;
import ru.big.town.restoremode.voice.commands.VoiceCommands;
import ru.big.town.restoremode.voice.commands.VoiceSteeringPolicy;
import ru.big.town.restoremode.widgets.dials.DialWidgetStore;

import java.util.ArrayList;
import java.util.List;

public final class SteeringSettingsFragment extends SettingsSectionFragment {
    @Override
    public SettingsSection section() {
        return SettingsSection.STEERING;
    }

    private SettingsList steeringActions;

    private int selectedSteeringTab = R.id.settingsStarTab;

    static final String[][] STEER_ACTIONS = {
        {"none", "Не менять"},
        {"open_voyahtune", "Открыть VoyahTune"},
        {"system_back", "Системное действие: Назад"},
        {"energy:EV", "Энергорежим: Electric"},
        {"energy:REV", "Энергорежим: Fuel"},
        {"energy:SREV", "Энергорежим: Save"},
        {"energy:EV,REV", "Энергорежим: Electric → Fuel"},
        {"energy:EV,REV,SREV", "Энергорежим: Electric → Fuel → Save"},
        {"energy:EV,SREV", "Энергорежим: Electric → Save"},
        {"energy:REV,SREV", "Энергорежим: Fuel → Save"},
        {"drive:ECO", "Режим езды: Eco"},
        {"drive:COMFORT", "Режим езды: Comf"},
        {"drive:SPORT", "Режим езды: Sport"},
        {"drive:OUTING", "Режим езды: Outing"},
        {"drive:SNOW", "Режим езды: Snow"},
        {"drive:INDIVIDUAL", "Режим езды: Indiv"},
        {"drive:ECO,COMFORT", "Режим езды: Eco → Comf"},
        {"drive:ECO,SPORT", "Режим езды: Eco → Sport"},
        {"drive:ECO,OUTING", "Режим езды: Eco → Outing"},
        {"drive:ECO,SNOW", "Режим езды: Eco → Snow"},
        {"drive:ECO,INDIVIDUAL", "Режим езды: Eco → Indiv"},
        {"drive:COMFORT,SPORT", "Режим езды: Comf → Sport"},
        {"drive:COMFORT,OUTING", "Режим езды: Comf → Outing"},
        {"drive:COMFORT,SNOW", "Режим езды: Comf → Snow"},
        {"drive:COMFORT,INDIVIDUAL", "Режим езды: Comf → Indiv"},
        {"drive:SPORT,OUTING", "Режим езды: Sport → Outing"},
        {"drive:SPORT,SNOW", "Режим езды: Sport → Snow"},
        {"drive:SPORT,INDIVIDUAL", "Режим езды: Sport → Indiv"},
        {"drive:OUTING,SNOW", "Режим езды: Outing → Snow"},
        {"drive:OUTING,INDIVIDUAL", "Режим езды: Outing → Indiv"},
        {"drive:SNOW,INDIVIDUAL", "Режим езды: Snow → Indiv"},
        {"recycle:LOW", "Рекуперация: Низкая"},
        {"recycle:MEDIUM", "Рекуперация: Стандартная"},
        {"recycle:HIGH", "Рекуперация: Высокая"},
        {"recycle:LOW,MEDIUM", "Рекуперация: Низкая → Стандартная"},
        {"recycle:LOW,HIGH", "Рекуперация: Низкая → Высокая"},
        {"recycle:MEDIUM,HIGH", "Рекуперация: Стандартная → Высокая"},
        {"toggle_suspension_maintenance", "Сервисный режим подвески: вкл/выкл"},
        {"toggle_forced_ev", "Force EV: вкл/выкл"},
        {"toggle_pedestrian_sound", "Звук пешеходов: вкл/выкл"},
        {"toggle_headlights", "Фары: выкл/ближний"},
        {"toggle_headlights_auto", "Фары: ближний/авто"},
    };

    private void initSteeringButtons() {
        steeringActions = settingView(R.id.settingsSteeringActions);
        steeringActions.setNestedScrollingEnabled(false);
        RadioGroup tabs = settingView(R.id.settingsSteeringTabs);
        tabs.check(selectedSteeringTab);
        View row = bindingRoot;
        tabs.setOnCheckedChangeListener(
                (group, selected) -> {
                    selectedSteeringTab = selected;
                    updateSteeringSelection(row);
                    refreshSteerActions();
                });
        updateSteeringSelection(row);
        refreshSteerActions();
    }

    private void updateSteeringSelection(View row) {
        boolean left = selectedSteeringTab == R.id.settingsStarTab;
        android.widget.ImageView wheel = row.findViewById(R.id.settingsSteeringWheel);
        TextView caption = row.findViewById(R.id.settingsSteeringCaption);
        TextView selected = row.findViewById(selectedSteeringTab);
        ((TextView) row.findViewById(R.id.settingsSteeringSelectedTitle))
                .setText(selected.getText());
        wheel.setImageResource(
                left ? R.drawable.settings_wheel_left : R.drawable.settings_wheel_right);
        caption.setText(
                left
                        ? "Левый блок · звёздочка"
                        : "Правый блок · "
                                + (selectedSteeringTab == R.id.settingsDvrTab
                                        ? "DVR"
                                        : selectedSteeringTab == R.id.settingsVoiceTab
                                                ? "голосовой помощник"
                                                : "трубка"));
        wheel.setContentDescription(caption.getText());
    }

    private void pickSteerAction(String key) {
        if (voiceOwnsSlot(key)) {
            return;
        }
        final int staticCount = STEER_ACTIONS.length - 1;
        final CharSequence[] labels = new CharSequence[staticCount + 4];
        for (int i = 0; i < staticCount; i++) {
            labels[i] = STEER_ACTIONS[i + 1][1];
        }
        labels[staticCount] = "Открыть сплит…";
        labels[staticCount + 1] = "Открыть приложение…";
        labels[staticCount + 2] = "Набрать номер…";
        labels[staticCount + 3] = "Своя CAN-команда…";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        requireContext(), R.style.SettingsDialog)
                .setTitle("Добавить действие")
                .setItems(
                        labels,
                        (d, which) -> {
                            if (which < staticCount) {
                                appendSteerAction(key, STEER_ACTIONS[which + 1][0]);
                            } else if (which == staticCount) {
                                pickSteerSplit(key);
                            } else if (which == staticCount + 1) {
                                pickSteerApp(key);
                            } else if (which == staticCount + 2) {
                                pickSteerDial(key);
                            } else {
                                showCustomSteerCommandDialog(key);
                            }
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickSteerDial(String key) {
        List<DialWidgetStore.Entry> entries = DialWidgetStore.load(preferences);
        if (entries.isEmpty()) {
            android.widget.Toast.makeText(
                            requireContext(),
                            "Сначала создайте карточку набора номера",
                            android.widget.Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        CharSequence[] labels = new CharSequence[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            DialWidgetStore.Entry entry = entries.get(i);
            labels[i] = (entry.name.isEmpty() ? "Без имени" : entry.name) + " — " + entry.number;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        requireContext(), R.style.SettingsDialog)
                .setTitle("Выбрать номер для кнопки руля")
                .setItems(
                        labels,
                        (dialog, which) -> {
                            String number = entries.get(which).number.replaceAll("[^0-9]", "");
                            if (number.length() >= 4 && number.length() <= 10) {
                                if (number.length() == 10) {
                                    number = "8" + number;
                                }
                                appendSteerAction(key, "call:" + number);
                            }
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickSteerSplit(String key) {
        final java.util.List<SplitStore.Preset> all = SplitStore.load(preferences);
        final java.util.List<Integer> readyIdx = new java.util.ArrayList<>();
        final java.util.List<CharSequence> labels = new java.util.ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            SplitStore.Preset preset = all.get(i);
            if (preset.ready()) {
                readyIdx.add(i);
                labels.add(
                        (preset.ll.isEmpty() ? preset.l : preset.ll)
                                + "  /  "
                                + (preset.rl.isEmpty() ? preset.r : preset.rl));
            }
        }
        if (readyIdx.isEmpty()) {
            com.google.android.material.snackbar.Snackbar.make(
                            requireView(),
                            "Нет готовых сплитов — сначала настройте сплит в «Приложения и "
                                    + "разделение экрана»",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .show();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        requireContext(), R.style.SettingsDialog)
                .setTitle("Открыть сплит")
                .setItems(
                        labels.toArray(new CharSequence[0]),
                        (d, which) -> appendSteerAction(key, "split:" + readyIdx.get(which)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickSteerApp(String key) {
        SettingsAppPicker.show(
                requireContext(),
                "Открыть приложение",
                (packageName, label) -> appendSteerAction(key, "app:" + packageName));
    }

    private void showCustomSteerCommandDialog(String key) {
        View content =
                LayoutInflater.from(requireContext())
                        .inflate(R.layout.dialog_steering_can_command, null, false);
        EditText editor = content.findViewById(R.id.steerCanCommandInput);
        TextView error = content.findViewById(R.id.steerCanCommandError);
        AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                requireContext(), R.style.SettingsDialog)
                        .setTitle("Своя CAN-команда")
                        .setView(content)
                        .setPositiveButton("Добавить", null)
                        .setNegativeButton("Отмена", null)
                        .create();
        dialog.setOnShowListener(
                ignored -> {
                    Button add = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                    TextWatcher watcher =
                            new TextWatcher() {
                                private boolean formatting;

                                @Override
                                public void beforeTextChanged(
                                        CharSequence s, int start, int count, int after) {}

                                @Override
                                public void onTextChanged(
                                        CharSequence s, int start, int before, int count) {
                                    if (formatting) {
                                        return;
                                    }
                                    String formatted =
                                            SteeringCanCommandPolicy.format(s.toString());
                                    if (!formatted.contentEquals(s)) {
                                        formatting = true;
                                        editor.setText(formatted);
                                        editor.setSelection(formatted.length());
                                        formatting = false;
                                    }
                                    updateCustomCanValidation(editor, error, add);
                                }

                                @Override
                                public void afterTextChanged(Editable s) {}
                            };
                    editor.addTextChangedListener(watcher);
                    updateCustomCanValidation(editor, error, add);
                    add.setOnClickListener(
                            v -> {
                                if (!SteeringCanCommandPolicy.isValid(
                                        editor.getText().toString())) {
                                    return;
                                }
                                appendSteerAction(
                                        key,
                                        SteeringCanCommandPolicy.actionId(
                                                editor.getText().toString()));
                                dialog.dismiss();
                            });
                    editor.requestFocus();
                });
        dialog.show();
    }

    private void updateCustomCanValidation(EditText editor, TextView error, Button add) {
        String compact = SteeringCanCommandPolicy.compact(editor.getText().toString());
        boolean valid = compact.length() == SteeringCanCommandPolicy.HEX_LENGTH;
        editor.setBackgroundColor(valid ? Color.WHITE : 0xffffafaf);
        error.setText(
                valid
                        ? "Команда готова"
                        : "Нужно 20 hex-символов (10 байт). Сейчас: " + compact.length());
        error.setTextColor(valid ? 0xff8bc9a3 : 0xffff8a80);
        add.setEnabled(valid);
        add.setAlpha(valid ? 1f : 0.4f);
    }

    private void appendSteerAction(String key, String action) {
        if (voiceOwnsSlot(key)) {
            return;
        }
        List<String> actions = SteeringActionStore.load(preferences, key);
        actions.add(action);
        SteeringActionStore.save(preferences, key, actions);
        refreshSteerActions();
        pushSteerConfig();
    }

    private boolean voiceOwnsSlot(String key) {
        return VoiceSteeringPolicy.ownsSlot(
                preferences.getBoolean(VoiceCommands.ENABLED, false),
                preferences.getString(VoiceSteeringPolicy.PRESS_KEY, VoiceSteeringPolicy.LONG),
                key);
    }

    private void refreshSteerActions() {
        if (steeringActions == null) {
            return;
        }
        String button =
                selectedSteeringTab == R.id.settingsStarTab
                        ? "steerStar"
                        : selectedSteeringTab == R.id.settingsDvrTab
                                ? "steerDvr"
                                : selectedSteeringTab == R.id.settingsVoiceTab
                                        ? "steerVoice"
                                        : "steerPhone";
        String shortKey = button + "Short";
        String longKey = button + "Long";
        List<String> shortActions = SteeringActionStore.load(preferences, shortKey);
        List<String> longActions = SteeringActionStore.load(preferences, longKey);
        int count =
                Math.max(
                        1,
                        Math.max(
                                voiceOwnsSlot(shortKey) ? 1 : shortActions.size(),
                                voiceOwnsSlot(longKey) ? 1 : longActions.size()));
        List<SettingsList.Row> rows = new ArrayList<>();
        rows.add(
                new SettingsList.Row(
                        button + ":header",
                        parent ->
                                steeringPair(
                                        steeringHeading("Короткое нажатие", "Касание"),
                                        steeringHeading("Долгое нажатие", "~0,6 с"))));
        for (int index = 0; index < count; index++) {
            final int actionIndex = index;
            rows.add(
                    new SettingsList.Row(
                            button + ":action:" + index,
                            parent ->
                                    steeringPair(
                                            steeringActionFragment(
                                                    parent, shortKey, shortActions, actionIndex),
                                            steeringActionFragment(
                                                    parent, longKey, longActions, actionIndex))));
        }
        rows.add(
                new SettingsList.Row(
                        button + ":footer",
                        parent -> steeringPair(steeringFooter(shortKey), steeringFooter(longKey))));
        steeringActions.submit(rows, false);
    }

    private SettingsGrid steeringPair(View shortPress, View longPress) {
        SettingsGrid grid = new SettingsGrid(requireContext(), null);
        grid.addView(shortPress);
        grid.addView(longPress);
        return grid;
    }

    private View steeringHeading(String title, String hint) {
        SettingsComponents components = new SettingsComponents(requireContext());
        LinearLayout panel =
                components.column(
                        components.text(
                                title, 22, requireContext().getColor(R.color.settings_text)),
                        components.text(
                                hint, 15, requireContext().getColor(R.color.settings_muted)));
        int padding = SettingsDesign.dp(panel, 25);
        panel.setPadding(padding, padding, padding, SettingsDesign.dp(panel, 20));
        return SettingsPanels.widgetFragment(panel, true, false);
    }

    private View steeringActionFragment(
            LinearLayout parent, String key, List<String> actions, int index) {
        LinearLayout panel = new LinearLayout(requireContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsDesign.dp(panel, 25);
        panel.setPadding(padding, 0, padding, 0);
        if (voiceOwnsSlot(key)) {
            if (index == 0) {
                View reserved =
                        LayoutInflater.from(requireContext())
                                .inflate(R.layout.settings_steering_reserved, panel, false);
                reserved.findViewById(R.id.settingsOpenVoice)
                        .setOnClickListener(view -> openSection(SettingsSection.VOICE));
                panel.addView(reserved);
            }
        } else if (actions.isEmpty() && index == 0) {
            TextView empty = new TextView(requireContext());
            empty.setText("Штатное поведение\nДействия не назначены");
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, SettingsDesign.dp(empty, 22), 0, SettingsDesign.dp(empty, 22));
            empty.setTextColor(0xff8b9cb4);
            empty.setTextSize(18f);
            panel.addView(empty);
        } else if (index < actions.size()) {
            panel.addView(steeringActionRow(parent, key, actions, index));
        }
        SettingsDesign.styleTree(panel);
        return SettingsPanels.widgetFragment(panel, false, false);
    }

    private View steeringFooter(String key) {
        LinearLayout panel = new LinearLayout(requireContext());
        int padding = SettingsDesign.dp(panel, 25);
        panel.setPadding(padding, SettingsDesign.dp(panel, 16), padding, padding);
        if (!voiceOwnsSlot(key)) {
            Button add = new com.google.android.material.button.MaterialButton(requireContext());
            add.setText("+ Добавить действие");
            add.setTag("settings.add");
            add.setOnClickListener(view -> pickSteerAction(key));
            panel.addView(add, new LinearLayout.LayoutParams(-1, -2));
        }
        SettingsDesign.styleTree(panel);
        return SettingsPanels.widgetFragment(panel, false, true);
    }

    private View steeringActionRow(
            LinearLayout parent, String key, List<String> actions, int index) {
        View row =
                LayoutInflater.from(requireContext())
                        .inflate(R.layout.item_steering_action, parent, false);
        TextView label = row.findViewById(R.id.steerActionLabel);
        ImageButton delete = row.findViewById(R.id.steerActionDelete);
        label.setText(steerActionLabel(actions.get(index)));
        ((TextView) row.findViewById(R.id.settingsSteerIndex)).setText(String.valueOf(index + 1));
        View up = row.findViewById(R.id.settingsSteerUp),
                down = row.findViewById(R.id.settingsSteerDown);
        up.setEnabled(index > 0);
        down.setEnabled(index + 1 < actions.size());
        up.setOnClickListener(v -> moveSteerAction(key, index, -1));
        down.setOnClickListener(v -> moveSteerAction(key, index, 1));
        delete.setOnClickListener(
                v -> {
                    List<String> current = SteeringActionStore.load(preferences, key);
                    if (index < 0 || index >= current.size()) {
                        return;
                    }
                    current.remove(index);
                    SteeringActionStore.save(preferences, key, current);
                    refreshSteerActions();
                    pushSteerConfig();
                });
        SettingsDesign.styleTree(row);
        return row;
    }

    private void moveSteerAction(String key, int index, int delta) {
        List<String> actions = SteeringActionStore.load(preferences, key);
        int target = index + delta;
        if (index < 0 || index >= actions.size() || target < 0 || target >= actions.size()) {
            return;
        }
        java.util.Collections.swap(actions, index, target);
        SteeringActionStore.save(preferences, key, actions);
        refreshSteerActions();
        pushSteerConfig();
    }

    private String steerActionLabel(String id) {
        if (id == null || id.isEmpty()) {
            return "Не менять";
        }
        for (String[] a : STEER_ACTIONS) {
            if (a[0].equals(id)) {
                return a[1];
            }
        }
        if (id.startsWith("split:")) {
            try {
                int n = Integer.parseInt(id.substring("split:".length()));
                java.util.List<SplitStore.Preset> all = SplitStore.load(preferences);
                if (n >= 0 && n < all.size()) {
                    SplitStore.Preset preset = all.get(n);
                    return "Сплит: "
                            + (preset.ll.isEmpty() ? preset.l : preset.ll)
                            + " / "
                            + (preset.rl.isEmpty() ? preset.r : preset.rl);
                }
            } catch (Exception ignored) {
            }
            return "Сплит (не найден)";
        }
        if (id.startsWith("app:")) {
            String packageName = id.substring("app:".length());
            try {
                android.content.pm.PackageManager packageManager =
                        requireContext().getPackageManager();
                return "Приложение: "
                        + packageManager
                                .getApplicationLabel(
                                        packageManager.getApplicationInfo(packageName, 0))
                                .toString();
            } catch (Exception e) {
                return "Приложение: " + packageName;
            }
        }
        if (id.startsWith("call:")) {
            return "Набрать номер: " + id.substring("call:".length());
        }
        if (id.startsWith("can:")) {
            String command = id.substring("can:".length());
            return command.length() == SteeringCanCommandPolicy.HEX_LENGTH
                    ? "Своя команда: " + SteeringCanCommandPolicy.format(command)
                    : "Своя команда (неверный формат)";
        }
        return "Неизвестное действие: " + id;
    }

    private void pushSteerConfig() {
        SplitConfigSync.pushSteering(requireContext(), preferences);
    }

    @Override
    protected void onSectionViewCreated(Bundle state) {
        selectedSteeringTab = state.getInt("steeringTab", R.id.settingsStarTab);
    }

    @Override
    protected void saveSectionState(Bundle state) {
        state.putInt("steeringTab", selectedSteeringTab);
    }

    @Override
    protected void bindSettingsRow() {
        if (settingView(R.id.settingsSteeringTabs) != null) {
            initSteeringButtons();
        }
    }

    @Override
    protected void releaseSettingsRow(View row) {
        if (isDescendant(row, steeringActions)) {
            steeringActions.setAdapter(null);
            steeringActions = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshSteerActions();
    }
}
