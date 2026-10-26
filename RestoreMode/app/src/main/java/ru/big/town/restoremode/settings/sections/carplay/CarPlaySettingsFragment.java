package ru.big.town.restoremode.settings.sections.carplay;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.shilapi.xcertplay.CarPlaySettingsAccess;
import com.shilapi.xcertplay.DiPlayActivity;
import com.shilapi.xcertplay.media.CarPlayAudioSettings;

import ru.big.town.common.CarPlayAudioProtocol;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.carplay.CarPlayAudioClient;
import ru.big.town.restoremode.carplay.CarPlayFeature;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;

import java.util.List;

public final class CarPlaySettingsFragment extends SettingsSectionFragment {
    private static final List<Integer> UI_SCALE_PRESETS = CarPlaySettingsAccess.uiScalePresets();
    private static final int[] UI_SCALE_LABELS = {
        R.id.carPlayUiScalePreset75, R.id.carPlayUiScalePreset85,
        R.id.carPlayUiScalePreset100, R.id.carPlayUiScalePreset115
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean updating;
    private boolean stopping;
    private boolean uiScaleDragging;
    private CarPlayAudioClient audio;
    private CarPlayAudioClient.Volumes volumes = new CarPlayAudioClient.Volumes(null, null);
    private final boolean[] dragging = new boolean[4];
    private final boolean[] pending = new boolean[4];
    private final Runnable[] volumeWrites = new Runnable[4];
    private static final int[] SLIDERS = {
        R.id.carPlayMediaVolume,
        R.id.carPlayNavigationVolume,
        R.id.carPlayPhoneVolume,
        R.id.carPlaySiriVolume
    };
    private static final int[] LABELS = {
        R.id.carPlayMediaVolumeLabel,
        R.id.carPlayNavigationVolumeLabel,
        R.id.carPlayPhoneVolumeLabel,
        R.id.carPlaySiriVolumeLabel
    };
    private static final int[] TITLES = {
        R.string.carplay_volume_media,
        R.string.carplay_volume_navigation,
        R.string.carplay_volume_phone,
        R.string.carplay_volume_siri
    };
    private final Runnable refresh =
            new Runnable() {
                @Override
                public void run() {
                    updateState();
                    if (audio != null && CarPlayFeature.isEnabled(requireContext())) {
                        audio.query();
                    }
                    handler.postDelayed(this, 1000);
                }
            };

    @Override
    public SettingsSection section() {
        return SettingsSection.CARPLAY;
    }

    @Override
    protected void onSectionViewCreated(Bundle state) {
        // Rows are inflated lazily, potentially after onResume has bound the service.
        audio = new CarPlayAudioClient(requireContext(), this::updateVolumes);
    }

    @Override
    protected void bindSettingsRow() {
        cancelVolumeWrites();
        Switch carPlayEnabled = settingView(R.id.carPlayEnabled);
        carPlayEnabled.setSaveEnabled(false);
        carPlayEnabled.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (updating) {
                        return;
                    }
                    if (!checked) {
                        cancelVolumeWrites();
                    }
                    CarPlayFeature.setEnabled(requireContext(), checked);
                    if (audio != null) {
                        if (checked && isResumed()) {
                            audio.start();
                        } else {
                            audio.stop();
                        }
                    }
                    updateState();
                });
        Switch fullscreen = settingView(R.id.carPlayFullscreen);
        fullscreen.setSaveEnabled(false);
        fullscreen.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (updating || !CarPlayFeature.isEnabled(requireContext())) {
                        return;
                    }
                    CarPlayFeature.setFullscreen(requireContext(), checked);
                });
        Switch ultra = settingView(R.id.carPlayUltra);
        ultra.setSaveEnabled(false);
        ultra.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (!updating) {
                        CarPlaySettingsAccess.setUltra(requireContext(), checked);
                    }
                });
        bindUiScale();
        for (int i = 0; i < SLIDERS.length; i++) {
            bindVolume(i);
        }
        updateVolumes(volumes);
        Switch duckMusic = settingView(R.id.carPlayDuckMusic);
        duckMusic.setSaveEnabled(false);
        duckMusic.setChecked(CarPlayAudioSettings.duckMusic(requireContext()));
        duckMusic.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (!updating) {
                        CarPlayAudioSettings.setDuckMusic(requireContext(), enabled);
                    }
                });
        Switch theme = settingView(R.id.carPlayThemeByLight);
        theme.setSaveEnabled(false);
        theme.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (!updating) {
                        CarPlaySettingsAccess.setThemeByLight(requireContext(), enabled);
                        updateState();
                    }
                });
        bindClick(
                R.id.carPlayDisconnect,
                view -> {
                    stopping = true;
                    updateState();
                    CarPlaySettingsAccess.disconnect(
                            () ->
                                    handler.post(
                                            () -> {
                                                stopping = false;
                                                if (getView() != null) {
                                                    updateState();
                                                }
                                            }));
                });
        bindClick(
                R.id.carPlayOpenHome,
                view ->
                        startActivity(
                                new Intent(requireContext(), DiPlayActivity.class)
                                        .putExtra("page", "home")
                                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        updateState();
    }

    private void bindUiScale() {
        uiScaleDragging = false;
        SeekBar slider = settingView(R.id.carPlayUiScale);
        slider.setSaveEnabled(false);
        slider.setMax(UI_SCALE_PRESETS.size() - 1);
        slider.setKeyProgressIncrement(1);
        for (int index = 0; index < UI_SCALE_LABELS.length; index++) {
            TextView label = settingView(UI_SCALE_LABELS[index]);
            label.setText(
                    getString(R.string.carplay_ui_scale_percent, UI_SCALE_PRESETS.get(index)));
        }
        slider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                        if (!fromUser
                                || updating
                                || !view.isEnabled()
                                || !CarPlayFeature.isEnabled(requireContext())) {
                            return;
                        }
                        int percent = UI_SCALE_PRESETS.get(progress);
                        CarPlaySettingsAccess.setUiScalePercent(requireContext(), percent);
                        showUiScale(percent);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar view) {
                        uiScaleDragging = true;
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar view) {
                        uiScaleDragging = false;
                        updateState();
                    }
                });
    }

    private void showUiScale(int percent) {
        String label = getString(R.string.carplay_ui_scale_value, percent);
        ((TextView) settingView(R.id.carPlayUiScaleLabel)).setText(label);
        settingView(R.id.carPlayUiScale).setContentDescription(label);
    }

    private void updateUiScale(boolean enabled) {
        SeekBar slider = settingView(R.id.carPlayUiScale);
        slider.setEnabled(enabled);
        if (!uiScaleDragging || !slider.isEnabled()) {
            int percent = CarPlaySettingsAccess.uiScalePercent(requireContext());
            slider.setProgress(UI_SCALE_PRESETS.indexOf(percent));
        }
        showUiScale(UI_SCALE_PRESETS.get(slider.getProgress()));
    }

    private void bindVolume(int index) {
        SeekBar slider = settingView(SLIDERS[index]);
        slider.setSaveEnabled(false);
        volumeWrites[index] =
                () -> {
                    if (!pending[index]) {
                        return;
                    }
                    pending[index] = false;
                    if (audio != null
                            && getView() != null
                            && slider.isEnabled()
                            && CarPlayFeature.isEnabled(requireContext())) {
                        audio.setVolume(
                                CarPlayAudioProtocol.streams()[index], slider.getProgress());
                    }
                };
        slider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar view, int value, boolean fromUser) {
                        if (!fromUser) {
                            return;
                        }
                        showVolume(index, value, view.getMax());
                        pending[index] = true;
                        handler.removeCallbacks(volumeWrites[index]);
                        handler.postDelayed(volumeWrites[index], 120);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar view) {
                        dragging[index] = true;
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar view) {
                        handler.removeCallbacks(volumeWrites[index]);
                        volumeWrites[index].run();
                        dragging[index] = false;
                    }
                });
    }

    private void updateVolumes(CarPlayAudioClient.Volumes values) {
        volumes = values;
        if (getView() == null) {
            return;
        }
        for (int i = 0; i < SLIDERS.length; i++) {
            SeekBar slider = settingView(SLIDERS[i]);
            if (slider == null || dragging[i] || pending[i]) {
                continue;
            }
            boolean available = values.maxima[i] > 0;
            slider.setEnabled(available);
            if (available) {
                slider.setMax(values.maxima[i]);
                slider.setProgress(values.levels[i]);
            }
            showVolume(i, values.levels[i], values.maxima[i]);
        }
    }

    private void showVolume(int index, int level, int maximum) {
        TextView label = settingView(LABELS[index]);
        if (label == null) {
            return;
        }
        label.setText(
                maximum > 0
                        ? getString(
                                R.string.carplay_volume_value,
                                getString(TITLES[index]),
                                level,
                                maximum)
                        : getString(R.string.carplay_volume_unavailable, getString(TITLES[index])));
    }

    private void updateState() {
        Switch theme = settingView(R.id.carPlayThemeByLight);
        if (theme == null) {
            return;
        }
        boolean hasPhone = CarPlaySettingsAccess.hasPhone(requireContext());
        updating = true;
        boolean enabled = CarPlayFeature.isEnabled(requireContext());
        ((Switch) settingView(R.id.carPlayEnabled)).setChecked(enabled);
        settingView(R.id.carPlayEnabledContent).setVisibility(enabled ? View.VISIBLE : View.GONE);
        settingView(R.id.carPlayFullscreenCard).setVisibility(View.VISIBLE);
        ((Switch) settingView(R.id.carPlayFullscreen))
                .setChecked(CarPlaySettingsAccess.fullscreen(requireContext()));
        updateUiScale(enabled);
        Switch duckMusic = settingView(R.id.carPlayDuckMusic);
        if (duckMusic != null) {
            duckMusic.setChecked(CarPlayAudioSettings.duckMusic(requireContext()));
        }
        theme.setChecked(CarPlaySettingsAccess.themeByLight(requireContext()));
        ((Switch) settingView(R.id.carPlayUltra))
                .setChecked(CarPlaySettingsAccess.ultra(requireContext()));
        updating = false;
        TextView phone = settingView(R.id.carPlayPhone);
        phone.setText(
                hasPhone
                        ? getString(
                                R.string.carplay_settings_phone,
                                CarPlaySettingsAccess.phoneName(requireContext()))
                        : getString(R.string.carplay_settings_no_phone));
        CarPlaySettingsAccess.ConnectionState state = CarPlaySettingsAccess.connectionState();
        TextView status = settingView(R.id.carPlayConnectionStatus);
        status.setText(
                state == CarPlaySettingsAccess.ConnectionState.CONNECTED
                        ? R.string.carplay_settings_connected
                        : state == CarPlaySettingsAccess.ConnectionState.CONNECTING
                                ? R.string.carplay_settings_connecting
                                : R.string.carplay_settings_disconnected);
        Button disconnect = settingView(R.id.carPlayDisconnect);
        disconnect.setVisibility(
                state == CarPlaySettingsAccess.ConnectionState.DISCONNECTED
                        ? View.GONE
                        : View.VISIBLE);
        disconnect.setEnabled(!stopping);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (audio != null && CarPlayFeature.isEnabled(requireContext())) {
            audio.start();
        }
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    @Override
    public void onPause() {
        uiScaleDragging = false;
        for (Runnable write : volumeWrites) {
            if (write != null) {
                handler.removeCallbacks(write);
                write.run();
            }
        }
        if (audio != null) {
            audio.stop();
        }
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onRowsVisibilityChanged() {
        updateState();
    }

    @Override
    protected void onSectionViewDestroyed() {
        uiScaleDragging = false;
        if (audio != null) {
            audio.stop();
        }
        audio = null;
        volumes = new CarPlayAudioClient.Volumes(null, null);
        cancelVolumeWrites();
        java.util.Arrays.fill(volumeWrites, null);
        handler.removeCallbacks(refresh);
    }

    private void cancelVolumeWrites() {
        for (int i = 0; i < volumeWrites.length; i++) {
            if (volumeWrites[i] != null) {
                handler.removeCallbacks(volumeWrites[i]);
            }
            pending[i] = dragging[i] = false;
        }
    }
}
