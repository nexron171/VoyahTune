package ru.big.town.restoremode.settings.sections.carplay;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.SeekBar;
import android.widget.TextView;

import com.shilapi.xcertplay.CarPlaySettingsAccess;
import com.shilapi.xcertplay.DiPlayActivity;

import ru.big.town.restoremode.R;
import ru.big.town.common.CarPlayAudioProtocol;
import ru.big.town.restoremode.carplay.CarPlayAudioClient;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;

public final class CarPlaySettingsFragment extends SettingsSectionFragment {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean updating;
    private boolean stopping;
    private CarPlayAudioClient audio;
    private final boolean[] dragging = new boolean[4], pending = new boolean[4];
    private final Runnable[] volumeWrites = new Runnable[4];
    private static final int[] SLIDERS = {R.id.carPlayMediaVolume, R.id.carPlayNavigationVolume,
            R.id.carPlayPhoneVolume, R.id.carPlaySiriVolume};
    private static final int[] LABELS = {R.id.carPlayMediaVolumeLabel, R.id.carPlayNavigationVolumeLabel,
            R.id.carPlayPhoneVolumeLabel, R.id.carPlaySiriVolumeLabel};
    private static final int[] TITLES = {R.string.carplay_volume_media, R.string.carplay_volume_navigation,
            R.string.carplay_volume_phone, R.string.carplay_volume_siri};
    private final Runnable refresh =
            new Runnable() {
                @Override
                public void run() {
                    updateState();
                    if (audio != null) audio.query();
                    handler.postDelayed(this, 1000);
                }
            };

    @Override
    public SettingsSection section() {
        return SettingsSection.CARPLAY;
    }

    @Override
    protected void bindSettingsRow() {
        audio = new CarPlayAudioClient(requireContext(), this::updateVolumes);
        for (int i = 0; i < SLIDERS.length; i++) bindVolume(i);
        updateVolumes(new CarPlayAudioClient.Volumes(null, null));
        Switch toggle = settingView(R.id.carPlayAutoConnect);
        toggle.setSaveEnabled(false);
        toggle.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (!updating) {
                        CarPlaySettingsAccess.setAutoConnect(requireContext(), enabled);
                        updateState();
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
                                                if (getView() != null) updateState();
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

    private void bindVolume(int index) {
        SeekBar slider = settingView(SLIDERS[index]);
        slider.setSaveEnabled(false);
        volumeWrites[index] = () -> {
            if (!pending[index]) return;
            pending[index] = false;
            if (audio != null && getView() != null && slider.isEnabled())
                audio.setVolume(CarPlayAudioProtocol.streams()[index], slider.getProgress());
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int value, boolean fromUser) {
                if (!fromUser) return;
                showVolume(index, value, view.getMax());
                pending[index] = true;
                handler.removeCallbacks(volumeWrites[index]);
                handler.postDelayed(volumeWrites[index], 120);
            }
            @Override public void onStartTrackingTouch(SeekBar view) { dragging[index] = true; }
            @Override public void onStopTrackingTouch(SeekBar view) {
                handler.removeCallbacks(volumeWrites[index]);
                volumeWrites[index].run();
                dragging[index] = false;
            }
        });
    }

    private void updateVolumes(CarPlayAudioClient.Volumes values) {
        if (getView() == null) return;
        for (int i = 0; i < SLIDERS.length; i++) {
            SeekBar slider = settingView(SLIDERS[i]);
            if (slider == null || dragging[i] || pending[i]) continue;
            boolean available = values.maxima[i] > 0;
            slider.setEnabled(available);
            if (available) { slider.setMax(values.maxima[i]); slider.setProgress(values.levels[i]); }
            showVolume(i, values.levels[i], values.maxima[i]);
        }
    }

    private void showVolume(int index, int level, int maximum) {
        TextView label = settingView(LABELS[index]);
        if (label == null) return;
        label.setText(maximum > 0 ? getString(R.string.carplay_volume_value, getString(TITLES[index]), level, maximum)
                : getString(R.string.carplay_volume_unavailable, getString(TITLES[index])));
    }

    private void updateState() {
        Switch toggle = settingView(R.id.carPlayAutoConnect);
        if (toggle == null) return;
        boolean enabled = CarPlaySettingsAccess.autoConnect(requireContext());
        boolean hasPhone = CarPlaySettingsAccess.hasPhone(requireContext());
        updating = true;
        toggle.setChecked(enabled);
        toggle.setEnabled(hasPhone || enabled);
        Switch theme = settingView(R.id.carPlayThemeByLight);
        theme.setChecked(CarPlaySettingsAccess.themeByLight(requireContext()));
        updating = false;
        TextView description = settingView(R.id.carPlayAutoConnectDescription);
        description.setText(
                hasPhone
                        ? R.string.carplay_home_auto_connect_detail
                        : R.string.carplay_home_auto_connect_choose_phone);
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
        if (audio != null) audio.start();
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    @Override
    public void onPause() {
        for (Runnable write : volumeWrites) if (write != null) { handler.removeCallbacks(write); write.run(); }
        if (audio != null) audio.stop();
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onRowsVisibilityChanged() {
        updateState();
    }

    @Override
    protected void onSectionViewDestroyed() {
        if (audio != null) audio.stop();
        audio = null;
        for (int i = 0; i < volumeWrites.length; i++) {
            if (volumeWrites[i] != null) handler.removeCallbacks(volumeWrites[i]);
            volumeWrites[i] = null;
            pending[i] = dragging[i] = false;
        }
        handler.removeCallbacks(refresh);
    }
}
