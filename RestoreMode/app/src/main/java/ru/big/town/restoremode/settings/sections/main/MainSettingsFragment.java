package ru.big.town.restoremode.settings.sections.main;

import android.content.Intent;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.Switch;
import android.widget.TextView;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;
import ru.big.town.restoremode.integration.GlobalVars;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.settings.core.SettingsSectionLayouts;
import ru.big.town.restoremode.settings.ui.dialogs.SettingsAppPicker;
import ru.big.town.restoremode.settings.ui.layout.SettingsGrid;
import ru.big.town.restoremode.settings.ui.layout.SettingsPanels;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;
import ru.big.town.restoremode.widgets.apps.AppShortcutStore;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;
import ru.big.town.restoremode.widgets.dials.DialWidgetStore;
import ru.big.town.restoremode.widgets.energy.EnergyWidgetLayout;
import ru.big.town.restoremode.widgets.energy.EnergyWidgetPreferences;
import ru.big.town.restoremode.widgets.energy.EnergyWidgetView;
import ru.big.town.restoremode.widgets.system.SystemWidgetLayout;

import java.util.ArrayList;
import java.util.List;

public final class MainSettingsFragment extends SettingsSectionFragment {
    @Override
    public SettingsSection section() {
        return SettingsSection.MAIN;
    }

    private void bindMainWidgetSwitches() {
        bindShowSwitch(R.id.switchShowTripTimer, "showTripTimer", true);
        bindShowSwitch(R.id.switchShowPowerHold, "showPowerHold", true);
        bindShowSwitch(R.id.switchShowWashMode, "showWashMode", true);
        bindShowSwitch(R.id.switchShowAutoLight, "showAutoLight", true);
        bindShowSwitch(R.id.switchShowPedestrian, "showPedestrian", true);
        bindShowSwitch(R.id.switchShowBatteryHeat, "showBatteryHeat", true);
        bindShowSwitch(R.id.switchShowVoiceCommand, "showVoiceCommand", false);
        bindShowSwitch(R.id.switchShowScenariosCard, "showScenariosCard", true);
        bindShowSwitch(R.id.switchShowForcedEv, "showForcedEv", false);
        bindShowSwitch(R.id.switchShowSuspensionMaintenance, "showSuspensionMaintenance", false);
        bindShowSwitch(
                R.id.switchShowLaunchAppsWidget,
                "showLaunchAppsWidget",
                false,
                R.id.launchAppsSizeRow);
        bindTileSizeSpinners(
                R.id.launchAppsSettingWidth,
                R.id.launchAppsSettingHeight,
                TileSizeStore.LAUNCH_APPS_WIDGET_ID,
                TileSizeStore.LAUNCH_APPS_DEFAULT_WIDTH,
                TileSizeStore.LAUNCH_APPS_DEFAULT_HEIGHT);
        bindShowSwitch(
                R.id.switchShowSuspensionWidget,
                "showSuspensionWidget",
                false,
                R.id.suspensionSizeRow);
        bindTileSizeSpinners(
                R.id.suspensionSettingWidth,
                R.id.suspensionSettingHeight,
                TileSizeStore.SUSPENSION_WIDGET_ID,
                TileSizeStore.SUSPENSION_DEFAULT_WIDTH,
                TileSizeStore.SUSPENSION_DEFAULT_HEIGHT);
        bindShowSwitch(R.id.switchShowCpu, "show_cpuWidget", false, R.id.CpuSizeRow);
        bindTileSizeSpinners(
                R.id.CpuSettingWidth, R.id.CpuSettingHeight, SystemWidgetLayout.CPU, 1, 1);
        bindShowSwitch(R.id.switchShowRam, "show_ramWidget", false, R.id.RamSizeRow);
        bindTileSizeSpinners(
                R.id.RamSettingWidth, R.id.RamSettingHeight, SystemWidgetLayout.RAM, 1, 1);
        bindShowSwitch(R.id.switchShowClearMemory, "show_clearMemoryWidget", false, 0);
        bindShowSwitch(R.id.switchShowEnergy, "show_energyWidget", false, R.id.EnergySizeRow);
        bindTileSizeSpinners(
                R.id.EnergySettingWidth, R.id.EnergySettingHeight, "energyWidget", 8, 4);
        bindShowSwitch(
                R.id.switchShowEnergyConsumption,
                "show_energyConsumptionWidget",
                false,
                R.id.EnergyConsumptionSizeRow);
        bindTileSizeSpinners(
                R.id.EnergyConsumptionSettingWidth,
                R.id.EnergyConsumptionSettingHeight,
                "energyConsumptionWidget",
                2,
                2);
        bindShowSwitch(
                R.id.switchShowEnergyTrip, "show_energyTripWidget", false, R.id.EnergyTripSizeRow);
        bindTileSizeSpinners(
                R.id.EnergyTripSettingWidth,
                R.id.EnergyTripSettingHeight,
                "energyTripWidget",
                8,
                2);
        bindShowSwitch(
                R.id.switchShowTirePressure,
                "show_tirePressureWidget",
                false,
                R.id.TirePressureSizeRow);
        bindTileSizeSpinners(
                R.id.TirePressureSettingWidth,
                R.id.TirePressureSettingHeight,
                "tirePressureWidget",
                4,
                4);
        bindShowSwitch(R.id.switchShowOdometer, "show_odometerWidget", false, R.id.OdometerSizeRow);
        bindTileSizeSpinners(
                R.id.OdometerSettingWidth, R.id.OdometerSettingHeight, "odometerWidget", 4, 1);
    }

    private void bindEnergyAppearance() {
        if (settingView(R.id.energyCarColor) == null) {
            return;
        }
        android.widget.Spinner carColor = settingView(R.id.energyCarColor);
        android.widget.ArrayAdapter<String> carColors =
                new android.widget.ArrayAdapter<>(
                        requireContext(),
                        R.layout.settings_spinner_item,
                        EnergyWidgetView.COLOR_NAMES);
        carColors.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        carColor.setAdapter(carColors);
        carColor.setSelection(
                java.util.Arrays.asList(EnergyWidgetView.COLORS)
                        .indexOf(
                                EnergyWidgetView.color(
                                        preferences.getString("energyCarColor", "burgundy"))));
        carColor.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        preferences
                                .edit()
                                .putString("energyCarColor", EnergyWidgetView.COLORS[position])
                                .apply();
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
        bindEnergyCapacity(
                R.id.energyBatteryCapacity,
                ru.big.town.common.EnergyWidgetSettings.BATTERY_KEY,
                43);
        bindEnergyCapacity(
                R.id.energyTankCapacity, ru.big.town.common.EnergyWidgetSettings.TANK_KEY, 56);
    }

    private void bindTripHistory() {
        if (settingView(R.id.switchSaveTripHistory) == null) {
            return;
        }
        Switch switchSaveHistory = settingView(R.id.switchSaveTripHistory);
        switchSaveHistory.setChecked(preferences.getBoolean("saveTripHistory", true));
        switchSaveHistory.setOnCheckedChangeListener(
                (button, checked) -> {
                    preferences.edit().putBoolean("saveTripHistory", checked).apply();
                    Intent intent =
                            new Intent("ru.big.town.anative.TRIP_HISTORY")
                                    .setPackage("ru.big.town.anative");
                    intent.putExtra("enabled", checked);
                    requireContext().sendBroadcast(intent);
                });
    }

    private void bindMainGrid() {
        if (settingView(R.id.switchFullscreenGrid) == null) {
            return;
        }
        Switch switchFullscreenGrid = settingView(R.id.switchFullscreenGrid);
        NumberPicker pickerFullscreenGridColumns = settingView(R.id.pickerFullscreenGridColumns);
        pickerFullscreenGridColumns.setMinValue(0);
        pickerFullscreenGridColumns.setMaxValue(12);
        pickerFullscreenGridColumns.setTextColor(0xffffffff);
        pickerFullscreenGridColumns.setContentDescription("Колонок с растянутым верхним рядом");
        pickerFullscreenGridColumns.setValue(preferences.getInt("fullscreenGridColumns", 8));
        pickerFullscreenGridColumns.setOnValueChangedListener(
                (picker, oldValue, newValue) ->
                        preferences.edit().putInt("fullscreenGridColumns", newValue).apply());

        switchFullscreenGrid.setChecked(preferences.getBoolean("fullscreenGrid", false));

        pickerFullscreenGridColumns.setEnabled(switchFullscreenGrid.isChecked());
        switchFullscreenGrid.setOnCheckedChangeListener(
                (b, checked) -> {
                    preferences.edit().putBoolean("fullscreenGrid", checked).apply();
                    pickerFullscreenGridColumns.setEnabled(checked);
                });

        NumberPicker tileSpacing = settingView(R.id.pickerTileSpacing);
        tileSpacing.setMinValue(0);
        tileSpacing.setMaxValue(24);
        tileSpacing.setValue(Math.max(0, Math.min(24, preferences.getInt("tileSpacingDp", 4))));
        tileSpacing.setOnValueChangedListener(
                (picker, oldValue, newValue) ->
                        preferences.edit().putInt("tileSpacingDp", newValue).apply());
    }

    private void addDialWidget() {
        List<DialWidgetStore.Entry> entries = DialWidgetStore.load(preferences);
        if (entries.size() >= DialWidgetStore.MAX_COUNT) {
            android.widget.Toast.makeText(
                            requireContext(),
                            "Достигнут лимит 100 карточек",
                            android.widget.Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        entries.add(new DialWidgetStore.Entry());
        DialWidgetStore.save(preferences, entries);
        TileOrderStore.sync(preferences, requireContext().getPackageManager());
        refreshSettingsRows();
    }

    private void bindShowSwitch(int switchId, String key, boolean defaultValue) {
        bindShowSwitch(switchId, key, defaultValue, 0);
    }

    private void bindShowSwitch(int switchId, String key, boolean defaultValue, int dependentId) {
        Switch visibilitySwitch = settingView(switchId);
        if (visibilitySwitch == null) {
            return;
        }
        View dependent = dependentId == 0 ? null : settingView(dependentId);
        boolean checked = preferences.getBoolean(key, defaultValue);
        visibilitySwitch.setChecked(checked);
        if (dependent != null) {
            dependent.setVisibility(checked ? View.VISIBLE : View.GONE);
        }
        visibilitySwitch.setOnCheckedChangeListener(
                (b, on) -> {
                    preferences.edit().putBoolean(key, on).apply();
                    if (dependent != null) {
                        dependent.setVisibility(on ? View.VISIBLE : View.GONE);
                    }
                });
    }

    private void bindTileSizeSpinners(
            int widthSpinnerId,
            int heightSpinnerId,
            String widgetId,
            int defaultWidth,
            int defaultHeight) {
        android.widget.Spinner widthSpinner = settingView(widthSpinnerId);
        android.widget.Spinner heightSpinner = settingView(heightSpinnerId);
        if (widthSpinner == null || heightSpinner == null) {
            return;
        }
        if (SystemWidgetLayout.isWidget(widgetId)) {
            bindEnergySize(widthSpinner, widgetId, true, 1, 1, 2, null);
            bindEnergySize(heightSpinner, widgetId, false, 1, 1, 1, null);
            heightSpinner.setEnabled(false);
            return;
        }
        if (EnergyWidgetLayout.isWidget(widgetId)) {
            Runnable bindHeight =
                    () -> {
                        int columns = TileSizeStore.width(preferences, widgetId, defaultWidth);
                        bindEnergySize(
                                heightSpinner,
                                widgetId,
                                false,
                                defaultHeight,
                                EnergyWidgetLayout.minHeight(widgetId, columns),
                                EnergyWidgetLayout.maxHeight(widgetId, columns),
                                null);
                    };
            bindHeight.run();
            bindEnergySize(
                    widthSpinner,
                    widgetId,
                    true,
                    defaultWidth,
                    EnergyWidgetLayout.minWidth(widgetId),
                    EnergyWidgetLayout.maxWidth(widgetId),
                    bindHeight);
            return;
        }

        String[] widths = {
            "1 ячейка",
            "2 ячейки",
            "3 ячейки",
            "4 ячейки",
            "5 ячеек",
            "6 ячеек",
            "7 ячеек",
            "8 ячеек",
            "9 ячеек",
            "10 ячеек",
            "11 ячеек",
            "12 ячеек"
        };
        android.widget.ArrayAdapter<String> widthAdapter =
                new android.widget.ArrayAdapter<>(
                        requireContext(), R.layout.settings_spinner_item, widths);
        widthAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        widthSpinner.setAdapter(widthAdapter);
        widthSpinner.setSelection(TileSizeStore.width(preferences, widgetId, defaultWidth) - 1);
        widthSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        if (TileSizeStore.width(preferences, widgetId, defaultWidth)
                                != position + 1) {
                            TileSizeStore.setWidth(preferences, widgetId, position + 1);
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });

        String[] heights = {"1 ячейка", "2 ячейки", "3 ячейки", "4 ячейки", "5 ячеек"};
        android.widget.ArrayAdapter<String> heightAdapter =
                new android.widget.ArrayAdapter<>(
                        requireContext(), R.layout.settings_spinner_item, heights);
        heightAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        heightSpinner.setAdapter(heightAdapter);
        heightSpinner.setSelection(TileSizeStore.height(preferences, widgetId, defaultHeight) - 1);
        heightSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        if (TileSizeStore.height(preferences, widgetId, defaultHeight)
                                != position + 1) {
                            TileSizeStore.setHeight(preferences, widgetId, position + 1);
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
    }

    private void bindEnergySize(
            android.widget.Spinner spinner,
            String id,
            boolean width,
            int fallback,
            int min,
            int max,
            Runnable onChange) {
        String[] labels = new String[max - min + 1];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = String.valueOf(min + i);
        }
        android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<>(
                        requireContext(), R.layout.settings_spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        spinner.setOnItemSelectedListener(null);
        spinner.setAdapter(adapter);
        spinner.setSelection(
                (width
                                ? TileSizeStore.width(preferences, id, fallback)
                                : TileSizeStore.height(preferences, id, fallback))
                        - min);
        spinner.setEnabled(max > min);
        spinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long itemId) {
                        int value = min + position;
                        if (width) {
                            TileSizeStore.setWidth(preferences, id, value);
                        } else {
                            TileSizeStore.setHeight(preferences, id, value);
                        }
                        if (onChange != null) {
                            onChange.run();
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
    }

    private void bindEnergyCapacity(int fieldId, String key, float fallback) {
        android.widget.EditText field = settingView(fieldId);
        if (field == null) {
            return;
        }
        field.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        float saved = EnergyWidgetPreferences.capacity(preferences, key, fallback);
        settingsList.bindDraft(
                field, "capacity:" + key, new java.text.DecimalFormat("0.#").format(saved));
        field.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(android.text.Editable value) {
                        float parsed =
                                ru.big.town.common.EnergyWidgetSettings.parseCapacity(
                                        value.toString());
                        if (!Float.isFinite(parsed)) {
                            field.setError("Число больше 0, до 1 знака после запятой");
                            return;
                        }
                        field.setError(null);
                        preferences.edit().putFloat(key, parsed).apply();
                        if (GlobalVars.isBound && GlobalVars.serviceMessenger != null) {
                            try {
                                EnergyWidgetPreferences.sync(
                                        GlobalVars.serviceMessenger, preferences);
                            } catch (RemoteException e) {
                                Log.w(
                                        "EnergyWidgets",
                                        "Capacity settings will sync on reconnect",
                                        e);
                            }
                        }
                    }
                });
    }

    private void onAddAppShortcut(View v) {
        SettingsAppPicker.show(
                requireContext(),
                "Добавить приложение",
                (packageName, label) -> {
                    java.util.List<String> list = AppShortcutStore.load(preferences);
                    if (!list.contains(packageName)) {
                        list.add(packageName);
                        AppShortcutStore.save(preferences, list);

                        TileOrderStore.sync(preferences, requireContext().getPackageManager());
                        renderAppShortcuts();
                    }
                });
    }

    private void renderAppShortcuts() {
        refreshSettingsRows();
    }

    private void onAddAppWidget(View v) {
        if (AppWidgetStore.load(preferences).size() >= AppWidgetStore.MAX_WIDGETS) {
            com.google.android.material.snackbar.Snackbar.make(
                            requireView(),
                            "Можно создать не более 20 виджетов",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .show();
            return;
        }
        SettingsAppPicker.show(
                requireContext(),
                "Приложение для виджета",
                (packageName, label) -> {
                    AppWidgetStore.add(preferences, packageName);
                    TileOrderStore.sync(preferences, requireContext().getPackageManager());
                    renderAppWidgets();
                });
    }

    private void renderAppWidgets() {
        refreshSettingsRows();
    }

    private View appWidgetProfileRow(
            LinearLayout parent, AppWidgetStore.Entry entry, int profileIndex) {
        android.content.pm.PackageManager packageManager = requireContext().getPackageManager();
        AppWidgetStore.Profile profile = entry.profiles.get(profileIndex);
        View profileView =
                LayoutInflater.from(requireContext())
                        .inflate(R.layout.item_app_widget_profile, parent, false);
        android.widget.ImageView icon = profileView.findViewById(R.id.appWidgetProfileIcon);
        TextView label = profileView.findViewById(R.id.appWidgetProfileLabel);
        android.widget.Spinner dpi = profileView.findViewById(R.id.appWidgetProfileDpi);
        ImageButton remove = profileView.findViewById(R.id.appWidgetProfileDelete);
        try {
            android.content.pm.ApplicationInfo info =
                    packageManager.getApplicationInfo(profile.packageName, 0);
            icon.setImageDrawable(packageManager.getApplicationIcon(info));
            label.setText(packageManager.getApplicationLabel(info));
        } catch (Exception ignored) {
            label.setText(profile.packageName);
        }
        String[] labels = new String[AppWidgetStore.DPI_VALUES.length];
        int selected = 0;
        for (int dpiIndex = 0; dpiIndex < AppWidgetStore.DPI_VALUES.length; dpiIndex++) {
            int value = AppWidgetStore.DPI_VALUES[dpiIndex];
            labels[dpiIndex] = value == 0 ? "Авто" : String.valueOf(value);
            if (value == AppWidgetStore.normalizeDpi(profile.dpi)) {
                selected = dpiIndex;
            }
        }
        android.widget.ArrayAdapter<String> dpiAdapter =
                new android.widget.ArrayAdapter<>(
                        requireContext(), R.layout.settings_spinner_item, labels);
        dpiAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        dpi.setAdapter(dpiAdapter);
        dpi.setSelection(selected);
        dpi.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        int value = AppWidgetStore.DPI_VALUES[position];
                        if (profile.dpi != value) {
                            profile.dpi = value;
                            AppWidgetStore.update(preferences, entry);
                            TileOrderStore.sync(preferences, requireContext().getPackageManager());
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
        remove.setVisibility(entry.profiles.size() > 1 ? View.VISIBLE : View.GONE);
        remove.setOnClickListener(
                v -> {
                    AppWidgetStore.removeProfile(entry, profileIndex);
                    AppWidgetStore.update(preferences, entry);
                    TileOrderStore.sync(preferences, requireContext().getPackageManager());
                    renderAppWidgets();
                });
        SettingsDesign.styleTree(profileView);
        return profileView;
    }

    private View dialRow(LinearLayout parent, DialWidgetStore.Entry entry) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View row = inflater.inflate(R.layout.item_dial_widget_setting, parent, false);
        EditText name = row.findViewById(R.id.dialSettingName);
        EditText number = row.findViewById(R.id.dialSettingNumber);
        settingsList.bindDraft(name, "dialName:" + entry.id, entry.name);
        settingsList.bindDraft(number, "dialNumber:" + entry.id, entry.number);
        row.findViewById(R.id.dialSettingSave)
                .setOnClickListener(
                        v -> {
                            String valueName = name.getText().toString().trim();
                            String valueNumber =
                                    number.getText().toString().replaceAll("[^0-9]", "");
                            if (valueName.isEmpty()) {
                                name.setError("Введите имя");
                                return;
                            }
                            if (valueNumber.length() < 4 || valueNumber.length() > 10) {
                                number.setError("Введите от 4 до 10 цифр");
                                return;
                            }
                            List<DialWidgetStore.Entry> entries = DialWidgetStore.load(preferences);
                            for (DialWidgetStore.Entry current : entries) {
                                if (current.id.equals(entry.id)) {
                                    current.name = valueName;
                                    current.number = valueNumber;
                                    break;
                                }
                            }
                            DialWidgetStore.save(preferences, entries);
                            TileOrderStore.sync(preferences, requireContext().getPackageManager());
                            android.widget.Toast.makeText(
                                            requireContext(),
                                            "Карточка сохранена",
                                            android.widget.Toast.LENGTH_SHORT)
                                    .show();
                        });
        row.findViewById(R.id.dialSettingDelete)
                .setOnClickListener(
                        v -> {
                            List<DialWidgetStore.Entry> entries = DialWidgetStore.load(preferences);
                            entries.removeIf(current -> current.id.equals(entry.id));
                            DialWidgetStore.save(preferences, entries);
                            TileOrderStore.sync(preferences, requireContext().getPackageManager());
                            refreshSettingsRows();
                        });
        SettingsDesign.styleTree(row);

        return row;
    }

    private View shortcutRow(LinearLayout parent, String packageName) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        android.content.pm.PackageManager packageManager = requireContext().getPackageManager();
        View row = inflater.inflate(R.layout.item_app_shortcut, parent, false);
        android.widget.ImageView icon = row.findViewById(R.id.shortcutIco);
        TextView label = row.findViewById(R.id.shortcutLabel);
        ImageButton deleteButton = row.findViewById(R.id.shortcutDelete);
        String name = packageName;
        try {
            android.content.pm.ApplicationInfo applicationInfo =
                    packageManager.getApplicationInfo(packageName, 0);
            name = packageManager.getApplicationLabel(applicationInfo).toString();
            icon.setImageDrawable(packageManager.getApplicationIcon(applicationInfo));
        } catch (Exception ignored) {
        }
        label.setText(name);
        deleteButton.setOnClickListener(
                v -> {
                    java.util.List<String> updatedPackages = AppShortcutStore.load(preferences);
                    updatedPackages.remove(packageName);
                    AppShortcutStore.save(preferences, updatedPackages);

                    TileOrderStore.sync(preferences, requireContext().getPackageManager());
                    renderAppShortcuts();
                });
        SettingsDesign.styleTree(row);

        return row;
    }

    private View appWidgetRow(LinearLayout parent, AppWidgetStore.Entry entry) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        android.content.pm.PackageManager packageManager = requireContext().getPackageManager();
        entry.ensureProfiles();
        View row = inflater.inflate(R.layout.settings_main_app_widget_header, parent, false);
        android.widget.ImageView icon = row.findViewById(R.id.appWidgetSettingIcon);
        TextView label = row.findViewById(R.id.appWidgetSettingLabel);
        ImageButton delete = row.findViewById(R.id.appWidgetSettingDelete);
        android.widget.Spinner widthSpinner = row.findViewById(R.id.appWidgetSettingWidth);
        android.widget.Spinner heightSpinner = row.findViewById(R.id.appWidgetSettingHeight);
        android.widget.Switch autoStart = row.findViewById(R.id.appWidgetSettingAutoStart);
        String name = entry.packageName;
        try {
            android.content.pm.ApplicationInfo info =
                    packageManager.getApplicationInfo(entry.packageName, 0);
            name = packageManager.getApplicationLabel(info).toString();
            icon.setImageDrawable(packageManager.getApplicationIcon(info));
        } catch (Exception ignored) {
        }
        label.setText("Виджет " + AppWidgetStore.designation(preferences, entry.id) + ": " + name);
        ((TextView) row.findViewById(R.id.settingsAppWidgetSubtitle))
                .setText(entry.profiles.size() + " приложений · запуск внутри карточки");
        android.widget.ArrayAdapter<String> widthAdapter =
                new android.widget.ArrayAdapter<>(
                        requireContext(),
                        R.layout.settings_spinner_item,
                        new String[] {
                            "1 ячейка",
                            "2 ячейки",
                            "3 ячейки",
                            "4 ячейки",
                            "5 ячеек",
                            "6 ячеек",
                            "7 ячеек",
                            "8 ячеек",
                            "9 ячеек",
                            "10 ячеек",
                            "11 ячеек",
                            "12 ячеек"
                        });
        widthAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        widthSpinner.setAdapter(widthAdapter);
        widthSpinner.setSelection(AppWidgetStore.clampWidth(entry.width) - 1);
        widthSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        int value = position + 1;
                        if (entry.width != value) {
                            entry.width = value;
                            AppWidgetStore.update(preferences, entry);
                            TileOrderStore.sync(preferences, requireContext().getPackageManager());
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
        android.widget.ArrayAdapter<String> heightAdapter =
                new android.widget.ArrayAdapter<>(
                        requireContext(),
                        R.layout.settings_spinner_item,
                        new String[] {"1 ячейка", "2 ячейки", "3 ячейки", "4 ячейки", "5 ячеек"});
        heightAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        heightSpinner.setAdapter(heightAdapter);
        heightSpinner.setSelection(AppWidgetStore.clampHeight(entry.height) - 1);
        heightSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        int value = position + 1;
                        if (entry.height != value) {
                            entry.height = value;
                            AppWidgetStore.update(preferences, entry);
                            TileOrderStore.sync(preferences, requireContext().getPackageManager());
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
        autoStart.setChecked(entry.autoStart);

        View delayContainer = row.findViewById(R.id.appWidgetDelayContainer);
        android.widget.SeekBar delaySeek = row.findViewById(R.id.appWidgetSettingDelay);
        TextView delayText = row.findViewById(R.id.appWidgetSettingDelayText);

        delayContainer.setVisibility(entry.autoStart ? View.VISIBLE : View.GONE);
        delaySeek.setProgress(entry.autoStartDelay - 1);
        delayText.setText(entry.autoStartDelay + " сек");

        delaySeek.setOnSeekBarChangeListener(
                new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                        int value = progress + 1;
                        delayText.setText(value + " сек");
                        if (fromUser) {
                            entry.autoStartDelay = value;
                            AppWidgetStore.update(preferences, entry);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
                });

        autoStart.setOnCheckedChangeListener(
                (button, checked) -> {
                    entry.autoStart = checked;
                    delayContainer.setVisibility(checked ? View.VISIBLE : View.GONE);
                    AppWidgetStore.update(preferences, entry);
                    TileOrderStore.sync(preferences, requireContext().getPackageManager());
                });
        delete.setContentDescription("Убрать виджет приложения");
        delete.setOnClickListener(
                v -> {
                    AppWidgetStore.remove(preferences, entry.id);
                    TileOrderStore.sync(preferences, requireContext().getPackageManager());
                    renderAppWidgets();
                });
        SettingsDesign.styleTree(row);

        return row;
    }

    private View appWidgetFooterRow(LinearLayout parent, AppWidgetStore.Entry entry) {
        View row =
                LayoutInflater.from(requireContext())
                        .inflate(R.layout.settings_main_app_widget_footer, parent, false);
        Button addProfile = row.findViewById(R.id.appWidgetAddProfile);
        addProfile.setOnClickListener(
                v ->
                        SettingsAppPicker.show(
                                requireContext(),
                                "Добавить приложение в виджет",
                                (packageName, pickedLabel) -> {
                                    AppWidgetStore.addProfile(
                                            entry, packageName, AppWidgetStore.DEFAULT_DPI);
                                    AppWidgetStore.update(preferences, entry);
                                    TileOrderStore.sync(
                                            preferences, requireContext().getPackageManager());
                                    renderAppWidgets();
                                }));
        SettingsDesign.styleTree(row);
        return row;
    }

    private View widgetProfileFragment(LinearLayout parent, AppWidgetStore.Entry entry, int index) {
        LinearLayout panel = new LinearLayout(requireContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsDesign.dp(panel, 25);
        panel.setPadding(padding, 0, padding, 0);
        if (index < entry.profiles.size()) {
            panel.addView(appWidgetProfileRow(parent, entry, index));
        }
        return SettingsPanels.widgetFragment(panel, false, false);
    }

    private void addAppWidgetRows(List<SettingsList.Row> rows) {
        List<AppWidgetStore.Entry> entries = AppWidgetStore.load(preferences);
        for (int index = 0; index < entries.size(); index += 2) {
            List<AppWidgetStore.Entry> pair =
                    new ArrayList<>(entries.subList(index, Math.min(index + 2, entries.size())));
            String key = "widgets:" + pair.get(0).id;
            rows.add(
                    new SettingsList.Row(
                            key,
                            parent -> {
                                SettingsGrid grid = new SettingsGrid(requireContext(), null);
                                for (AppWidgetStore.Entry entry : pair) {
                                    grid.addView(
                                            SettingsPanels.widgetFragment(
                                                    appWidgetRow(parent, entry), true, false));
                                }
                                return grid;
                            }));
            int profileCount = 0;
            for (AppWidgetStore.Entry entry : pair) {
                profileCount = Math.max(profileCount, entry.profiles.size());
            }
            for (int profileIndex = 0; profileIndex < profileCount; profileIndex++) {
                final int currentProfile = profileIndex;
                rows.add(
                        new SettingsList.Row(
                                key + ":profile:" + profileIndex,
                                parent -> {
                                    SettingsGrid grid = new SettingsGrid(requireContext(), null);
                                    for (AppWidgetStore.Entry entry : pair) {
                                        grid.addView(
                                                widgetProfileFragment(
                                                        parent, entry, currentProfile));
                                    }
                                    return grid;
                                }));
            }
            rows.add(
                    new SettingsList.Row(
                            key + ":footer",
                            parent -> {
                                SettingsGrid grid = new SettingsGrid(requireContext(), null);
                                for (AppWidgetStore.Entry entry : pair) {
                                    grid.addView(
                                            SettingsPanels.widgetFragment(
                                                    appWidgetFooterRow(parent, entry),
                                                    false,
                                                    true));
                                }
                                return SettingsPanels.spaced(grid);
                            }));
        }
    }

    @Override
    protected void bindSettingsRow() {
        bindMainWidgetSwitches();
        bindEnergyAppearance();
        bindTripHistory();
        bindMainGrid();
        bindShowSwitch(R.id.switchShowTaskManagerTile, "showTaskManagerTile", true);
        bindClick(R.id.buttonAddDialWidget, view -> addDialWidget());
        bindClick(R.id.buttonAddAppShortcut, this::onAddAppShortcut);
        bindClick(R.id.buttonAddAppWidget, this::onAddAppWidget);
    }

    @Override
    protected boolean cacheLayout(int layout) {
        return layout != R.layout.settings_main_add_shortcut;
    }

    @Override
    protected View decorateSettingsRow(int layout, View row) {
        if (layout == R.layout.settings_main_add_shortcut) {
            return SettingsPanels.appSelectionPanel(
                    row, AppShortcutStore.load(preferences).isEmpty(), true);
        }
        return row;
    }

    @Override
    protected void addDynamicRows(
            List<SettingsList.Row> rows, SettingsSectionLayouts.DynamicContent kind) {
        switch (kind) {
            case APP_WIDGETS:
                addAppWidgetRows(rows);
                break;
            case DIAL_CARDS:
                for (DialWidgetStore.Entry entry : DialWidgetStore.load(preferences)) {
                    rows.add(
                            new SettingsList.Row(
                                    "dial:" + entry.id, parent -> dialRow(parent, entry)));
                }
                break;
            case SHORTCUTS:
                SettingsPanels.addAppChipRows(
                        rows,
                        AppShortcutStore.load(preferences),
                        "shortcuts",
                        true,
                        this::shortcutRow);
                break;
            default:
                super.addDynamicRows(rows, kind);
        }
    }
}
