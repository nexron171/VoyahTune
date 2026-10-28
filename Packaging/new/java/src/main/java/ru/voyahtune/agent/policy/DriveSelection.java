package ru.voyahtune.agent.policy;

import java.util.List;
import java.util.Map;

public final class DriveSelection {
    @FunctionalInterface
    public interface IndividualValues {
        String read(String key, String account) throws Exception;
    }

    @FunctionalInterface
    public interface Account {
        String read() throws Exception;
    }

    public interface Row extends AutoCloseable {
        boolean moveToFirst() throws Exception;
        int columnCount() throws Exception;
        boolean isNull(int column) throws Exception;
        String string(int column) throws Exception;
        int integer(int column) throws Exception;
        @Override void close() throws Exception;
    }

    public static final class Settings {
        public final String mode;
        public final String energy;
        public final String recycle;
        public final Integer maintenance;
        public final boolean driveEnabled;
        public final boolean energyEnabled;
        public final boolean recycleEnabled;
        public final boolean forcedEv;
        public final String account;

        public Settings(String mode, String energy, String recycle, Integer maintenance, boolean driveEnabled,
                        boolean energyEnabled, boolean recycleEnabled, boolean forcedEv, String account) {
            this.mode = mode;
            this.energy = energy;
            this.recycle = recycle;
            this.maintenance = maintenance;
            this.driveEnabled = driveEnabled;
            this.energyEnabled = energyEnabled;
            this.recycleEnabled = recycleEnabled;
            this.forcedEv = forcedEv;
            this.account = account;
        }
    }

    public static final class Targets {
        private final int[] drive;
        public final Integer energy;
        public final int maintenance;
        public final Integer recycle;

        private Targets(int[] drive, Integer energy, int maintenance, Integer recycle) {
            this.drive = drive == null ? null : drive.clone();
            this.energy = energy;
            this.maintenance = maintenance;
            this.recycle = recycle;
        }

        public int[] drive() {
            return drive == null ? null : drive.clone();
        }
    }

    private static final Map<String, int[]> PROFILES = Map.of(
            "ECO", new int[] {1, 2, 1}, "COMFORT", new int[] {2, 2, 2}, "SPORT", new int[] {3, 3, 3},
            "OUTING", new int[] {4, 2, 3}, "SNOW", new int[] {6, 2, 2});
    private static final Map<String, Integer> ENERGIES = Map.of("SMART", 1, "Smart", 1, "EV", 2, "REV", 3, "SREV", 4, "FORCE_EV", 5);
    private static final Map<String, Integer> RECYCLING = Map.of("LOW", 2, "MEDIUM", 3, "HIGH", 4);
    private static final List<String> DRIVE_NAMES = List.of("", "ECO", "COMFORT", "SPORT", "OUTING", "INDIVIDUAL", "SNOW");
    private static final List<String> ENERGY_NAMES = List.of("", "SMART", "EV", "REV", "SREV", "FORCE_EV");

    private DriveSelection() {}

    public static String driveName(int mode) {
        return mode >= 1 && mode < DRIVE_NAMES.size() ? DRIVE_NAMES.get(mode) : null;
    }

    public static String energyName(int mode) {
        return mode >= 1 && mode < ENERGY_NAMES.size() ? ENERGY_NAMES.get(mode) : null;
    }

    public static Targets resolve(Settings settings, IndividualValues individual) throws Exception {
        return resolve(settings, individual, () -> settings.account);
    }

    public static Targets read(Row row, Account account, IndividualValues individual) throws Exception {
        Settings settings;
        try (Row cursor = row) {
            if (cursor == null || !cursor.moveToFirst() || cursor.columnCount() <= 32 || cursor.isNull(32)) return null;
            settings = new Settings(cursor.string(0), cursor.string(1), cursor.string(2), cursor.integer(32),
                    cursor.integer(6) == 1, cursor.integer(8) == 1, cursor.integer(7) == 1, cursor.integer(19) == 1, null);
        }
        return resolve(settings, individual, account);
    }

    private static Targets resolve(Settings settings, IndividualValues individual, Account account) throws Exception {
        if (settings == null || settings.maintenance == null || settings.maintenance < 0 || settings.maintenance > 1) return null;
        Integer recycle = null;
        if (settings.recycleEnabled && !"SNOW".equals(settings.mode)) {
            recycle = settings.recycle == null ? null : RECYCLING.get(settings.recycle);
            if (recycle == null) return null;
        }
        Integer energy = null;
        if (settings.forcedEv) energy = 5;
        else if (settings.energyEnabled) {
            energy = settings.energy == null ? null : ENERGIES.get(settings.energy);
            if (energy == null) return null;
        }
        int[] drive = null;
        if (settings.driveEnabled) {
            if ("INDIVIDUAL".equals(settings.mode)) {
                String accountId = account.read();
                if (accountId == null || accountId.isEmpty()) return null;
                Integer steering = individual(individual.read("drive_mode_steeringWheelAssist", accountId), 2);
                Integer pedal = individual(individual.read("drive_mode_runState", accountId), 1);
                if (steering == null || pedal == null || steering != 2 && steering != 3 || pedal < 1 || pedal > 3) return null;
                drive = new int[] {5, steering, pedal};
            } else {
                drive = settings.mode == null ? null : PROFILES.get(settings.mode);
                if (drive == null) return null;
            }
        }
        return new Targets(drive, energy, settings.maintenance == 1 ? 2 : 1, recycle);
    }

    private static Integer individual(String raw, int fallback) {
        if (raw == null) return fallback;
        String value = raw.trim();
        if (!value.matches("-?[0-9]+")) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
