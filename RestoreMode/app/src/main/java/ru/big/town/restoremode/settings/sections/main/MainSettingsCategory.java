package ru.big.town.restoremode.settings.sections.main;

import static ru.big.town.restoremode.settings.core.SettingsSectionLayouts.DynamicContent.APP_WIDGETS;
import static ru.big.town.restoremode.settings.core.SettingsSectionLayouts.DynamicContent.CARPLAY_WIDGETS;
import static ru.big.town.restoremode.settings.core.SettingsSectionLayouts.DynamicContent.DIAL_CARDS;
import static ru.big.town.restoremode.settings.core.SettingsSectionLayouts.DynamicContent.LINK_SHORTCUTS;
import static ru.big.town.restoremode.settings.core.SettingsSectionLayouts.DynamicContent.SHORTCUTS;
import static ru.big.town.restoremode.settings.core.SettingsSectionLayouts.dynamic;
import static ru.big.town.restoremode.settings.core.SettingsSectionLayouts.layout;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.core.SettingsSectionLayouts.Item;

/** Second-level pages reuse the dashboard's existing preference bindings. */
public enum MainSettingsCategory {
    ACTIONS(
            "Управление и быстрые действия",
            "Автомобиль, голосовое управление и системные действия",
            layout(R.layout.settings_main_passenger_power_hold),
            layout(R.layout.settings_main_wash_mode_auto_light),
            layout(R.layout.settings_main_rear_fog_hotspot),
            layout(R.layout.settings_main_fuel_flap),
            layout(R.layout.settings_main_recirculation),
            layout(R.layout.settings_main_pedestrian_sound_battery_heat),
            layout(R.layout.settings_main_voice_scenarios),
            layout(R.layout.settings_main_forced_ev_suspension_maintenance),
            layout(R.layout.settings_main_vehicle_controls),
            layout(R.layout.settings_main_memory_task_manager),
            layout(R.layout.settings_main_android_settings),
            layout(R.layout.settings_main_brightness_row)),
    INFORMATION(
            "Информационные виджеты",
            "Часы, поездка, энергия, музыка и показатели системы",
            layout(R.layout.settings_main_clock_trip),
            layout(R.layout.settings_main_energy_charge_consumption),
            layout(R.layout.settings_main_energy_trip_tire_pressure),
            layout(R.layout.settings_main_cabin_temperature),
            layout(R.layout.settings_main_odometer),
            layout(R.layout.settings_main_cpu_ram),
            layout(R.layout.settings_main_media_radio)),
    APPLICATIONS(
            "Приложения",
            "Быстрый запуск, ярлыки и встроенные окна приложений",
            layout(R.layout.settings_main_launch_apps_row),
            layout(R.layout.settings_main_app_widgets_heading),
            dynamic(APP_WIDGETS),
            layout(R.layout.settings_main_add_app_widget),
            layout(R.layout.settings_main_shortcuts_heading),
            dynamic(SHORTCUTS),
            layout(R.layout.settings_main_add_shortcut)),
    CARPLAY("CarPlay", "Ярлык или встроенное окно, размеры и автозапуск", dynamic(CARPLAY_WIDGETS)),
    VEHICLE(
            "Автомобиль и поездки",
            "Цвет автомобиля, ёмкости батареи и бака, история поездок",
            layout(R.layout.settings_main_vehicle_parameters),
            layout(R.layout.settings_main_trip_history_heading),
            layout(R.layout.settings_main_trip_history_row)),
    GRID(
            "Сетка и размещение",
            "Полноэкранная сетка и выравнивание после прокрутки",
            layout(R.layout.settings_main_grid)),
    LINKS(
            "Ссылки и контакты",
            "Веб-ссылки, диплинки и карточки набора номера",
            layout(R.layout.settings_main_links_heading),
            dynamic(LINK_SHORTCUTS),
            layout(R.layout.settings_main_add_link),
            layout(R.layout.settings_main_dial_cards_heading),
            dynamic(DIAL_CARDS),
            layout(R.layout.settings_main_add_dial_card));

    public final String title;
    public final String description;
    private final Item[] layouts;

    MainSettingsCategory(String title, String description, Item... layouts) {
        this.title = title;
        this.description = description;
        this.layouts = layouts;
    }

    public Item[] layouts() {
        return layouts.clone();
    }
}
