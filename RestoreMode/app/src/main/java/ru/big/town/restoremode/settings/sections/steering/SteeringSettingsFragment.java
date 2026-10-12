package ru.big.town.restoremode.settings.sections.steering;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.integration.config.SplitConfigSync;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.settings.ui.dialogs.SettingsActionPicker;
import ru.big.town.restoremode.settings.ui.layout.SettingsComponents;
import ru.big.town.restoremode.settings.ui.layout.SettingsGrid;
import ru.big.town.restoremode.settings.ui.layout.SettingsPanels;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;
import ru.big.town.restoremode.vehicle.steering.SteeringActionStore;
import ru.big.town.restoremode.voice.commands.VoiceCommands;
import ru.big.town.restoremode.voice.commands.VoiceSteeringPolicy;

import java.util.ArrayList;
import java.util.List;

public final class SteeringSettingsFragment extends SettingsSectionFragment {
    @Override
    public SettingsSection section() {
        return SettingsSection.STEERING;
    }

    private SettingsList steeringActions;

    private int selectedSteeringTab = R.id.settingsStarTab;

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
        if (voiceOwnsSlot(key)) return;
        SettingsActionPicker.show(
                requireContext(),
                preferences,
                requireView(),
                action -> appendSteerAction(key, action));
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
                        preferences.getString(
                                VoiceSteeringPolicy.PRESS_KEY, VoiceSteeringPolicy.LONG),
                        key)
                || siriOwnsSlot(key);
    }

    private boolean siriOwnsSlot(String key) {
        return VoiceSteeringPolicy.siriOwnsSlot(
                preferences.getBoolean(VoiceSteeringPolicy.SIRI_ENABLED, false),
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
                if (siriOwnsSlot(key)) {
                    ((TextView) reserved.findViewById(R.id.settingsReservedVoiceTitle))
                            .setText("Вызов Siri в CarPlay");
                    ((Button) reserved.findViewById(R.id.settingsOpenVoice))
                            .setText("Настроить Siri");
                }
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
        return SettingsActionPicker.label(requireContext(), preferences, id);
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
