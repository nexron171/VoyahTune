package ru.big.town.restoremode.settings.sections.vehicle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.integration.GlobalVars;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.settings.ui.controls.SettingsControls;
import ru.big.town.restoremode.vehicle.DriveSelectionPreferences;
import ru.big.town.restoremode.vehicle.FragranceSettings;

public final class VehicleSettingsFragment extends SettingsSectionFragment {
    @Override
    public SettingsSection section() {
        return SettingsSection.VEHICLE;
    }

    private RadioGroup autoLightGroup;

    private TextView textSensorLevel;

    private CheckBox checkBox34;

    static final int MSG_AUTO_LIGHT_ENABLE = 10;

    static final int MSG_AUTO_LIGHT_DISABLE = 11;

    static final int MSG_APPLY_SUSPENSION_MAINTENANCE = 37;

    static final int MSG_APPLY_FORCED_EV = 35;

    private static final String NATIVE_PACKAGE = "ru.big.town.anative";

    private static final String ACTION_BATTERY_HEAT_AUTO_CHANGED =
            "ru.big.town.anative.BATTERY_HEAT_AUTO_CHANGED";

    private static final String EXTRA_BATTERY_HEAT_AUTO_ENABLED = "autoEnabled";

    private static final String ACTION_MODE_REMEMBER_CHANGED =
            "ru.big.town.anative.MODE_REMEMBER_CHANGED";

    private static final String EXTRA_MODE_KEY = "modeKey";

    private static final String EXTRA_REMEMBER_LAST = "rememberLast";

    private final BroadcastReceiver luxReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int sensorLevel = intent.getIntExtra("sensorLevel", -1);
                    if (textSensorLevel != null) {
                        textSensorLevel.setText(
                                sensorLevel >= 0 ? "Датчик: " + sensorLevel : "Датчик: —");
                    }
                }
            };

    private boolean syncingModeUi;

    private final BroadcastReceiver modeSyncReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String mode = intent.getStringExtra("mode");
                    if (mode == null || mode.isEmpty()) {
                        return;
                    }
                    String modeKey = intent.getStringExtra("modeKey");
                    if (modeKey == null) {
                        modeKey =
                                intent.getBooleanExtra("isEnergy", false) ? "energy" : "driveMode";
                    }
                    String rememberKey =
                            "energy".equals(modeKey)
                                    ? "energyRememberLast"
                                    : "recycle".equals(modeKey)
                                            ? "recycleRememberLast"
                                            : "driveRememberLast";
                    if (!preferences.getBoolean(rememberKey, true)) {
                        return;
                    }
                    int groupId =
                            "energy".equals(modeKey)
                                    ? R.id.energy_modes_group
                                    : "recycle".equals(modeKey)
                                            ? R.id.recycle_modes_group
                                            : R.id.drive_modes_group;
                    RadioGroup g = settingView(groupId);
                    syncingModeUi = true;
                    try {
                        if (g != null) {
                            SettingsControls.checkRadioByTag(g, mode);
                        }
                    } finally {
                        syncingModeUi = false;
                    }
                }
            };

    private boolean syncingSettingUi;

    private final BroadcastReceiver settingSyncReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String key = intent.getStringExtra("key");
                    if (key == null || !intent.hasExtra("value")) {
                        return;
                    }
                    boolean value = intent.getBooleanExtra("value", false);
                    preferences.edit().putBoolean(key, value).apply();
                    syncingSettingUi = true;
                    try {
                        if ("forcedEv".equals(key)) {
                            RadioGroup group = settingView(R.id.forcedEvGroup);
                            if (group != null) {
                                group.check(value ? R.id.forcedEvOn : R.id.forcedEvOff);
                            }
                        } else if ("autoLight".equals(key)) {
                            if (autoLightGroup != null) {
                                autoLightGroup.check(value ? R.id.autoLightOn : R.id.autoLightOff);
                            }
                        } else if ("suspensionMaintenance".equals(key)) {
                            Switch toggle = settingView(R.id.switchSuspensionMaintenance);
                            if (toggle != null) {
                                toggle.setChecked(value);
                            }
                        } else if ("disablePedestrianSound".equals(key)) {
                            RadioGroup group = settingView(R.id.pedestrianSoundGroup);
                            if (group != null) {
                                group.check(
                                        value ? R.id.pedestrianSoundOn : R.id.pedestrianSoundOff);
                            }
                        }
                    } finally {
                        syncingSettingUi = false;
                    }
                }
            };

    private void bindBatteryHeating() {
        if (settingView(R.id.switchBatteryHeatAuto) == null) {
            return;
        }
        Switch switchBatteryHeat = settingView(R.id.switchBatteryHeatAuto);
        if (switchBatteryHeat != null) {
            switchBatteryHeat.setChecked(preferences.getBoolean("batteryHeatAuto", false));
            switchBatteryHeat.setOnCheckedChangeListener(
                    (b, checked) -> {
                        preferences.edit().putBoolean("batteryHeatAuto", checked).apply();
                        Intent changed =
                                new Intent(ACTION_BATTERY_HEAT_AUTO_CHANGED)
                                        .setPackage(NATIVE_PACKAGE)
                                        .putExtra(EXTRA_BATTERY_HEAT_AUTO_ENABLED, checked);
                        requireContext().sendBroadcast(changed);
                    });
        }
    }

    private void bindWiperColdMode() {
        if (settingView(R.id.switchWiperCold) == null) {
            return;
        }
        Switch switchWiperCold = settingView(R.id.switchWiperCold);
        switchWiperCold.setChecked(preferences.getBoolean("wiperColdMode", false));
        switchWiperCold.setOnCheckedChangeListener(
                (b, checked) -> preferences.edit().putBoolean("wiperColdMode", checked).apply());
    }

    private void bindMediaPause() {
        if (settingView(R.id.switchPauseMediaOnDoor) == null) {
            return;
        }
        Switch switchPauseMedia = settingView(R.id.switchPauseMediaOnDoor);
        if (switchPauseMedia != null) {
            switchPauseMedia.setChecked(preferences.getBoolean("pauseMediaOnDoor", false));
            switchPauseMedia.setOnCheckedChangeListener(
                    (b, checked) ->
                            preferences.edit().putBoolean("pauseMediaOnDoor", checked).apply());
        }
    }

    private void initPedestrianSoundGroup() {
        RadioGroup group = settingView(R.id.pedestrianSoundGroup);
        if (group == null) {
            return;
        }
        boolean disabled = preferences.getBoolean("disablePedestrianSound", false);
        group.check(disabled ? R.id.pedestrianSoundOn : R.id.pedestrianSoundOff);
        group.setOnCheckedChangeListener(
                (g, checkedId) -> {
                    if (syncingSettingUi) {
                        return;
                    }
                    boolean off = (checkedId == R.id.pedestrianSoundOn);
                    preferences.edit().putBoolean("disablePedestrianSound", off).apply();
                    Log.i("$$$ Advance pedestrian $$$", off ? "DISABLED (muted)" : "ENABLED");
                });
    }

    private void initSuspensionMaintenance() {
        Switch toggle = settingView(R.id.switchSuspensionMaintenance);
        toggle.setChecked(preferences.getBoolean("suspensionMaintenance", false));
        toggle.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (syncingSettingUi) {
                        return;
                    }
                    preferences.edit().putBoolean("suspensionMaintenance", enabled).apply();
                    if (GlobalVars.isBound && GlobalVars.serviceMessenger != null) {
                        try {
                            GlobalVars.serviceMessenger.send(
                                    Message.obtain(
                                            null,
                                            MSG_APPLY_SUSPENSION_MAINTENANCE,
                                            enabled ? 1 : 0,
                                            0));
                        } catch (RemoteException e) {
                            Log.w("VoyahSuspension", "Service unavailable", e);
                        }
                    }
                });
    }

    private void initForcedEvGroup() {
        RadioGroup group = settingView(R.id.forcedEvGroup);
        if (group == null) {
            return;
        }
        boolean on = preferences.getBoolean("forcedEv", false);
        group.check(on ? R.id.forcedEvOn : R.id.forcedEvOff);
        group.setOnCheckedChangeListener(
                (g, checkedId) -> {
                    if (syncingSettingUi) {
                        return;
                    }
                    boolean enabled = (checkedId == R.id.forcedEvOn);
                    preferences.edit().putBoolean("forcedEv", enabled).apply();
                    sendForcedEv(enabled);
                    Log.i("$$$ Advance forcedEV $$$", enabled ? "ON" : "OFF");
                });
    }

    private void sendForcedEv(boolean on) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance forcedEV $$$", "SetModesService не забинден");
            return;
        }
        try {
            GlobalVars.serviceMessenger.send(
                    Message.obtain(null, MSG_APPLY_FORCED_EV, on ? 1 : 0, 0));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void initModeRadios() {
        RadioGroup drive = settingView(R.id.drive_modes_group);
        RadioGroup energy = settingView(R.id.energy_modes_group);
        View intelligent = settingView(R.id.SMART);
        if (intelligent != null) {
            intelligent.setVisibility(
                    preferences.getBoolean("checkBox34", false) ? View.GONE : View.VISIBLE);
        }
        RadioGroup recycle = settingView(R.id.recycle_modes_group);
        SettingsControls.checkRadioByTag(drive, preferences.getString("driveMode", "INDIVIDUAL"));
        SettingsControls.checkRadioByTag(energy, preferences.getString("energy", "SREV"));
        SettingsControls.checkRadioByTag(recycle, preferences.getString("recycle", "LOW"));
        if (drive != null) {
            drive.setOnCheckedChangeListener((g, id) -> saveRadio("driveMode", id));
        }
        // Selecting the already checked pinned profile also relinquishes a widget override.
        if (drive != null) {
            for (int i = 0; i < drive.getChildCount(); i++) {
                View child = drive.getChildAt(i);
                if (child instanceof RadioButton) {
                    child.setOnClickListener(v -> saveRadio("driveMode", v.getId()));
                }
            }
        }
        if (energy != null) {
            energy.setOnCheckedChangeListener((g, id) -> saveRadio("energy", id));
        }
        if (recycle != null) {
            recycle.setOnCheckedChangeListener((g, id) -> saveRadio("recycle", id));
        }
    }

    private void saveRadio(String key, int checkedId) {
        if (syncingModeUi) {
            return;
        }
        View v = settingView(checkedId);
        if (v != null && v.getTag() != null) {
            if ("driveMode".equals(key)) {
                DriveSelectionPreferences.select(
                        preferences,
                        v.getTag().toString(),
                        ru.big.town.common.DriveSelectionPolicy.SETTINGS);
            } else if ("energy".equals(key)) {
                DriveSelectionPreferences.selectEnergy(preferences, v.getTag().toString(), true);
            } else {
                preferences.edit().putString(key, v.getTag().toString()).apply();
            }
            Log.i("$$$ Advance mode $$$", key + "=" + v.getTag());
        }
    }

    private void initModeEnableToggles() {
        setupEnableSwitch(R.id.switchDriveMode, R.id.drive_modes_group, "driveEnabled");
        setupEnableSwitch(R.id.switchEnergy, R.id.energy_modes_group, "energyEnabled");
        setupEnableSwitch(R.id.switchRecycle, R.id.recycle_modes_group, "recycleEnabled");
    }

    private void initModeRememberLastToggles() {
        bindRememberLastSwitch(R.id.switchDriveRememberLast, "driveRememberLast", "driveMode");
        bindRememberLastSwitch(R.id.switchEnergyRememberLast, "energyRememberLast", "energy");
        bindRememberLastSwitch(R.id.switchRecycleRememberLast, "recycleRememberLast", "recycle");
    }

    private void bindRememberLastSwitch(int switchId, String prefKey, String modeKey) {
        Switch visibilitySwitch = settingView(switchId);
        if (visibilitySwitch == null) {
            return;
        }
        visibilitySwitch.setChecked(preferences.getBoolean(prefKey, true));
        visibilitySwitch.setOnCheckedChangeListener(
                (button, checked) -> {
                    preferences.edit().putBoolean(prefKey, checked).apply();
                    Intent changed =
                            new Intent(ACTION_MODE_REMEMBER_CHANGED)
                                    .setPackage(NATIVE_PACKAGE)
                                    .putExtra(EXTRA_MODE_KEY, modeKey)
                                    .putExtra(EXTRA_REMEMBER_LAST, checked);
                    requireContext().sendBroadcast(changed);
                });
    }

    private void initFragranceSettings() {
        Switch enabledSwitch = settingView(R.id.switchFragrance);
        RadioGroup tasteGroup = settingView(R.id.fragranceTasteGroup);
        RadioGroup durationGroup = settingView(R.id.fragranceDurationGroup);
        RadioGroup intensityGroup = settingView(R.id.fragranceIntensityGroup);

        int taste =
                FragranceSettings.normalizeTaste(
                        preferences.getInt(
                                FragranceSettings.TASTE, FragranceSettings.DEFAULT_TASTE));
        int duration =
                FragranceSettings.normalizeDuration(
                        preferences.getInt(
                                FragranceSettings.DURATION, FragranceSettings.DEFAULT_DURATION));
        int intensity =
                FragranceSettings.normalizeIntensity(
                        preferences.getInt(
                                FragranceSettings.INTENSITY, FragranceSettings.DEFAULT_INTENSITY));
        SettingsControls.checkRadioByTag(tasteGroup, String.valueOf(taste));
        SettingsControls.checkRadioByTag(durationGroup, String.valueOf(duration));
        SettingsControls.checkRadioByTag(intensityGroup, String.valueOf(intensity));

        bindIntRadio(tasteGroup, FragranceSettings.TASTE);
        bindIntRadio(durationGroup, FragranceSettings.DURATION);
        bindIntRadio(intensityGroup, FragranceSettings.INTENSITY);

        boolean enabled =
                preferences.getBoolean(
                        FragranceSettings.ENABLED, FragranceSettings.DEFAULT_ENABLED);
        if (enabledSwitch != null) {
            enabledSwitch.setChecked(enabled);
            enabledSwitch.setOnCheckedChangeListener(
                    (button, checked) -> {
                        preferences.edit().putBoolean(FragranceSettings.ENABLED, checked).apply();
                        applyFragranceEnabled(checked);
                    });
        }
        applyFragranceEnabled(enabled);
    }

    private void bindIntRadio(RadioGroup group, String key) {
        if (group == null) {
            return;
        }
        group.setOnCheckedChangeListener(
                (g, checkedId) -> {
                    View selected = settingView(checkedId);
                    if (selected == null || selected.getTag() == null) {
                        return;
                    }
                    try {
                        preferences
                                .edit()
                                .putInt(key, Integer.parseInt(selected.getTag().toString()))
                                .apply();
                    } catch (NumberFormatException e) {
                        Log.e("$$$ Advance fragrance $$$", "Invalid " + key + " tag", e);
                    }
                });
    }

    private void applyFragranceEnabled(boolean enabled) {
        applyModeToggle(R.id.fragranceTasteGroup, enabled);
        applyModeToggle(R.id.fragranceDurationGroup, enabled);
        applyModeToggle(R.id.fragranceIntensityGroup, enabled);
    }

    private void setupEnableSwitch(int switchId, int groupId, String key) {
        Switch visibilitySwitch = settingView(switchId);
        if (visibilitySwitch == null) {
            return;
        }
        boolean enabled = preferences.getBoolean(key, false);
        visibilitySwitch.setChecked(enabled);
        applyModeToggle(groupId, enabled);
        visibilitySwitch.setOnCheckedChangeListener(
                (commandButton, checked) -> {
                    preferences.edit().putBoolean(key, checked).apply();
                    applyModeToggle(groupId, checked);
                });
    }

    private void applyModeToggle(int groupId, boolean enabled) {
        RadioGroup group = settingView(groupId);
        if (group == null) {
            return;
        }
        group.setAlpha(enabled ? 1.0f : 0.4f);
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setEnabled(enabled);
            group.getChildAt(i).setClickable(enabled);
        }
    }

    private void initCheckBox34() {
        checkBox34 = settingView(R.id.checkBox34);
        checkBox34.setChecked(preferences.getBoolean("checkBox34", false));
        checkBox34.setText("");
    }

    private void onCheckBox34Click(View view) {
        boolean hidden = ((CheckBox) view).isChecked();
        preferences.edit().putBoolean("checkBox34", hidden).apply();
        View intelligent = settingView(R.id.SMART);
        if (intelligent != null) {
            intelligent.setVisibility(hidden ? View.GONE : View.VISIBLE);
        }
    }

    private void initAutoLight() {
        autoLightGroup = settingView(R.id.autoLightGroup);
        textSensorLevel = settingView(R.id.textSensorLevel);
        if (autoLightGroup == null) {
            return;
        }

        boolean on = preferences.getBoolean("autoLight", false);
        autoLightGroup.check(on ? R.id.autoLightOn : R.id.autoLightOff);
        autoLightGroup.setOnCheckedChangeListener(
                (group, checkedId) -> {
                    if (syncingSettingUi) {
                        return;
                    }
                    boolean enabled = (checkedId == R.id.autoLightOn);
                    preferences.edit().putBoolean("autoLight", enabled).apply();
                    sendAutoLightMessage(enabled);
                    if (!enabled && textSensorLevel != null) {
                        textSensorLevel.setText("Датчик: —");
                    }
                    Log.i("$$$ Advance autolight $$$", enabled ? "ON" : "OFF");
                });
    }

    private void sendAutoLightMessage(boolean enable) {
        int what = enable ? MSG_AUTO_LIGHT_ENABLE : MSG_AUTO_LIGHT_DISABLE;
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w(
                    "$$$ Advance autolight $$$",
                    "SetModesService не забинден — состояние применится позже");
            return;
        }
        try {
            GlobalVars.serviceMessenger.send(Message.obtain(null, what));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void bindSettingsRow() {
        bindBatteryHeating();
        bindWiperColdMode();
        bindMediaPause();
        initModeRadios();
        initModeEnableToggles();
        initModeRememberLastToggles();
        initFragranceSettings();
        initPedestrianSoundGroup();
        initForcedEvGroup();
        if (settingView(R.id.checkBox34) != null) {
            initCheckBox34();
            bindClick(R.id.checkBox34, this::onCheckBox34Click);
        }
        if (settingView(R.id.autoLightGroup) != null) {
            initAutoLight();
        }
        if (settingView(R.id.switchSuspensionMaintenance) != null) {
            initSuspensionMaintenance();
        }
    }

    @Override
    protected void releaseSettingsRow(View row) {
        if (isDescendant(row, autoLightGroup)) {
            autoLightGroup = null;
        }
        if (isDescendant(row, textSensorLevel)) {
            textSensorLevel = null;
        }
        if (isDescendant(row, checkBox34)) {
            checkBox34 = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (autoLightGroup != null) {
            syncingSettingUi = true;
            autoLightGroup.check(
                    preferences.getBoolean("autoLight", false)
                            ? R.id.autoLightOn
                            : R.id.autoLightOff);
            syncingSettingUi = false;
        }
        Context context = requireContext();
        context.registerReceiver(
                luxReceiver,
                new IntentFilter("ru.big.town.anative.LUX_UPDATE"),
                Context.RECEIVER_EXPORTED);
        context.registerReceiver(
                modeSyncReceiver,
                new IntentFilter("ru.big.town.anative.MODE_SYNCED"),
                Context.RECEIVER_EXPORTED);
        context.registerReceiver(
                settingSyncReceiver,
                new IntentFilter("ru.big.town.anative.SETTING_SYNCED"),
                "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE",
                null,
                Context.RECEIVER_EXPORTED);
        context.sendBroadcast(
                new Intent("ru.big.town.anative.REQUEST_LUX_UPDATE").setPackage(NATIVE_PACKAGE));
    }

    @Override
    public void onPause() {
        Context context = requireContext();
        context.unregisterReceiver(luxReceiver);
        context.unregisterReceiver(modeSyncReceiver);
        context.unregisterReceiver(settingSyncReceiver);
        super.onPause();
    }
}
