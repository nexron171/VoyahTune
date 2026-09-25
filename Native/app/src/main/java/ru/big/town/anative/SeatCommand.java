package ru.big.town.anative;

/** Allowlist at the IPC boundary. No arbitrary VehicleState names or values from the UI. */
final class SeatCommand {
    final String field;
    final int value;

    private SeatCommand(String field, int value) { this.field = field; this.value = value; }

    static boolean handles(String action) {
        return action != null && (action.startsWith("seat:") || action.startsWith("wheel_heat:"));
    }

    static SeatCommand parse(String action) {
        if (action == null) return null;
        if (action.equals("wheel_heat:on")) return new SeatCommand("STEER_WHEEL_HEAT_SWITCH", 2);
        if (action.equals("wheel_heat:off")) return new SeatCommand("STEER_WHEEL_HEAT_SWITCH", 1);
        String[] parts = action.split(":", -1);
        if (parts.length != 4 || !parts[0].equals("seat")) return null;
        String side = parts[1].equals("driver") ? "LEFT" : parts[1].equals("passenger") ? "RIGHT" : null;
        String function = parts[2].equals("massage") ? "MASS" : parts[2].equals("heat") ? "HEATING"
                : parts[2].equals("vent") ? "VENTILATION" : null;
        if (side == null || function == null) return null;
        String value = parts[3], operation;
        int setting;
        if (value.equals("on") || value.equals("off")) {
            operation = "SWITCH"; setting = value.equals("on") ? 2 : 1;
        } else if (value.matches("[1-3]")) {
            operation = function.equals("MASS") ? "INTEN" : "COMMAND";
            setting = Integer.parseInt(value);
        } else if (function.equals("MASS") && (value.equals("waves") || value.equals("rollers"))) {
            operation = "COMMAND"; setting = value.equals("waves") ? 1 : 2;
        } else return null;
        return new SeatCommand("FRONT_SEAT_" + function + "_" + operation + "_" + side, setting);
    }
}
