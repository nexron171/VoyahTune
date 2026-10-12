package ru.big.town.common;

/** OD temporary energy commands never replace the driver's saved selection. */
public final class SmartSaveSettings {
    public static final String ENABLED = "smartSaveEnabled";
    public static final String CHANGED = "ru.big.town.anative.SMART_SAVE_CHANGED";
    public static final String TEMPORARY = "__vt_temporary_energy";
    public static final String REVISION = "__vt_energy_revision";
    public static final String CYCLE = "__vt_energy_cycle";

    private SmartSaveSettings() {}
}
