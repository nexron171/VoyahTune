package ru.big.town.restoremode.settings.sections.carplay;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import com.shilapi.xcertplay.CarPlaySettingsAccess;
import com.shilapi.xcertplay.DiPlayActivity;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;

public final class CarPlaySettingsFragment extends SettingsSectionFragment {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean updating;
    private boolean stopping;
    private final Runnable refresh =
            new Runnable() {
                @Override
                public void run() {
                    updateState();
                    handler.postDelayed(this, 1000);
                }
            };

    @Override
    public SettingsSection section() {
        return SettingsSection.CARPLAY;
    }

    @Override
    protected void bindSettingsRow() {
        Switch toggle = settingView(R.id.carPlayAutoConnect);
        toggle.setSaveEnabled(false);
        toggle.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (!updating) {
                        CarPlaySettingsAccess.setAutoConnect(requireContext(), enabled);
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

    private void updateState() {
        Switch toggle = settingView(R.id.carPlayAutoConnect);
        if (toggle == null) return;
        boolean enabled = CarPlaySettingsAccess.autoConnect(requireContext());
        boolean hasPhone = CarPlaySettingsAccess.hasPhone(requireContext());
        updating = true;
        toggle.setChecked(enabled);
        toggle.setEnabled(hasPhone || enabled);
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
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onRowsVisibilityChanged() {
        updateState();
    }

    @Override
    protected void onSectionViewDestroyed() {
        handler.removeCallbacks(refresh);
    }
}
