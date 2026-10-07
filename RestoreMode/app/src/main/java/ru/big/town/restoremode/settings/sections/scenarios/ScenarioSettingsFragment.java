package ru.big.town.restoremode.settings.sections.scenarios;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import ru.big.town.restoremode.integration.config.SplitConfigSync;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.voice.commands.VoiceCommands;

public final class ScenarioSettingsFragment extends SettingsSectionFragment {
    private ScenarioSettingsPage page;

    @Override
    public SettingsSection section() {
        return SettingsSection.SCENARIOS;
    }

    @Override
    protected void onSectionViewCreated(Bundle state) {
        page =
                new ScenarioSettingsPage(
                        (AppCompatActivity) requireActivity(),
                        settingsList,
                        preferences,
                        () -> {
                            SplitConfigSync.pushScenarios(requireContext(), preferences);
                            VoiceCommands.invalidate();
                        });
    }

    @Override
    protected void showRows(boolean resetScroll) {
        page.refresh(resetScroll);
    }

    @Override
    protected void onSectionViewDestroyed() {
        page = null;
    }
}
