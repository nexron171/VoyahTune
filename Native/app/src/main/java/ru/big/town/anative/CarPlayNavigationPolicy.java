package ru.big.town.anative;

/** OD panel reports are scoped to the live fullscreen host on each physical display. */
final class CarPlayNavigationPolicy {
    static final String DOCK_HIDDEN = "voyahtune_carplay_dock_hidden_";
    static final String STATUS_BAR_HIDDEN = "voyahtune_carplay_statusbar_hidden_";
    private static final String HOST = "ru.big.town.anative.CarPlayFullscreenActivity";

    private CarPlayNavigationPolicy() {}

    static boolean shouldForceNavigation(
            boolean od, int displayId, String activity, int dockHidden, int statusBarHidden) {
        if (!od || (displayId != 0 && displayId != 1) || !HOST.equals(activity)) {
            return false;
        }
        // An older or not-yet-attached hook must not leave fullscreen CarPlay without navigation.
        return dockHidden != 0 || statusBarHidden != 0;
    }
}
