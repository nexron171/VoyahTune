package ru.big.town.restoremode.settings.core;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.sections.apollo.ApolloSettingsFragment;
import ru.big.town.restoremode.settings.sections.apps.AppsSettingsFragment;
import ru.big.town.restoremode.settings.sections.can.CanSettingsFragment;
import ru.big.town.restoremode.settings.sections.main.MainSettingsFragment;
import ru.big.town.restoremode.settings.sections.other.OtherSettingsFragment;
import ru.big.town.restoremode.settings.sections.scenarios.ScenarioSettingsFragment;
import ru.big.town.restoremode.settings.sections.steering.SteeringSettingsFragment;
import ru.big.town.restoremode.settings.sections.vehicle.VehicleSettingsFragment;
import ru.big.town.restoremode.settings.sections.voice.VoiceSettingsFragment;

/** Stable identifiers also used by settings deep links. */
public enum SettingsSection {
    MAIN(0, R.id.navMainScreen, "Главный экран", "ПЕРСОНАЛИЗАЦИЯ"),
    VEHICLE(1, R.id.navDriveModes, "Настройки автомобиля", "АВТОМОБИЛЬ"),
    APPS(2, R.id.navSplitScreen, "Приложения и разделение экрана", "ПРИЛОЖЕНИЯ"),
    APOLLO(3, R.id.navApolloTech, "Apollo Tech", "АССИСТЕНТЫ"),
    CAN(4, R.id.navCustomCommands, "Собственные команды", "РАСШИРЕННЫЕ НАСТРОЙКИ"),
    STEERING(5, R.id.navSteeringButtons, "Кнопки на руле", "УПРАВЛЕНИЕ"),
    OTHER(6, R.id.navOther, "Другое", "СИСТЕМА"),
    VOICE(7, R.id.navVoiceControl, "Голосовое управление", "ГОЛОСОВОЙ ПОМОЩНИК"),
    SCENARIOS(8, R.id.navScenarios, "Сценарии", "ПЕРСОНАЛИЗАЦИЯ");

    public final int identifier;
    public final int navigationId;
    public final String title;
    public final String category;

    SettingsSection(int identifier, int navigationId, String title, String category) {
        this.identifier = identifier;
        this.navigationId = navigationId;
        this.title = title;
        this.category = category;
    }

    public static SettingsSection fromIdentifier(int identifier) {
        for (SettingsSection section : values()) {
            if (section.identifier == identifier) {
                return section;
            }
        }
        return MAIN;
    }

    public boolean savesImmediately() {
        return this == VOICE || this == SCENARIOS;
    }

    public SettingsSectionFragment createFragment() {
        switch (this) {
            case MAIN:
                return new MainSettingsFragment();
            case VEHICLE:
                return new VehicleSettingsFragment();
            case APPS:
                return new AppsSettingsFragment();
            case APOLLO:
                return new ApolloSettingsFragment();
            case CAN:
                return new CanSettingsFragment();
            case STEERING:
                return new SteeringSettingsFragment();
            case OTHER:
                return new OtherSettingsFragment();
            case VOICE:
                return new VoiceSettingsFragment();
            case SCENARIOS:
                return new ScenarioSettingsFragment();
            default:
                throw new IllegalStateException("Unknown settings section: " + this);
        }
    }
}
