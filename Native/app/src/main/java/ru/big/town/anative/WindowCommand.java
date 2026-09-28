package ru.big.town.anative;

/** Exact IPC allowlist. A group is one OEM field, never a sequence of individual writes. */
final class WindowCommand {
    final String field;
    final int value;

    private WindowCommand(String field, int value) { this.field = field; this.value = value; }

    static boolean handles(String action) {
        return action != null && (action.startsWith("windows:") || action.startsWith("sunroof:")
                || action.startsWith("sunshade:"));
    }

    static WindowCommand parse(String action) {
        if (action == null) return null;
        switch (action) {
            case "sunroof:open": return new WindowCommand("SunroofControl", 1);
            case "sunroof:close": return new WindowCommand("SunroofControl", 2);
            case "sunroof:vent": return new WindowCommand("SunroofControl", 4);
            case "sunshade:open": return new WindowCommand("SunroofControl", 5);
            case "sunshade:close": return new WindowCommand("SunroofControl", 6);
            case "windows:vent": return new WindowCommand("ALL_WINDOW_CONTROL", 6);
        }
        String[] p = action.split(":", -1);
        if ((p.length != 2 && p.length != 3) || !p[0].equals("windows")) return null;
        String operation = p[p.length - 1];
        if (!operation.equals("open") && !operation.equals("close")) return null;
        String field;
        if (p.length == 2) field = "ALL_WINDOW_CONTROL";
        else switch (p[1]) {
            case "driver": field = "DRIVER_WINDOW_CONTROL"; break;
            case "passenger": field = "PAS_WIDOW_CONTROL"; break;
            case "rear_left": field = "LEFT_BACK_WINDOW_CONTROL"; break;
            case "rear_right": field = "RIGHT_BACK_WINDOW_CONTROL"; break;
            case "front": field = "ALL_FRONT_WINDOW_CONTROL"; break;
            case "rear": field = "ALL_REAR_WINDOW_CONTROL"; break;
            case "left": field = "ALL_LEFT_WINDOW_CONTROL"; break;
            case "right": field = "ALL_RIGHT_WINDOW_CONTROL"; break;
            default: return null;
        }
        return new WindowCommand(field, operation.equals("open") ? 3 : 1);
    }
}
