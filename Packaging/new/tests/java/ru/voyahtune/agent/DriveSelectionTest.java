package ru.voyahtune.agent;

import java.util.Arrays;
import ru.voyahtune.agent.policy.DriveSelection;

public final class DriveSelectionTest {
    public static void main(String[] arguments) throws Exception {
        verify();
        System.out.println("DriveSelectionTest: passed");
    }

    public static void verify() throws Exception {
        DriveSelection.Settings normal = settings("SPORT", "REV", "HIGH", 1, true, true, true, false);
        DriveSelection.Targets selected = DriveSelection.resolve(normal, (key, account) -> null);
        require(Arrays.equals(selected.drive(), new int[] {3, 3, 3}) && selected.energy == 3
                && selected.maintenance == 2 && selected.recycle == 4, "Stored drive selection was not resolved");
        int[] copy = selected.drive();
        copy[0] = 100;
        require(selected.drive()[0] == 3, "Drive selection leaked a mutable profile");
        DriveSelection.Targets snow = DriveSelection.resolve(settings("SNOW", "invalid", "invalid", 0, true, false, true, true), (key, account) -> null);
        require(Arrays.equals(snow.drive(), new int[] {6, 2, 2}) && snow.energy == 5 && snow.recycle == null,
                "Snow or forced EV ownership changed");
        require(DriveSelection.resolve(settings("bad", "EV", "LOW", 0, true, true, false, false), (key, account) -> null) == null,
                "Unknown profile changed the reset");
        require(DriveSelection.resolve(settings("ECO", "bad", "LOW", 0, true, true, false, false), (key, account) -> null) == null,
                "Unknown energy changed the reset");
        require(DriveSelection.resolve(settings("ECO", "EV", "bad", 0, true, true, true, false), (key, account) -> null) == null,
                "Unknown recuperation changed the reset");
        for (Integer maintenance : new Integer[] {null, -1, 2}) {
            require(DriveSelection.resolve(settings("ECO", "EV", "LOW", maintenance, true, true, false, false), (key, account) -> null) == null,
                    "Missing/invalid suspension preference was accepted");
        }
        DriveSelection.Targets individual = DriveSelection.resolve(settings("INDIVIDUAL", "SMART", "MEDIUM", 0, true, true, true, false),
                (key, account) -> key.equals("drive_mode_steeringWheelAssist") ? "3" : "2");
        require(Arrays.equals(individual.drive(), new int[] {5, 3, 2}), "Individual account profile was not used");
        require(DriveSelection.resolve(settings("INDIVIDUAL", "SMART", "MEDIUM", 0, true, true, false, false),
                (key, account) -> "invalid") == null, "Malformed individual values were accepted");
        DriveSelection.Targets disabled = DriveSelection.resolve(settings("unknown", "unknown", "unknown", 0, false, false, false, false),
                (key, account) -> { throw new AssertionError("Disabled profile queried individual values"); });
        require(disabled.drive() == null && disabled.energy == null && disabled.recycle == null && disabled.maintenance == 1,
                "Disabled restore did not preserve stock modes");
        Row row = new Row();
        DriveSelection.Targets fromRow = DriveSelection.read(row, () -> "account", (key, account) -> null);
        require(fromRow != null && Arrays.equals(fromRow.drive(), new int[] {3, 3, 3}) && row.closes == 1,
                "Provider row was not parsed and closed");
        Row shortRow = new Row();
        shortRow.columns = 6;
        require(DriveSelection.read(shortRow, () -> "account", (key, account) -> null) == null && shortRow.closes == 1,
                "Incomplete provider row was accepted or leaked");
        Row nullMaintenance = new Row();
        nullMaintenance.missingMaintenance = true;
        require(DriveSelection.read(nullMaintenance, () -> "account", (key, account) -> null) == null && nullMaintenance.closes == 1,
                "Missing maintenance row was accepted or leaked");
    }

    private static DriveSelection.Settings settings(String mode, String energy, String recycle, Integer maintenance,
                                                   boolean driveEnabled, boolean energyEnabled, boolean recycleEnabled, boolean forcedEv) {
        return new DriveSelection.Settings(mode, energy, recycle, maintenance, driveEnabled, energyEnabled, recycleEnabled, forcedEv, "account");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Row implements DriveSelection.Row {
        int closes;
        int columns = 40;
        boolean missingMaintenance;
        @Override public boolean moveToFirst() { return true; }
        @Override public int columnCount() { return columns; }
        @Override public boolean isNull(int column) { return column == 32 && missingMaintenance; }
        @Override public String string(int column) { return column == 0 ? "SPORT" : column == 1 ? "EV" : "HIGH"; }
        @Override public int integer(int column) { return column == 6 ? 1 : 0; }
        @Override public void close() { closes++; }
    }
}
