package ru.big.town.restoremode.settings.sections.apps;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.apps.AppDpiStore;
import ru.big.town.restoremode.apps.DockLongPressAction;
import ru.big.town.restoremode.apps.FullscreenAppStore;
import ru.big.town.restoremode.apps.split.SplitStore;
import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;
import ru.big.town.restoremode.integration.config.SplitConfigSync;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.settings.core.SettingsSectionLayouts;
import ru.big.town.restoremode.settings.ui.controls.SettingsChoiceGroup;
import ru.big.town.restoremode.settings.ui.dialogs.SettingsAppPicker;
import ru.big.town.restoremode.settings.ui.layout.SettingsGrid;
import ru.big.town.restoremode.settings.ui.layout.SettingsPanels;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.ui.preview.SettingsPreviewView;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;

import java.util.List;

public final class AppsSettingsFragment extends SettingsSectionFragment {
    @Override
    public SettingsSection section() {
        return SettingsSection.APPS;
    }

    private Button dockApp1Btn, dockApp2Btn;
    private Button dockSplit1Btn, dockSplit2Btn;

    private void initDockOverride() {
        dockApp1Btn = settingView(R.id.buttonDockApp1);
        dockApp2Btn = settingView(R.id.buttonDockApp2);
        dockSplit1Btn = settingView(R.id.buttonDockSplit1);
        dockSplit2Btn = settingView(R.id.buttonDockSplit2);
        refreshDockButtons();
        settingView(R.id.settingsResetDock1).setOnClickListener(v -> clearDockApp(1));
        settingView(R.id.settingsResetDock2).setOnClickListener(v -> clearDockApp(2));
        if (dockApp1Btn != null) {
            dockApp1Btn.setOnLongClickListener(
                    v -> {
                        clearDockApp(1);
                        return true;
                    });
        }
        if (dockApp2Btn != null) {
            dockApp2Btn.setOnLongClickListener(
                    v -> {
                        clearDockApp(2);
                        return true;
                    });
        }
    }

    private void pickDockLongPress(int slot) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        requireContext(), R.style.SettingsDialog)
                .setTitle("Долгое нажатие · приложение " + slot)
                .setItems(
                        new String[] {
                            "Открыть сплит",
                            "Открыть в медиакарточке приборной панели",
                            "Не назначено"
                        },
                        (dialog, which) -> {
                            if (which == 0) {
                                pickDockSplit(slot);
                            } else {
                                preferences
                                        .edit()
                                        .putString(
                                                "dockOverride" + slot + "LongAction",
                                                which == 1 ? "cluster" : "none")
                                        .apply();
                                refreshDockButtons();
                                pushDockConfig();
                            }
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickDockApp(int slot) {
        SettingsAppPicker.show(
                requireContext(),
                "Приложение " + slot + " в доке",
                (packageName, label) -> {
                    preferences
                            .edit()
                            .putString("dockOverride" + slot, packageName)
                            .putString("dockOverride" + slot + "Label", label)
                            .apply();
                    refreshDockButtons();
                    pushDockConfig();
                });
    }

    private void pickDockSplit(int slot) {
        final java.util.List<SplitStore.Preset> all = SplitStore.load(preferences);
        final java.util.List<Integer> readyIdx = new java.util.ArrayList<>();
        final java.util.List<CharSequence> labels = new java.util.ArrayList<>();
        labels.add("Нет (только открыть приложение)");
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
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        requireContext(), R.style.SettingsDialog)
                .setTitle("Сплит по долгому нажатию (слот " + slot + ")")
                .setItems(
                        labels.toArray(new CharSequence[0]),
                        (d, which) -> {
                            if (which == 0) {
                                preferences
                                        .edit()
                                        .putString("dockOverride" + slot + "LongAction", "none")
                                        .remove("dockOverride" + slot + "Split")
                                        .remove("dockOverride" + slot + "SplitLabel")
                                        .apply();
                            } else {
                                int presetIndex = readyIdx.get(which - 1);
                                preferences
                                        .edit()
                                        .putString("dockOverride" + slot + "LongAction", "split")
                                        .putInt("dockOverride" + slot + "Split", presetIndex)
                                        .putString(
                                                "dockOverride" + slot + "SplitLabel",
                                                labels.get(which).toString())
                                        .apply();
                            }
                            refreshDockButtons();
                            pushDockConfig();
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void clearDockApp(int slot) {
        preferences
                .edit()
                .remove("dockOverride" + slot)
                .remove("dockOverride" + slot + "Label")
                .remove("dockOverride" + slot + "LongAction")
                .remove("dockOverride" + slot + "Split")
                .remove("dockOverride" + slot + "SplitLabel")
                .apply();
        refreshDockButtons();
        pushDockConfig();
        com.google.android.material.snackbar.Snackbar.make(
                        requireView(),
                        "Слот " + slot + " сброшен",
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show();
    }

    private void refreshDockButtons() {
        setDockButtonText(dockApp1Btn, 1);
        setDockButtonText(dockApp2Btn, 2);
        setDockSplitButton(dockSplit1Btn, 1);
        setDockSplitButton(dockSplit2Btn, 2);
    }

    private void setDockButtonText(Button b, int slot) {
        if (b == null) {
            return;
        }
        String label = preferences.getString("dockOverride" + slot + "Label", "");
        b.setText(label.isEmpty() ? "Не выбрано" : label);
    }

    private void setDockSplitButton(Button b, int slot) {
        if (b == null) {
            return;
        }
        boolean hasApp = !preferences.getString("dockOverride" + slot, "").isEmpty();
        b.setVisibility(View.VISIBLE);
        b.setEnabled(hasApp);
        String action = DockLongPressAction.resolve(preferences, slot);
        String label = "не назначено";
        if ("cluster".equals(action)) {
            label = "медиакарточка приборной панели";
        } else if ("split".equals(action)) {
            label =
                    "сплит · "
                            + preferences.getString(
                                    "dockOverride" + slot + "SplitLabel", "не выбран");
        }
        b.setText(label);
    }

    private void onAddFullscreenApp(View v) {
        SettingsAppPicker.show(
                requireContext(),
                "Добавить полноэкранное приложение",
                (packageName, label) -> {
                    if (packageName.startsWith("ru.big.town")) {
                        com.google.android.material.snackbar.Snackbar.make(
                                        requireView(),
                                        "Экраны VoyahTune используют собственную системную"
                                                + " раскладку",
                                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                .show();
                        return;
                    }
                    java.util.List<String> packages = FullscreenAppStore.load(preferences);
                    if (!packages.contains(packageName)) {
                        packages.add(packageName);
                        saveFullscreenApps(packages);
                    }
                });
    }

    private void saveFullscreenApps(java.util.List<String> packages) {
        FullscreenAppStore.save(preferences, packages);
        SplitConfigSync.pushFullscreenApps(requireContext(), preferences);
        renderFullscreenApps();
    }

    private void renderFullscreenApps() {
        refreshSettingsRows();
    }

    private void onAddSplitPreset(View v) {
        java.util.List<SplitStore.Preset> list = SplitStore.load(preferences);
        list.add(new SplitStore.Preset());
        saveSplitPresets(list);
        renderSplitPresets();
    }

    private void saveSplitPresets(java.util.List<SplitStore.Preset> list) {
        SplitStore.save(preferences, list);

        TileOrderStore.sync(preferences, requireContext().getPackageManager());
        SplitConfigSync.pushAll(requireContext(), preferences);
    }

    private void renderSplitPresets() {
        refreshSettingsRows();
    }

    private static final int[] DPI_VALUES = {
        0, 120, 140, 160, 180, 200, 213, 240, 260, 280, 300, 320, 360
    };

    private static final String[] DPI_LABELS = {
        "Авто", "120", "140", "160", "180", "200", "213", "240", "260", "280", "300", "320", "360"
    };

    private int dpiIndex(int dpi) {
        for (int i = 0; i < DPI_VALUES.length; i++) {
            if (DPI_VALUES[i] == dpi) {
                return i;
            }
        }
        return 0;
    }

    private void pushDockConfig() {
        SplitConfigSync.pushDock(requireContext(), preferences);
    }

    private View fullscreenRow(LinearLayout parent, String packageName) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        android.content.pm.PackageManager packageManager = requireContext().getPackageManager();
        View row = inflater.inflate(R.layout.item_app_shortcut, parent, false);
        android.widget.ImageView icon = row.findViewById(R.id.shortcutIco);
        TextView label = row.findViewById(R.id.shortcutLabel);
        ImageButton delete = row.findViewById(R.id.shortcutDelete);
        String name = packageName;
        try {
            android.content.pm.ApplicationInfo info =
                    packageManager.getApplicationInfo(packageName, 0);
            name = packageManager.getApplicationLabel(info).toString();
            icon.setImageResource(R.drawable.settings_icon_fullscreen);
        } catch (Exception ignored) {
        }
        label.setText(name);
        delete.setContentDescription("Убрать из полноэкранных приложений");
        delete.setOnClickListener(
                v -> {
                    java.util.List<String> next = FullscreenAppStore.load(preferences);
                    next.remove(packageName);
                    saveFullscreenApps(next);
                });
        SettingsDesign.styleTree(row);

        return row;
    }

    private View splitRow(LinearLayout parent, SplitStore.Preset preset, int presetIndex) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View row = inflater.inflate(R.layout.item_split_preset, parent, false);
        SettingsPreviewView preview = row.findViewById(R.id.settingsSplitPreview);
        preview.setSplit(preset);
        ((TextView) row.findViewById(R.id.settingsSplitTitle))
                .setText("Сплит " + (presetIndex + 1));
        TextView ratioLabel = row.findViewById(R.id.settingsSplitRatioLabel);
        ratioLabel.setText(SplitStore.RATIO_LABELS[Math.max(0, Math.min(4, preset.ratio))]);

        Button leftAppButton = row.findViewById(R.id.splitLeftBtn);
        Button rightAppButton = row.findViewById(R.id.splitRightBtn);
        Button deleteButton = row.findViewById(R.id.splitDeleteBtn);
        android.widget.Spinner scaleSpinner = row.findViewById(R.id.splitRatioSpinner);

        leftAppButton.setText(preset.ll.isEmpty() ? "не выбрано" : preset.ll);
        rightAppButton.setText(preset.rl.isEmpty() ? "не выбрано" : preset.rl);

        leftAppButton.setOnClickListener(
                v ->
                        SettingsAppPicker.show(
                                requireContext(),
                                "Приложение слева",
                                (packageName, label) -> {
                                    java.util.List<SplitStore.Preset> updatedPresets =
                                            SplitStore.load(preferences);
                                    if (presetIndex < updatedPresets.size()) {
                                        updatedPresets.get(presetIndex).l = packageName;
                                        updatedPresets.get(presetIndex).ll = label;
                                        saveSplitPresets(updatedPresets);
                                        renderSplitPresets();
                                    }
                                }));
        rightAppButton.setOnClickListener(
                v ->
                        SettingsAppPicker.show(
                                requireContext(),
                                "Приложение справа",
                                (packageName, label) -> {
                                    java.util.List<SplitStore.Preset> updatedPresets =
                                            SplitStore.load(preferences);
                                    if (presetIndex < updatedPresets.size()) {
                                        updatedPresets.get(presetIndex).r = packageName;
                                        updatedPresets.get(presetIndex).rl = label;
                                        saveSplitPresets(updatedPresets);
                                        renderSplitPresets();
                                    }
                                }));

        android.widget.ArrayAdapter<String> spinnerAdapter =
                new android.widget.ArrayAdapter<>(
                        requireContext(), R.layout.settings_spinner_item, SplitStore.RATIO_LABELS);
        spinnerAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        scaleSpinner.setAdapter(spinnerAdapter);
        scaleSpinner.setSelection(preset.ratio, false);
        SettingsChoiceGroup ratios = row.findViewById(R.id.settingsSplitRatios);
        for (int ratioIndex = 0; ratioIndex < SplitStore.RATIO_LABELS.length; ratioIndex++) {
            RadioButton choice = new RadioButton(requireContext());
            choice.setId(View.generateViewId());
            choice.setText(SplitStore.RATIO_LABELS[ratioIndex]);
            choice.setTag(ratioIndex);
            ratios.addView(choice);
            if (ratioIndex == preset.ratio) {
                ratios.check(choice.getId());
            }
        }
        ratios.setOnCheckedChangeListener(
                (group, checkedId) -> {
                    View selected = group.findViewById(checkedId);
                    if (selected != null) {
                        scaleSpinner.setSelection((Integer) selected.getTag());
                    }
                });
        scaleSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        java.util.List<SplitStore.Preset> updatedPresets =
                                SplitStore.load(preferences);
                        if (presetIndex < updatedPresets.size()
                                && updatedPresets.get(presetIndex).ratio != position) {
                            updatedPresets.get(presetIndex).ratio = position;
                            updatedPresets.get(presetIndex).split = 0f;
                            preview.setSplit(updatedPresets.get(presetIndex));
                            ratioLabel.setText(
                                    SplitStore.RATIO_LABELS[updatedPresets.get(presetIndex).ratio]);
                            saveSplitPresets(updatedPresets);
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });

        Switch resizable = row.findViewById(R.id.splitResizableSwitch);
        if (resizable != null) {
            resizable.setChecked(preset.resizable);
            resizable.setOnCheckedChangeListener(
                    (b, checked) -> {
                        java.util.List<SplitStore.Preset> updatedPresets =
                                SplitStore.load(preferences);
                        if (presetIndex < updatedPresets.size()) {
                            updatedPresets.get(presetIndex).resizable = checked;
                            if (!checked) {
                                updatedPresets.get(presetIndex).split = 0f;
                            }
                            preview.setSplit(updatedPresets.get(presetIndex));
                            ratioLabel.setText(
                                    SplitStore.RATIO_LABELS[updatedPresets.get(presetIndex).ratio]);
                            saveSplitPresets(updatedPresets);
                        }
                    });
        }

        deleteButton.setOnClickListener(
                v -> {
                    java.util.List<SplitStore.Preset> updatedPresets = SplitStore.load(preferences);
                    if (presetIndex < updatedPresets.size()) {
                        updatedPresets.remove(presetIndex);
                        saveSplitPresets(updatedPresets);
                        renderSplitPresets();
                    }
                });

        SettingsDesign.styleTree(row);

        return row;
    }

    private View dpiRow(LinearLayout parent, String packageName) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        android.content.pm.PackageManager packageManager = requireContext().getPackageManager();
        final String targetPackageName = packageName;
        View row = inflater.inflate(R.layout.item_app_dpi, parent, false);
        android.widget.ImageView icon = row.findViewById(R.id.appDpiIco);
        TextView label = row.findViewById(R.id.appDpiLabel);
        android.widget.Spinner scaleSpinner = row.findViewById(R.id.appDpiSpinner);

        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(packageName));
        } catch (Exception ignored) {
        }
        label.setText(appCatalog.currentLabels().get(packageName));

        android.widget.ArrayAdapter<String> spinnerAdapter =
                new android.widget.ArrayAdapter<>(
                        requireContext(), R.layout.settings_spinner_item, DPI_LABELS);
        spinnerAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        scaleSpinner.setAdapter(spinnerAdapter);
        scaleSpinner.setSelection(dpiIndex(AppDpiStore.get(preferences, packageName)), false);
        scaleSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent, View v, int position, long id) {
                        int dpi = DPI_VALUES[position];
                        if (dpi != AppDpiStore.get(preferences, targetPackageName)) {
                            AppDpiStore.set(preferences, targetPackageName, dpi);
                            SplitConfigSync.pushAppDpi(
                                    requireContext(), preferences, targetPackageName, dpi);
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });

        SettingsDesign.styleTree(row);
        return row;
    }

    private AppsSettingsViewModel appCatalog;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        appCatalog = new ViewModelProvider(this).get(AppsSettingsViewModel.class);
    }

    @Override
    protected void onSectionViewCreated(Bundle state) {
        appCatalog.labels().observe(getViewLifecycleOwner(), labels -> refreshSettingsRows());
        appCatalog.load(requireContext().getApplicationContext());
    }

    @Override
    protected void bindSettingsRow() {
        if (settingView(R.id.dockOverrideBlock) != null) {
            initDockOverride();
            bindClick(R.id.buttonDockApp1, view -> pickDockApp(1));
            bindClick(R.id.buttonDockApp2, view -> pickDockApp(2));
            bindClick(R.id.buttonDockSplit1, view -> pickDockLongPress(1));
            bindClick(R.id.buttonDockSplit2, view -> pickDockLongPress(2));
        }
        TextView count = settingView(R.id.settingsSplitCount);
        if (count != null) {
            count.setText("Сплитов: " + SplitStore.load(preferences).size());
        }
        bindClick(R.id.buttonAddFullscreenApp, this::onAddFullscreenApp);
        bindClick(R.id.buttonAddSplit, this::onAddSplitPreset);
    }

    @Override
    protected View decorateSettingsRow(int layout, View row) {
        if (layout == R.layout.settings_apps_fullscreen_description) {
            return SettingsPanels.appSelectionPanel(row, true, false);
        }
        if (layout == R.layout.settings_apps_add_fullscreen_app) {
            return SettingsPanels.appSelectionPanel(row, false, true);
        }
        return row;
    }

    @Override
    protected void releaseSettingsRow(View row) {
        if (isDescendant(row, dockApp1Btn)) {
            dockApp1Btn = null;
        }
        if (isDescendant(row, dockApp2Btn)) {
            dockApp2Btn = null;
        }
        if (isDescendant(row, dockSplit1Btn)) {
            dockSplit1Btn = null;
        }
        if (isDescendant(row, dockSplit2Btn)) {
            dockSplit2Btn = null;
        }
    }

    @Override
    protected void addDynamicRows(
            List<SettingsList.Row> rows, SettingsSectionLayouts.DynamicContent kind) {
        switch (kind) {
            case FULLSCREEN_APPS:
                SettingsPanels.addAppChipRows(
                        rows,
                        FullscreenAppStore.load(preferences),
                        "fullscreen",
                        false,
                        this::fullscreenRow);
                break;
            case SPLITS:
                List<SplitStore.Preset> presets = SplitStore.load(preferences);
                for (int index = 0; index < presets.size(); index += 2) {
                    final int start = index;
                    rows.add(
                            new SettingsList.Row(
                                    "split:" + index,
                                    parent -> {
                                        SettingsGrid grid =
                                                new SettingsGrid(requireContext(), null);
                                        for (int position = start;
                                                position < Math.min(start + 2, presets.size());
                                                position++) {
                                            grid.addView(
                                                    splitRow(
                                                            parent,
                                                            presets.get(position),
                                                            position));
                                        }
                                        return SettingsPanels.spaced(grid);
                                    }));
                }
                break;
            case APP_SCALE:
                for (String packageName : appCatalog.currentLabels().keySet()) {
                    rows.add(
                            new SettingsList.Row(
                                    "dpi:" + packageName, parent -> dpiRow(parent, packageName)));
                }
                break;
            default:
                super.addDynamicRows(rows, kind);
        }
    }
}
