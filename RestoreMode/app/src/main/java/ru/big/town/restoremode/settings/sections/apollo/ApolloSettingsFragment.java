package ru.big.town.restoremode.settings.sections.apollo;

import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import ru.big.town.common.VehiclePlatform;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.settings.core.SettingsSectionLayouts;
import ru.big.town.restoremode.vehicle.ApolloSettings;

public final class ApolloSettingsFragment extends SettingsSectionFragment {
    @Override
    protected SettingsSectionLayouts.Item[] sectionLayouts() {
        SettingsSectionLayouts.Item[] rows = super.sectionLayouts();
        if (VehiclePlatform.current().isH97XY()) {
            return rows;
        }
        SettingsSectionLayouts.Item[] withStockMenu =
                new SettingsSectionLayouts.Item[rows.length + 1];
        withStockMenu[0] = rows[0];
        withStockMenu[1] = SettingsSectionLayouts.layout(R.layout.settings_apollo_stock_menu);
        System.arraycopy(rows, 1, withStockMenu, 2, rows.length - 1);
        return withStockMenu;
    }

    @Override
    public SettingsSection section() {
        return SettingsSection.APOLLO;
    }

    private Switch switchApolloTlc,
            switchApolloTrafficLights,
            switchApolloTrafficSigns,
            switchApolloSpeedSigns;

    private RadioGroup apolloGreenSoundGroup;

    private View apolloGreenSoundContainer;

    private TextView textApolloStatus;

    private void initApolloTech() {
        Switch stockMenu = settingView(R.id.switchApolloStockMenu);
        if (stockMenu != null) {
            stockMenu.setChecked(
                    ApolloSettings.stockUiEnabled(preferences, VehiclePlatform.current()));
            stockMenu.setOnCheckedChangeListener(
                    (button, checked) -> {
                        preferences.edit().putBoolean(ApolloSettings.STOCK_UI, checked).apply();
                        requestApolloApply();
                    });
        }
        if (settingView(R.id.switchApolloTlc) != null) {
            switchApolloTlc = settingView(R.id.switchApolloTlc);
        }
        if (settingView(R.id.switchApolloTrafficLights) != null) {
            switchApolloTrafficLights = settingView(R.id.switchApolloTrafficLights);
        }
        if (settingView(R.id.switchApolloTrafficSigns) != null) {
            switchApolloTrafficSigns = settingView(R.id.switchApolloTrafficSigns);
        }
        if (settingView(R.id.switchApolloSpeedSigns) != null) {
            switchApolloSpeedSigns = settingView(R.id.switchApolloSpeedSigns);
        }
        if (settingView(R.id.apolloGreenSoundGroup) != null) {
            apolloGreenSoundGroup = settingView(R.id.apolloGreenSoundGroup);
        }
        if (settingView(R.id.apolloGreenSoundContainer) != null) {
            apolloGreenSoundContainer = settingView(R.id.apolloGreenSoundContainer);
        }
        if (settingView(R.id.textApolloStatus) != null) {
            textApolloStatus = settingView(R.id.textApolloStatus);
        }
        bindApolloSwitch(switchApolloTlc, ApolloSettings.TLC);
        bindApolloSwitch(switchApolloTrafficSigns, ApolloSettings.TRAFFIC_SIGNS);
        bindApolloSwitch(switchApolloSpeedSigns, ApolloSettings.SPEED_SIGNS);
        bindApolloSwitch(switchApolloTrafficLights, ApolloSettings.TRAFFIC_LIGHTS);

        boolean greenSound =
                preferences.getBoolean(ApolloSettings.GREEN_SOUND, ApolloSettings.DEFAULT_ENABLED);
        if (settingView(R.id.apolloGreenSoundGroup) != null) {
            apolloGreenSoundGroup.check(
                    greenSound ? R.id.apolloGreenSoundOn : R.id.apolloGreenSoundOff);
            apolloGreenSoundGroup.setOnCheckedChangeListener(
                    (group, checkedId) -> {
                        if (checkedId != R.id.apolloGreenSoundOn
                                && checkedId != R.id.apolloGreenSoundOff) {
                            return;
                        }
                        preferences
                                .edit()
                                .putBoolean(
                                        ApolloSettings.GREEN_SOUND,
                                        checkedId == R.id.apolloGreenSoundOn)
                                .apply();
                        requestApolloApply();
                    });
        }
        updateApolloUi();
    }

    private void bindApolloSwitch(Switch target, String preference) {
        if (target == null
                || (bindingRoot != null && bindingRoot.findViewById(target.getId()) != target)) {
            return;
        }
        target.setChecked(preferences.getBoolean(preference, ApolloSettings.DEFAULT_ENABLED));
        target.setEnabled(true);
        target.setOnCheckedChangeListener(
                (button, checked) -> {
                    preferences.edit().putBoolean(preference, checked).apply();
                    requestApolloApply();
                    if (ApolloSettings.TRAFFIC_LIGHTS.equals(preference)) {
                        updateApolloUi();
                    }
                });
    }

    private void requestApolloApply() {
        try {
            requireContext()
                    .startForegroundService(
                            new Intent("ru.big.town.anative.APPLY_APOLLO")
                                    .setClassName(
                                            "ru.big.town.anative",
                                            "ru.big.town.anative.SetModesService"));
        } catch (RuntimeException e) {
            Log.w("ApolloSettings", "Saved targets; immediate apply unavailable", e);
        }
    }

    private void updateApolloUi() {
        boolean trafficLightsEnabled =
                preferences.getBoolean(
                        ApolloSettings.TRAFFIC_LIGHTS, ApolloSettings.DEFAULT_ENABLED);
        if (apolloGreenSoundGroup != null) {
            apolloGreenSoundGroup.setEnabled(trafficLightsEnabled);
        }
        if (apolloGreenSoundContainer != null && apolloGreenSoundGroup != null) {
            apolloGreenSoundContainer.setAlpha(trafficLightsEnabled ? 1f : 0.45f);
            for (int i = 0; i < apolloGreenSoundGroup.getChildCount(); i++) {
                apolloGreenSoundGroup.getChildAt(i).setEnabled(trafficLightsEnabled);
            }
        }

        if (textApolloStatus != null) {
            textApolloStatus.setText(
                    "Применение при изменении, кнопкой «Применить», при пробуждении автомобиля "
                            + "и первом включении D после открытия водительской двери.");
        }
    }

    @Override
    protected void bindSettingsRow() {
        initApolloTech();
    }

    @Override
    protected void releaseSettingsRow(View row) {
        if (isDescendant(row, switchApolloTlc)) {
            switchApolloTlc = null;
        }
        if (isDescendant(row, switchApolloTrafficLights)) {
            switchApolloTrafficLights = null;
        }
        if (isDescendant(row, switchApolloTrafficSigns)) {
            switchApolloTrafficSigns = null;
        }
        if (isDescendant(row, switchApolloSpeedSigns)) {
            switchApolloSpeedSigns = null;
        }
        if (isDescendant(row, apolloGreenSoundGroup)) {
            apolloGreenSoundGroup = null;
        }
        if (isDescendant(row, apolloGreenSoundContainer)) {
            apolloGreenSoundContainer = null;
        }
        if (isDescendant(row, textApolloStatus)) {
            textApolloStatus = null;
        }
    }
}
