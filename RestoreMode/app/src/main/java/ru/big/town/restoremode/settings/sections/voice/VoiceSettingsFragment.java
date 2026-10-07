package ru.big.town.restoremode.settings.sections.voice;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;

public final class VoiceSettingsFragment extends SettingsSectionFragment {
    private VoiceSettingsPage page;

    @Override
    public SettingsSection section() {
        return SettingsSection.VOICE;
    }

    @Override
    protected void onSectionViewCreated(Bundle state) {
        page =
                new VoiceSettingsPage(
                        (AppCompatActivity) requireActivity(),
                        settingsList,
                        preferences,
                        this::requestPermissions);
    }

    @Override
    protected void showRows(boolean resetScroll) {
        page.refresh(resetScroll);
    }

    @Override
    public void onResume() {
        super.onResume();
        page.refresh();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (page != null) {
            page.onPermissionResult(request, grants);
        }
    }

    @Override
    protected void onSectionViewDestroyed() {
        page.close();
        page = null;
    }
}
