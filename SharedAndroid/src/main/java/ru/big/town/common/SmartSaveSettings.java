package ru.big.town.common;

/** Temporary energy commands never replace the driver's saved selection. */
public final class SmartSaveSettings {
    public static final String ENABLED = "smartSaveEnabled";
    public static final String HYBRID_ENGINE_HOLD = "hybridEngineHoldEnabled";
    public static final String TAKE_OVER_HYBRID_ENERGY = "takeOverHybridEnergy";
    public static final String CHANGED = "ru.big.town.anative.SMART_SAVE_CHANGED";
    public static final String TEMPORARY = "__vt_temporary_energy";
    public static final String REVISION = "__vt_energy_revision";
    public static final String CYCLE = "__vt_energy_cycle";

    public enum Automation {
        SMART_SAVE("SREV", 4, 2, ENABLED),
        HYBRID("REV", 3, 4, HYBRID_ENGINE_HOLD);

        private final String selection;
        public final int selectedMode;
        public final int temporaryMode;
        public final String option;

        Automation(String selection, int selectedMode, int temporaryMode, String option) {
            this.selection = selection;
            this.selectedMode = selectedMode;
            this.temporaryMode = temporaryMode;
            this.option = option;
        }

        public static Automation selected(String energy, boolean forcedElectric) {
            if (forcedElectric) return null;
            for (Automation automation : values()) {
                if (automation.selection.equals(energy)) return automation;
            }
            return null;
        }

        public boolean allows(int mode, boolean enabled) {
            return mode == selectedMode || (enabled && mode == temporaryMode);
        }
    }

    private SmartSaveSettings() {}
}
