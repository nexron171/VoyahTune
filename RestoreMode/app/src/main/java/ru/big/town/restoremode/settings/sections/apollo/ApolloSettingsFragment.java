package ru.big.town.restoremode.settings.sections.apollo;

import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.vehicle.ApolloSettings;

public final class ApolloSettingsFragment extends SettingsSectionFragment {
    @Override
    public SettingsSection section() {
        return SettingsSection.APOLLO;
    }

    private Switch switchApolloTlc,
            switchApolloTrafficLights,
            switchApolloTrafficSigns,
            switchApolloSpeedSigns,
            switchApolloSpeedWarning;

    private RadioGroup apolloSpeedModeGroup;

    private View apolloSpeedOptionsContainer;

    private RadioGroup apolloGreenSoundGroup;

    private View apolloGreenSoundContainer;

    private TextView textApolloStatus;

    private void initApolloTech() {
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
        boolean od = ru.big.town.common.InfrastructureProfile.read(requireContext()).usesAccHooks();
        if (settingView(R.id.apolloSpeedOptionsContainer) != null) {
            apolloSpeedOptionsContainer = settingView(R.id.apolloSpeedOptionsContainer);
        }
        if (apolloSpeedOptionsContainer != null) {
            apolloSpeedOptionsContainer.setVisibility(od ? View.VISIBLE : View.GONE);
        }
        if (settingView(R.id.apolloSpeedModeGroup) != null) {
            apolloSpeedModeGroup = settingView(R.id.apolloSpeedModeGroup);
        }
        if (settingView(R.id.switchApolloSpeedWarning) != null) {
            switchApolloSpeedWarning = settingView(R.id.switchApolloSpeedWarning);
        }
        if (od && settingView(R.id.apolloSpeedModeGroup) != null) {
            int mode = ApolloSettings.speedMode(preferences);
            apolloSpeedModeGroup.check(
                    mode == ApolloSettings.AUTO_CORRECTION
                            ? R.id.apolloSpeedAutomatic
                            : mode == ApolloSettings.CONFIRM_CORRECTION
                                    ? R.id.apolloSpeedConfirm
                                    : R.id.apolloSpeedRecognition);
            apolloSpeedModeGroup.setOnCheckedChangeListener(
                    (group, checkedId) -> {
                        if (!switchApolloSpeedSigns.isChecked()) {
                            return;
                        }
                        int selected;
                        if (checkedId == R.id.apolloSpeedAutomatic) {
                            selected = ApolloSettings.AUTO_CORRECTION;
                        } else if (checkedId == R.id.apolloSpeedConfirm) {
                            selected = ApolloSettings.CONFIRM_CORRECTION;
                        } else if (checkedId == R.id.apolloSpeedRecognition) {
                            selected = ApolloSettings.RECOGNITION_ONLY;
                        } else {
                            return;
                        }
                        preferences
                                .edit()
                                .putInt(ApolloSettings.SPEED_MODE, selected)
                                .remove(ApolloSettings.CRUISE_SPEED_ADJUSTMENT)
                                .apply();
                        applyApolloTargets();
                    });
            bindApolloSwitch(switchApolloSpeedWarning, ApolloSettings.SPEED_WARNING);
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
                        applyApolloTargets();
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
                    applyApolloTargets();
                    if (ApolloSettings.TRAFFIC_LIGHTS.equals(preference)
                            || ApolloSettings.SPEED_SIGNS.equals(preference)) {
                        updateApolloUi();
                    }
                });
    }

    private void applyApolloTargets() {
        if (!ru.big.town.common.InfrastructureProfile.read(requireContext()).usesAccHooks()) {
            return;
        }
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
        boolean speedSignsEnabled =
                preferences.getBoolean(ApolloSettings.SPEED_SIGNS, ApolloSettings.DEFAULT_ENABLED);
        if (apolloSpeedOptionsContainer != null) {
            apolloSpeedOptionsContainer.setAlpha(speedSignsEnabled ? 1f : 0.45f);
        }
        if (apolloSpeedModeGroup != null) {
            apolloSpeedModeGroup.setEnabled(speedSignsEnabled);
            for (int i = 0; i < apolloSpeedModeGroup.getChildCount(); i++) {
                apolloSpeedModeGroup.getChildAt(i).setEnabled(speedSignsEnabled);
            }
        }
        if (switchApolloSpeedWarning != null) {
            switchApolloSpeedWarning.setEnabled(speedSignsEnabled);
        }
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
                    ru.big.town.common.InfrastructureProfile.read(requireContext()).usesAccHooks()
                            ? "Применение при изменении, кнопкой «Применить» и при пробуждении "
                                    + "автомобиля."
                            : "Применение кнопкой «Применить» и через 10 секунд после "
                                    + "пробуждения.");
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
        if (isDescendant(row, switchApolloSpeedWarning)) {
            switchApolloSpeedWarning = null;
        }
        if (isDescendant(row, apolloSpeedOptionsContainer)) {
            apolloSpeedOptionsContainer = null;
        }
        if (isDescendant(row, apolloSpeedModeGroup)) {
            apolloSpeedModeGroup = null;
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
