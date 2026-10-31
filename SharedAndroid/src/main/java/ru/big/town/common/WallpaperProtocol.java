package ru.big.town.common;

/** Native applies scenario wallpaper actions through RestoreMode's signed provider. */
public final class WallpaperProtocol {
    public static final String ACTION_PREFIX = "wallpaper:";
    public static final String AUTHORITY = "ru.big.town.restoremode.restoremodecontentprovider";
    public static final String METHOD_SET = "setDashboardWallpaper";
    public static final String RESULT_APPLIED = "applied";
    public static final String PERMISSION = "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";

    private WallpaperProtocol() {}

    public static boolean handles(String action) {
        return action != null && action.startsWith(ACTION_PREFIX);
    }

    public static String wallpaperId(String action) {
        return handles(action) ? action.substring(ACTION_PREFIX.length()) : null;
    }
}
