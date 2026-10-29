package ru.voyahtune.agent.policy;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

public final class WindowPolicy {
    @FunctionalInterface public interface Settings { String read(String key) throws Exception; }

    public final boolean enabled;
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;
    public final int compactBottom;
    public final int liftType;
    public final Map<String, Integer> density;
    public final Set<String> fullscreen;

    private WindowPolicy(boolean enabled, int left, int top, int right, int bottom, int compactBottom, int liftType,
                         Map<String, Integer> density, Set<String> fullscreen) {
        if (left < 0 || top < 0 || right <= left || bottom <= top || compactBottom <= top || compactBottom > bottom
                || liftType != 1 && liftType != 2) throw new IllegalArgumentException("Invalid window viewport");
        this.enabled = enabled;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.compactBottom = compactBottom;
        this.liftType = liftType;
        this.density = Map.copyOf(density);
        this.fullscreen = Set.copyOf(fullscreen);
    }

    public static WindowPolicy read(Settings settings, int liftProperty) throws Exception {
        int enabled = integer(settings, "voyahtune_freeform", 1);
        if (enabled != 0 && enabled != 1) throw new IllegalArgumentException("Invalid freeform flag");
        Map<String, Integer> density = new LinkedHashMap<>();
        for (String name : packages(settings.read("voyahtune_dpi_packages"))) {
            int dpi = integer(settings, "voyahtune_dpi_" + name, 0);
            if (!name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") || dpi != 0 && (dpi < 100 || dpi > 640)) {
                throw new IllegalArgumentException("Invalid DPI policy for " + name);
            }
            density.put(name, dpi);
        }
        int lift = liftProperty == 1 || liftProperty == 2 ? liftProperty : integer(settings, "voyahtune_screen_lift_type", 2);
        return new WindowPolicy(enabled == 1, integer(settings, "voyahtune_win_left", 145), integer(settings, "voyahtune_win_top", 45),
                integer(settings, "voyahtune_win_right", 1920), integer(settings, "voyahtune_win_bottom", 720),
                integer(settings, "voyahtune_win_compact_bottom", 560), lift, density, packages(settings.read("voyahtune_fullscreen_apps")));
    }

    private static Set<String> packages(String csv) {
        Set<String> result = new LinkedHashSet<>();
        if (csv != null) for (String raw : csv.split(",")) if (!raw.trim().isEmpty()) result.add(raw.trim());
        return result;
    }

    public static int integer(Settings settings, String key, int fallback) throws Exception {
        String value = settings.read(key);
        if (value == null || value.strip().isEmpty()) return fallback;
        value = value.strip();
        double number;
        if (value.matches("0[xX][0-9a-fA-F]+")) number = new BigInteger(value.substring(2), 16).doubleValue();
        else if (value.matches("0[bB][01]+")) number = new BigInteger(value.substring(2), 2).doubleValue();
        else if (value.matches("0[oO][0-7]+")) number = new BigInteger(value.substring(2), 8).doubleValue();
        else {
            if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("Invalid integer " + key);
            number = Double.parseDouble(value);
        }
        if (!Double.isFinite(number) || number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid integer " + key);
        return (int) number;
    }

    public int effectiveBottom() { return liftType == 1 ? compactBottom : bottom; }
    public int density(String name) { return density.getOrDefault(name, 0); }
    public boolean fullscreen(String name) { return fullscreen.contains(name); }
    public WindowPolicy withLift(int lift) { return new WindowPolicy(enabled, left, top, right, bottom, compactBottom, lift, density, fullscreen); }

    public static boolean blacklisted(String name) {
        if (name == null || name.isEmpty() || name.startsWith("ru.big.town")) return true;
        if (name.equals("com.android.settings") || name.equals("com.android.documentsui")) return false;
        for (String prefix : new String[] {"com.android", "com.qinggan", "com.pateo", "com.baidu", "com.huawei", "com.iflytek", "com.iland", "com.mega", "com.qti", "com.qualcomm", "com.tencent", "com.nng.igo.primong", "com.bz.CA08"}) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
