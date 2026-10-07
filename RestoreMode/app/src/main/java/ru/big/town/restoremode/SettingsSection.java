package ru.big.town.restoremode;

/** Stable identifiers also used by settings deep links. */
enum SettingsSection {
    MAIN(0, R.id.navMainScreen, "Главный экран", "ПЕРСОНАЛИЗАЦИЯ"),
    VEHICLE(1, R.id.navDriveModes, "Настройки автомобиля", "АВТОМОБИЛЬ"),
    APPS(2, R.id.navSplitScreen, "Приложения и разделение экрана", "ПРИЛОЖЕНИЯ"),
    APOLLO(3, R.id.navApolloTech, "Apollo Tech", "АССИСТЕНТЫ"),
    CAN(4, R.id.navCustomCommands, "Собственные команды", "РАСШИРЕННЫЕ НАСТРОЙКИ"),
    STEERING(5, R.id.navSteeringButtons, "Кнопки на руле", "УПРАВЛЕНИЕ"),
    OTHER(6, R.id.navOther, "Другое", "СИСТЕМА"),
    VOICE(7, R.id.navVoiceControl, "Голосовое управление", "ГОЛОСОВОЙ ПОМОЩНИК"),
    SCENARIOS(8, R.id.navScenarios, "Сценарии", "ПЕРСОНАЛИЗАЦИЯ");

    final int identifier;
    final int navigationId;
    final String title;
    final String category;

    SettingsSection(int identifier, int navigationId, String title, String category) {
        this.identifier = identifier;
        this.navigationId = navigationId;
        this.title = title;
        this.category = category;
    }

    static SettingsSection fromIdentifier(int identifier) {
        for (SettingsSection section : values()) {
            if (section.identifier == identifier) {
                return section;
            }
        }
        return MAIN;
    }
}
