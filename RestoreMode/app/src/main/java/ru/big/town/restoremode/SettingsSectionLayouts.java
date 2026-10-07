package ru.big.town.restoremode;

/** Resource descriptors only; no section creates Views before it is selected. */
final class SettingsSectionLayouts {
    private SettingsSectionLayouts() {}

    private static final Item[] MAIN = {
        layout(R.layout.settings_main_overview),
        layout(R.layout.settings_main_quick_access_heading),
        layout(R.layout.settings_main_trip_timer_power_hold),
        layout(R.layout.settings_main_wash_mode_auto_light),
        layout(R.layout.settings_main_pedestrian_sound_battery_heat),
        layout(R.layout.settings_main_voice_scenarios),
        layout(R.layout.settings_main_forced_ev_suspension_maintenance),
        layout(R.layout.settings_main_launch_apps_suspension),
        layout(R.layout.settings_main_suspension_description),
        layout(R.layout.settings_main_energy_heading),
        layout(R.layout.settings_main_energy_charge_consumption),
        layout(R.layout.settings_main_energy_trip_tire_pressure),
        layout(R.layout.settings_main_odometer),
        layout(R.layout.settings_main_vehicle_parameters_heading),
        layout(R.layout.settings_main_vehicle_parameters),
        layout(R.layout.settings_main_system_heading),
        layout(R.layout.settings_main_cpu_ram),
        layout(R.layout.settings_main_memory_task_manager),
        layout(R.layout.settings_main_app_widgets_heading),
        dynamic(DynamicContent.APP_WIDGETS),
        layout(R.layout.settings_main_add_app_widget),
        layout(R.layout.settings_main_shortcuts_heading),
        dynamic(DynamicContent.SHORTCUTS),
        layout(R.layout.settings_main_add_shortcut),
        layout(R.layout.settings_main_dial_cards_heading),
        dynamic(DynamicContent.DIAL_CARDS),
        layout(R.layout.settings_main_add_dial_card),
        layout(R.layout.settings_main_trip_history_heading),
        layout(R.layout.settings_main_trip_history),
        layout(R.layout.settings_main_grid_heading),
        layout(R.layout.settings_main_grid)
    };
    private static final Item[] CAN = {
        layout(R.layout.settings_can_intro),
        layout(R.layout.settings_can_warning),
        layout(R.layout.settings_can_editor)
    };
    private static final Item[] VEHICLE = {
        layout(R.layout.settings_vehicle_intro),
        layout(R.layout.settings_vehicle_modes_heading),
        layout(R.layout.settings_vehicle_drive_energy_modes),
        layout(R.layout.settings_vehicle_energy_button_layout),
        layout(R.layout.settings_vehicle_comfort_heading),
        layout(R.layout.settings_vehicle_fragrance),
        layout(R.layout.settings_vehicle_light_sound_heading),
        layout(R.layout.settings_vehicle_light_sound),
        layout(R.layout.settings_vehicle_automation_heading),
        layout(R.layout.settings_vehicle_automation),
        layout(R.layout.settings_vehicle_special_modes_heading),
        layout(R.layout.settings_vehicle_special_modes)
    };
    private static final Item[] APPS = {
        layout(R.layout.settings_apps_intro),
        layout(R.layout.settings_apps_split_heading),
        dynamic(DynamicContent.SPLITS),
        layout(R.layout.settings_apps_add_split),
        layout(R.layout.settings_apps_dock_heading),
        layout(R.layout.settings_apps_dock_slots),
        layout(R.layout.settings_apps_fullscreen_heading),
        layout(R.layout.settings_apps_fullscreen_description),
        dynamic(DynamicContent.FULLSCREEN_APPS),
        layout(R.layout.settings_apps_add_fullscreen_app),
        layout(R.layout.settings_apps_scale_heading),
        dynamic(DynamicContent.APP_SCALE)
    };
    private static final Item[] APOLLO = {
        layout(R.layout.settings_apollo_intro),
        layout(R.layout.settings_apollo_status),
        layout(R.layout.settings_apollo_maneuvers_heading),
        layout(R.layout.settings_apollo_lane_change_assist),
        layout(R.layout.settings_apollo_recognition_heading),
        layout(R.layout.settings_apollo_traffic_recognition),
        layout(R.layout.settings_apollo_speed_heading),
        layout(R.layout.settings_apollo_speed_assistance)
    };
    private static final Item[] OTHER = {
        layout(R.layout.settings_other_intro),
        layout(R.layout.settings_other_version_updates),
        layout(R.layout.settings_other_location_heading),
        layout(R.layout.settings_other_location_permission),
        layout(R.layout.settings_other_metrics_heading),
        layout(R.layout.settings_other_metrics),
        layout(R.layout.settings_other_diagnostics),
        layout(R.layout.settings_other_keyboard_heading),
        layout(R.layout.settings_other_keyboard),
        layout(R.layout.settings_other_appearance_heading),
        layout(R.layout.settings_other_appearance_behavior),
        layout(R.layout.settings_other_maintenance_heading),
        layout(R.layout.settings_other_maintenance_actions),
        layout(R.layout.settings_other_engineering_menu)
    };
    private static final Item[] STEERING = {
        layout(R.layout.settings_steering_intro), layout(R.layout.settings_steering_button_actions)
    };

    enum DynamicContent {
        APP_WIDGETS,
        DIAL_CARDS,
        SHORTCUTS,
        FULLSCREEN_APPS,
        SPLITS,
        APP_SCALE
    }

    static final class Item {
        final int layoutResource;
        final DynamicContent dynamicContent;

        private Item(int layoutResource, DynamicContent dynamicContent) {
            this.layoutResource = layoutResource;
            this.dynamicContent = dynamicContent;
        }
    }

    private static Item layout(int resource) {
        return new Item(resource, null);
    }

    private static Item dynamic(DynamicContent content) {
        return new Item(0, content);
    }

    static Item[] forSection(SettingsSection section) {
        switch (section) {
            case MAIN:
                return MAIN;
            case VEHICLE:
                return VEHICLE;
            case APPS:
                return APPS;
            case APOLLO:
                return APOLLO;
            case CAN:
                return CAN;
            case STEERING:
                return STEERING;
            case OTHER:
                return OTHER;
            default:
                throw new IllegalArgumentException("Section has its own controller: " + section);
        }
    }
}
