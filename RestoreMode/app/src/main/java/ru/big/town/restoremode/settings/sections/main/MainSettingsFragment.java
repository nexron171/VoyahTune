package ru.big.town.restoremode.settings.sections.main;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.dashboard.DashboardProfile;
import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;
import ru.big.town.restoremode.dashboard.tiles.TileScrollSnap;
import ru.big.town.restoremode.integration.GlobalVars;
import ru.big.town.restoremode.scenarios.ScenarioStore;
import ru.big.town.restoremode.settings.core.SettingsHost;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.settings.core.SettingsSectionLayouts;
import ru.big.town.restoremode.settings.ui.dialogs.SettingsAppPicker;
import ru.big.town.restoremode.settings.ui.layout.SettingsGrid;
import ru.big.town.restoremode.settings.ui.layout.SettingsPanels;
import ru.big.town.restoremode.settings.ui.layout.SettingsWidgetCard;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.ui.preview.SettingsIllustration;
import ru.big.town.restoremode.settings.ui.preview.WidgetSizeGrid;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;
import ru.big.town.restoremode.widgets.apps.AppShortcutStore;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;
import ru.big.town.restoremode.widgets.brightness.BrightnessWidgetLayout;
import ru.big.town.restoremode.widgets.climate.CabinTemperatureWidgetLayout;
import ru.big.town.restoremode.widgets.climate.RecirculationWidgetLayout;
import ru.big.town.restoremode.widgets.clock.ClockWidgetView;
import ru.big.town.restoremode.widgets.dials.DialWidgetStore;
import ru.big.town.restoremode.widgets.energy.EnergyWidgetPreferences;
import ru.big.town.restoremode.widgets.fuel.FuelFlapWidgetLayout;
import ru.big.town.restoremode.widgets.headlights.HeadlightWidgetLayout;
import ru.big.town.restoremode.widgets.links.LinkBrowserPicker;
import ru.big.town.restoremode.widgets.links.LinkShortcutStore;
import ru.big.town.restoremode.widgets.radio.RadioWidgetLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

public class MainSettingsFragment extends SettingsSectionFragment {
    private SharedPreferences globalPreferences;
    private MainSettingsCategory category;
    private Parcelable menuScroll;

    private final SharedPreferences.OnSharedPreferenceChangeListener orderChanged =
            (prefs, key) -> {
                if (TileOrderStore.ORDER_KEY.equals(key)) refreshDynamicTiles();
            };

    private void refreshDynamicTiles() {
        if (category == MainSettingsCategory.APPLICATIONS
                || category == MainSettingsCategory.CARPLAY
                || category == MainSettingsCategory.LINKS) refreshSettingsRows();
    }

    @Override
    public void onResume() {
        super.onResume();
        preferences.registerOnSharedPreferenceChangeListener(orderChanged);
        refreshDynamicTiles();
    }

    @Override
    public void onPause() {
        preferences.unregisterOnSharedPreferenceChangeListener(orderChanged);
        super.onPause();
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        globalPreferences = preferences;
        preferences = profile().layoutPreferences(requireContext());
    }

    protected DashboardProfile profile() {
        return DashboardProfile.driver();
    }

    @Override
    public SettingsSection section() {
        return SettingsSection.MAIN;
    }

    @Override
    protected void onSectionViewCreated(Bundle state) {
        String savedCategory = state.getString("mainCategory");
        category = savedCategory == null ? null : MainSettingsCategory.valueOf(savedCategory);
        menuScroll = state.getParcelable("mainMenuScroll");
    }

    @Override
    protected void saveSectionState(Bundle state) {
        state.putString("mainCategory", category == null ? null : category.name());
        state.putParcelable("mainMenuScroll", menuScroll);
    }

    @Override
    protected SettingsSectionLayouts.Item[] sectionLayouts() {
        if (category == null) {
            return super.sectionLayouts();
        }
        List<SettingsSectionLayouts.Item> layouts = new ArrayList<>();
        if (profile().isPassenger() && category == MainSettingsCategory.GRID) {
            layouts.add(SettingsSectionLayouts.layout(R.layout.settings_passenger_autostart_row));
        }
        java.util.Collections.addAll(layouts, category.layouts());
        return layouts.toArray(new SettingsSectionLayouts.Item[0]);
    }

    @Override
    protected void showRows(boolean resetScroll) {
        boolean carPlayEnabled =
                ru.big.town.restoremode.carplay.CarPlayFeature.isEnabled(requireContext());
        if (category == MainSettingsCategory.CARPLAY && !carPlayEnabled) {
            category = null;
        }
        List<SettingsList.Row> rows = new ArrayList<>();
        if (category == null) {
            rows.add(
                    layoutRow(
                            profile().isPassenger()
                                    ? R.layout.settings_passenger_intro
                                    : R.layout.settings_main_overview));
            rows.add(
                    new SettingsList.Row(
                            "mainCategories", parent -> categoryGrid(parent, carPlayEnabled)));
            settingsList.submit(rows, resetScroll);
        } else {
            super.showRows(resetScroll);
        }
        if (getActivity() instanceof SettingsHost) {
            ((SettingsHost) requireActivity()).refreshHeader();
        }
    }

    public String categoryTitle() {
        return category == null ? null : category.title;
    }

    private View categoryGrid(LinearLayout parent, boolean carPlayEnabled) {
        SettingsGrid grid =
                (SettingsGrid)
                        getLayoutInflater()
                                .inflate(R.layout.settings_main_categories, parent, false);
        for (MainSettingsCategory item : MainSettingsCategory.values()) {
            if (item == MainSettingsCategory.CARPLAY && !carPlayEnabled) {
                continue;
            }
            View tile =
                    categoryLink(
                            grid,
                            item.title,
                            item.description,
                            () -> {
                                menuScroll = settingsList.getLayoutManager().onSaveInstanceState();
                                category = item;
                                showRows(true);
                            });
            tile.setMinimumHeight(SettingsDesign.dp(tile, 150));
            grid.addView(tile);
        }
        return grid;
    }

    private View categoryLink(ViewGroup parent, String title, String description, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.settings_main_category, parent, false);
        ((TextView) row.findViewById(R.id.mainCategoryTitle)).setText(title);
        ((TextView) row.findViewById(R.id.mainCategoryDescription)).setText(description);
        row.setOnClickListener(view -> action.run());
        return row;
    }

    public boolean navigateUp() {
        if (category == null || settingsList == null) {
            return false;
        }
        category = null;
        showRows(true);
        if (menuScroll != null) {
            settingsList.getLayoutManager().onRestoreInstanceState(menuScroll);
        }
        return true;
    }

    private void syncTiles() {
        TileOrderStore.sync(
                preferences,
                globalPreferences,
                requireContext().getPackageManager(),
                profile().isPassenger());
    }

    private void bindMainWidgetSwitches() {
        bindMediaAutoStart();
        bindShowSwitch(R.id.switchShowPassengerDashboardTile, "showPassengerDashboardTile", false);
        bindShowSwitch(R.id.switchShowTripTimer, "showTripTimer", true);
        bindShowSwitch(R.id.switchShowPowerHold, "showPowerHold", true);
        bindShowSwitch(R.id.switchShowWashMode, "showWashMode", true);
        bindShowSwitch(R.id.switchShowRearFog, "showRearFog", false);
        bindShowSwitch(R.id.switchShowHotspot, "showHotspot", false);
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
        bindShowSwitch(
                R.id.switchShowSuspensionWidget,
                "showSuspensionWidget",
                false,
                R.id.suspensionSizeRow);
        bindShowSwitch(
                R.id.switchShowHeadlights,
                HeadlightWidgetLayout.VISIBLE,
                false,
                R.id.headlightSizeRow);
        bindShowSwitch(R.id.switchShowRecirculation, RecirculationWidgetLayout.VISIBLE, false);
        bindShowSwitch(
                R.id.switchShowFuelFlap, FuelFlapWidgetLayout.VISIBLE, false, R.id.fuelFlapSizeRow);
        bindShowSwitch(R.id.switchShowBrightness, BrightnessWidgetLayout.VISIBLE, false);
        bindShowSwitch(
                R.id.switchShowCabinTemperature, CabinTemperatureWidgetLayout.VISIBLE, false);
        bindShowSwitch(R.id.switchShowRadio, RadioWidgetLayout.VISIBLE, false, R.id.radioSizeRow);
        bindShowSwitch(
                R.id.switchShowClock, "show_" + ClockWidgetView.ID, false, R.id.ClockSizeRow);
        bindShowSwitch(R.id.switchShowCpu, "show_cpuWidget", false, R.id.CpuSizeRow);
        bindShowSwitch(R.id.switchShowRam, "show_ramWidget", false, R.id.RamSizeRow);
        bindShowSwitch(R.id.switchShowClearMemory, "show_clearMemoryWidget", false, 0);
        bindShowSwitch(R.id.switchShowMedia, "show_mediaWidget", false, R.id.MediaSizeRow);
        bindShowSwitch(R.id.switchShowEnergy, "show_energyWidget", false, R.id.EnergySizeRow);
        bindShowSwitch(
                R.id.switchShowEnergyConsumption,
                "show_energyConsumptionWidget",
                false,
                R.id.EnergyConsumptionSizeRow);
        bindShowSwitch(
                R.id.switchShowEnergyTrip, "show_energyTripWidget", false, R.id.EnergyTripSizeRow);
        bindShowSwitch(
                R.id.switchShowTirePressure,
                "show_tirePressureWidget",
                false,
                R.id.TirePressureSizeRow);
        bindShowSwitch(R.id.switchShowOdometer, "show_odometerWidget", false, R.id.OdometerSizeRow);
    }

    private void bindEnergyCapacities() {
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
        switchSaveHistory.setChecked(globalPreferences.getBoolean("saveTripHistory", true));
        switchSaveHistory.setOnCheckedChangeListener(
                (button, checked) -> {
                    globalPreferences.edit().putBoolean("saveTripHistory", checked).apply();
                    Intent intent =
                            new Intent("ru.big.town.anative.TRIP_HISTORY")
                                    .setPackage("ru.big.town.anative");
                    intent.putExtra("enabled", checked);
                    requireContext().sendBroadcast(intent);
                });
    }

    private void bindHiddenScenarios(Button restore) {
        List<ScenarioStore.Scenario> hidden = ScenarioStore.load(globalPreferences);
        hidden.removeIf(
                scenario ->
                        !scenario.enabled
                                || !scenario.showTile
                                || !TileOrderStore.isScenarioHidden(preferences, scenario.id));
        restore.setVisibility(hidden.isEmpty() ? View.GONE : View.VISIBLE);
        restore.setOnClickListener(
                view -> {
                    String[] labels = new String[hidden.size()];
                    for (int index = 0; index < hidden.size(); index++) {
                        labels[index] = hidden.get(index).name;
                    }
                    new android.app.AlertDialog.Builder(requireContext())
                            .setTitle("Вернуть плитку сценария")
                            .setItems(
                                    labels,
                                    (dialog, index) -> {
                                        TileOrderStore.setScenarioHidden(
                                                preferences, hidden.get(index).id, false);
                                        syncTiles();
                                        bindHiddenScenarios(restore);
                                    })
                            .show();
                });
    }

    private void bindMainGrid() {
        if (settingView(R.id.switchFullscreenGrid) == null) {
            return;
        }
        bindHiddenScenarios(settingView(R.id.restoreScenarioTile));
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
        ((View) tileSpacing.getParent())
                .setVisibility(profile().isPassenger() ? View.VISIBLE : View.GONE);
        tileSpacing.setMinValue(0);
        tileSpacing.setMaxValue(24);
        tileSpacing.setValue(
                Math.max(0, Math.min(24, globalPreferences.getInt("tileSpacingDp", 4))));
        tileSpacing.setOnValueChangedListener(
                (picker, oldValue, newValue) ->
                        globalPreferences.edit().putInt("tileSpacingDp", newValue).apply());

        Spinner gridSnapMode = settingView(R.id.gridSnapMode);
        if (gridSnapMode != null) {
            ArrayAdapter<String> modes =
                    new ArrayAdapter<>(
                            requireContext(),
                            R.layout.settings_spinner_item,
                            TileScrollSnap.LABELS);
            modes.setDropDownViewResource(R.layout.settings_spinner_dropdown);
            gridSnapMode.setAdapter(modes);
            gridSnapMode.setSelection(
                    TileScrollSnap.indexOf(
                            TileScrollSnap.fromKey(preferences.getString("gridSnapMode", null))));
            gridSnapMode.setOnItemSelectedListener(
                    new AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(
                                AdapterView<?> parent, View view, int position, long id) {
                            preferences
                                    .edit()
                                    .putString("gridSnapMode", TileScrollSnap.KEYS[position])
                                    .apply();
                        }

                        @Override
                        public void onNothingSelected(AdapterView<?> parent) {}
                    });
        }
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
        syncTiles();
        refreshSettingsRows();
    }

    private void bindShowSwitch(int switchId, String key, boolean defaultValue) {
        bindShowSwitch(switchId, key, defaultValue, 0);
    }

    private boolean defaultVisibility(boolean driverDefault) {
        return profile().defaultVisible(driverDefault);
    }

    private void bindShowSwitch(int switchId, String key, boolean defaultValue, int dependentId) {
        Switch visibilitySwitch = settingView(switchId);
        if (visibilitySwitch == null) {
            return;
        }
        View dependent = dependentId == 0 ? null : settingView(dependentId);
        boolean checked = preferences.getBoolean(key, defaultVisibility(defaultValue));
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

    private void bindEnergyCapacity(int fieldId, String key, float fallback) {
        android.widget.EditText field = settingView(fieldId);
        if (field == null) {
            return;
        }
        field.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        float saved = EnergyWidgetPreferences.capacity(globalPreferences, key, fallback);
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
                        globalPreferences.edit().putFloat(key, parsed).apply();
                        if (GlobalVars.isBound && GlobalVars.serviceMessenger != null) {
                            try {
                                EnergyWidgetPreferences.sync(
                                        GlobalVars.serviceMessenger, globalPreferences);
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

                        syncTiles();
                        renderAppShortcuts();
                    }
                });
    }

    private void renderAppShortcuts() {
        refreshSettingsRows();
    }

    private boolean canAddAppWidget() {
        if (AppWidgetStore.load(preferences).size() < AppWidgetStore.MAX_WIDGETS) {
            return true;
        }
        com.google.android.material.snackbar.Snackbar.make(
                        requireView(),
                        "Можно создать не более 20 виджетов",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .show();
        return false;
    }

    private void onAddAppWidget(View view) {
        if (!canAddAppWidget()) {
            return;
        }
        SettingsAppPicker.show(
                requireContext(),
                "Приложение для виджета",
                (packageName, label) -> {
                    AppWidgetStore.add(preferences, packageName, profile().widgetIdPrefix());
                    syncTiles();
                    renderAppWidgets();
                });
    }

    private void addCarPlayWidgetRows(List<SettingsList.Row> rows) {
        if (!ru.big.town.restoremode.carplay.CarPlayFeature.isEnabled(requireContext())) {
            return;
        }
        List<AppWidgetStore.Entry> entries = AppWidgetStore.load(preferences);
        entries.removeIf(entry -> !CarPlayWidgetSettings.isDedicated(entry));
        if (entries.isEmpty()) {
            entries.add(null);
        }
        for (AppWidgetStore.Entry entry : entries) {
            rows.add(
                    new SettingsList.Row(
                            "carplay:" + (entry == null ? "new" : entry.id),
                            parent -> {
                                View row =
                                        CarPlayWidgetSettings.create(
                                                requireContext(),
                                                parent,
                                                preferences,
                                                profile(),
                                                entry,
                                                this::canAddAppWidget,
                                                this::refreshSettingsRows);
                                useLayoutPreferences(row);
                                return row;
                            }));
        }
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
        if (CarPlayTarget.isCarPlay(profile.packageName)) {
            label.setText(CarPlayTarget.LABEL);
            icon.setImageResource(R.drawable.carplay_home_logo);
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
                            syncTiles();
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
                    syncTiles();
                    renderAppWidgets();
                });
        SettingsDesign.styleTree(profileView);
        return profileView;
    }

    private View linkShortcutRow(LinearLayout parent, LinkShortcutStore.Entry entry) {
        View row =
                LayoutInflater.from(requireContext())
                        .inflate(R.layout.item_link_shortcut_setting, parent, false);
        ((TextView) row.findViewById(R.id.linkShortcutLabel)).setText(entry.name);
        ((TextView) row.findViewById(R.id.linkShortcutAddress))
                .setText(
                        (entry.deepLink
                                        ? "Диплинк"
                                        : LinkBrowserPicker.label(
                                                requireContext(), entry.browserPackage))
                                + " · "
                                + entry.address);
        row.findViewById(R.id.linkShortcutEdit).setOnClickListener(view -> editLinkShortcut(entry));
        row.findViewById(R.id.linkShortcutDelete)
                .setOnClickListener(
                        view -> {
                            LinkShortcutStore.remove(preferences, entry.id);
                            syncTiles();
                            refreshSettingsRows();
                        });
        SettingsDesign.styleTree(row);
        return row;
    }

    private void editLinkShortcut(LinkShortcutStore.Entry entry) {
        View form =
                LayoutInflater.from(requireContext()).inflate(R.layout.dialog_link_shortcut, null);
        EditText name = form.findViewById(R.id.linkShortcutName);
        EditText address = form.findViewById(R.id.linkShortcutUrl);
        android.widget.RadioGroup type = form.findViewById(R.id.linkShortcutType);
        name.setText(entry == null ? "" : entry.name);
        address.setText(entry == null ? "" : entry.address);
        type.check(
                entry != null && entry.deepLink ? R.id.linkShortcutDeepLink : R.id.linkShortcutWeb);
        View browserContainer = form.findViewById(R.id.linkBrowserContainer);
        LinkBrowserPicker browserPicker =
                new LinkBrowserPicker(
                        requireContext(),
                        form.findViewById(R.id.linkBrowserPicker),
                        entry == null ? "" : entry.browserPackage);
        browserContainer.setVisibility(entry != null && entry.deepLink ? View.GONE : View.VISIBLE);
        type.setOnCheckedChangeListener(
                (group, checkedId) ->
                        browserContainer.setVisibility(
                                checkedId == R.id.linkShortcutWeb ? View.VISIBLE : View.GONE));
        SettingsDesign.styleTree(form);
        android.app.AlertDialog dialog =
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle(entry == null ? "Добавить ярлык-ссылку" : "Изменить ярлык-ссылку")
                        .setView(form)
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Сохранить", null)
                        .create();
        dialog.setOnShowListener(
                ignored ->
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        view -> {
                                            if (name.getText().toString().trim().isEmpty()) {
                                                name.setError("Введите название ярлыка");
                                                return;
                                            }
                                            try {
                                                LinkShortcutStore.Entry saved =
                                                        new LinkShortcutStore.Entry(
                                                                entry == null
                                                                        ? java.util
                                                                                .UUID
                                                                                .randomUUID()
                                                                                .toString()
                                                                        : entry.id,
                                                                name.getText().toString(),
                                                                address.getText().toString(),
                                                                type.getCheckedRadioButtonId()
                                                                        == R.id
                                                                                .linkShortcutDeepLink,
                                                                browserPicker.selectedPackage());
                                                LinkShortcutStore.put(preferences, saved);
                                                syncTiles();
                                                refreshSettingsRows();
                                                dialog.dismiss();
                                            } catch (IllegalArgumentException exception) {
                                                address.setError(exception.getMessage());
                                            }
                                        }));
        dialog.show();
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
                            syncTiles();
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
                            syncTiles();
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

                    syncTiles();
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
        android.widget.Switch autoStart = row.findViewById(R.id.appWidgetSettingAutoStart);
        String name = entry.packageName;
        try {
            android.content.pm.ApplicationInfo info =
                    packageManager.getApplicationInfo(entry.packageName, 0);
            name = packageManager.getApplicationLabel(info).toString();
            icon.setImageDrawable(packageManager.getApplicationIcon(info));
        } catch (Exception ignored) {
        }
        if (CarPlayTarget.isCarPlay(entry.packageName)) {
            name = CarPlayTarget.LABEL;
            icon.setImageResource(R.drawable.carplay_home_logo);
        }
        label.setText("Виджет " + AppWidgetStore.designation(preferences, entry.id) + ": " + name);
        boolean carPlayShortcut =
                CarPlayTarget.opensActivity(entry.packageName, entry.width, entry.height);
        ((TextView) row.findViewById(R.id.settingsAppWidgetSubtitle))
                .setText(
                        carPlayShortcut
                                ? getString(R.string.carplay_shortcut_description)
                                : entry.profiles.size() + " приложений · запуск внутри карточки");
        WidgetSizeGrid grid = row.findViewById(R.id.appWidgetSizeGrid);
        grid.bind(
                entry.width,
                entry.height,
                (columns, rows) -> true,
                (columns, rows) -> {
                    entry.width = columns;
                    entry.height = rows;
                    AppWidgetStore.update(preferences, entry);
                    syncTiles();
                    if (CarPlayTarget.isCarPlay(entry.packageName)) {
                        renderAppWidgets();
                    }
                });
        autoStart.setChecked(entry.autoStart && !carPlayShortcut);
        autoStart.setEnabled(!carPlayShortcut);

        View delayContainer = row.findViewById(R.id.appWidgetDelayContainer);
        android.widget.SeekBar delaySeek = row.findViewById(R.id.appWidgetSettingDelay);
        TextView delayText = row.findViewById(R.id.appWidgetSettingDelayText);

        delayContainer.setVisibility(
                entry.autoStart && !carPlayShortcut ? View.VISIBLE : View.GONE);
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
                    syncTiles();
                });
        delete.setContentDescription("Убрать виджет приложения");
        delete.setOnClickListener(
                v -> {
                    AppWidgetStore.remove(preferences, entry.id);
                    syncTiles();
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
                                    syncTiles();
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
        entries.removeIf(CarPlayWidgetSettings::isDedicated);
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

    /** The switch is written to DrivePreferences because Native reads it from the provider. */
    private void bindPassengerAutoStart() {
        Switch autoStart = settingView(R.id.switchPassengerAutoStart);
        if (autoStart == null) {
            return;
        }
        autoStart.setChecked(globalPreferences.getBoolean("passengerAutoStart", false));
        autoStart.setOnCheckedChangeListener(
                (button, checked) ->
                        globalPreferences.edit().putBoolean("passengerAutoStart", checked).apply());
    }

    private void bindMediaAutoStart() {
        Switch autoStart = settingView(R.id.switchMediaAutoStart);
        if (autoStart == null) {
            return;
        }
        autoStart.setChecked(globalPreferences.getBoolean("mediaAutoStartLastPlayer", false));
        autoStart.setOnCheckedChangeListener(
                (button, checked) ->
                        globalPreferences
                                .edit()
                                .putBoolean("mediaAutoStartLastPlayer", checked)
                                .apply());
    }

    @Override
    protected void bindSettingsRow() {
        bindPassengerAutoStart();
        bindMainWidgetSwitches();
        bindEnergyCapacities();
        bindTripHistory();
        bindMainGrid();
        bindShowSwitch(R.id.switchShowTaskManagerTile, "showTaskManagerTile", true);
        bindShowSwitch(R.id.switchShowAndroidSettings, "showAndroidSettings", true);
        bindClick(R.id.buttonAddLinkShortcut, view -> editLinkShortcut(null));
        bindClick(R.id.buttonAddDialWidget, view -> addDialWidget());
        bindClick(R.id.buttonAddAppShortcut, this::onAddAppShortcut);
        bindClick(R.id.buttonAddAppWidget, this::onAddAppWidget);
    }

    @Override
    protected boolean cacheLayout(int layout) {
        return layout != R.layout.settings_main_add_shortcut
                && layout != R.layout.settings_main_carplay_heading;
    }

    @Override
    protected View decorateSettingsRow(int layout, View row) {
        if (layout == R.layout.settings_main_carplay_heading) {
            row.setVisibility(
                    ru.big.town.restoremode.carplay.CarPlayFeature.isEnabled(requireContext())
                            ? View.VISIBLE
                            : View.GONE);
        }
        useLayoutPreferences(row);
        if (layout == R.layout.settings_main_add_shortcut) {
            return SettingsPanels.appSelectionPanel(
                    row, AppShortcutStore.load(preferences).isEmpty(), true);
        }
        return row;
    }

    private void useLayoutPreferences(View view) {
        if (view instanceof SettingsWidgetCard) {
            ((SettingsWidgetCard) view).usePreferences(preferences);
        } else if (view instanceof WidgetSizeGrid) {
            ((WidgetSizeGrid) view).usePreferences(preferences);
        } else if (view instanceof SettingsIllustration) {
            ((SettingsIllustration) view).usePreferences(preferences);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                useLayoutPreferences(group.getChildAt(index));
            }
        }
    }

    private <T> void addShortcutRows(
            List<SettingsList.Row> rows,
            List<T> entries,
            String prefix,
            Function<T, String> identifier,
            BiFunction<LinearLayout, T, View> factory) {
        final int columns = 2;
        for (int index = 0; index < entries.size(); index += columns) {
            List<T> group =
                    new ArrayList<>(
                            entries.subList(index, Math.min(index + columns, entries.size())));
            rows.add(
                    new SettingsList.Row(
                            prefix + identifier.apply(group.get(0)),
                            parent -> {
                                SettingsGrid grid = new SettingsGrid(requireContext(), null);
                                for (T entry : group) {
                                    View content = factory.apply(grid, entry);
                                    grid.addView(
                                            SettingsPanels.appSelectionPanel(content, true, true));
                                }
                                return SettingsPanels.spaced(grid);
                            }));
        }
    }

    @Override
    protected void addDynamicRows(
            List<SettingsList.Row> rows, SettingsSectionLayouts.DynamicContent kind) {
        switch (kind) {
            case CARPLAY_WIDGETS:
                addCarPlayWidgetRows(rows);
                break;
            case APP_WIDGETS:
                addAppWidgetRows(rows);
                break;
            case LINK_SHORTCUTS:
                addShortcutRows(
                        rows,
                        LinkShortcutStore.load(preferences),
                        "link:",
                        entry -> entry.id,
                        this::linkShortcutRow);
                break;
            case DIAL_CARDS:
                addShortcutRows(
                        rows,
                        DialWidgetStore.load(preferences),
                        "dial:",
                        entry -> entry.id,
                        this::dialRow);
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
