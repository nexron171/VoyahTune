package ru.big.town.anative;

final class CloseAppsPolicy {
    private CloseAppsPolicy() { }
    static boolean canStop(String pkg, boolean system, String home) {
        return pkg != null && !system && !pkg.equals(home)
                && !pkg.equals("ru.big.town.restoremode") && !pkg.equals("ru.big.town.anative")
                && !pkg.equals("ru.big.town.updater") && !pkg.equals("big.town.runyn")
                && !pkg.startsWith("com.qinggan") && !pkg.startsWith("com.android.car");
    }
}
