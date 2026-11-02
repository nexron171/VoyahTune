package ru.big.town.restoremode.settings.sections.vehicle;

import static ru.big.town.restoremode.settings.core.SettingsSectionLayouts.dynamic;
import static ru.big.town.restoremode.settings.core.SettingsSectionLayouts.layout;
import static ru.big.town.restoremode.settings.core.SettingsSectionLayouts.DynamicContent.SCREEN_BRIGHTNESS;
import static ru.big.town.restoremode.settings.core.SettingsSectionLayouts.DynamicContent.TURN_SOUND;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.core.SettingsSectionLayouts.Item;

public enum VehicleSettingsCategory {
    DRIVING(
            "Движение и энергия",
            "Режимы движения, рекуперация, Save и Forced EV",
            layout(R.layout.settings_vehicle_drive_energy_modes),
            layout(R.layout.settings_vehicle_energy_button_layout),
            layout(R.layout.settings_vehicle_forced_ev_row)),
    LIGHTING(
            "Свет и экраны",
            "Автоматический ближний свет и яркость дисплеев",
            layout(R.layout.settings_vehicle_auto_light_row),
            dynamic(SCREEN_BRIGHTNESS)),
    AUDIO(
            "Звук и музыка",
            "Предупреждение пешеходов, музыка по двери и поворотники",
            layout(R.layout.settings_vehicle_pedestrian_sound_row),
            layout(R.layout.settings_vehicle_media_door),
            dynamic(TURN_SOUND)),
    COMFORT(
            "Комфорт и автоматизация",
            "Ароматизатор и автоматический прогрев батареи",
            layout(R.layout.settings_vehicle_fragrance),
            layout(R.layout.settings_vehicle_battery_heating_row)),
    SERVICE(
            "Обслуживание и штатный интерфейс",
            "Сервисные режимы подвески и дворников, штатная статистика",
            layout(R.layout.settings_vehicle_service_cards));

    public final String title;
    public final String description;
    private final Item[] layouts;

    VehicleSettingsCategory(String title, String description, Item... layouts) {
        this.title = title;
        this.description = description;
        this.layouts = layouts;
    }

    public Item[] layouts() {
        return layouts.clone();
    }
}
