package ru.big.town.common;

import java.util.regex.Pattern;

/** An app and its instrument-cluster card, stored together in a button or voice assignment. */
public final class ClusterAppAction {
    public static final String PREFIX = "cluster_app:";
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+");

    public enum Placement {
        MEDIA("media"),
        CENTER("center");

        private final String prefix;

        Placement(String key) {
            prefix = PREFIX + key + ":";
        }

        public String actionFor(String packageName) {
            return prefix + packageName;
        }
    }

    public final String packageName;
    private final Placement placement;

    private ClusterAppAction(Placement placement, String packageName) {
        this.placement = placement;
        this.packageName = packageName;
    }

    public Placement placementFor(VehiclePlatform platform) {
        return platform.isH97XY() ? Placement.MEDIA : placement;
    }

    public static ClusterAppAction parse(String action) {
        if (action == null || !action.startsWith(PREFIX)) return null;
        for (Placement placement : Placement.values()) {
            if (action.startsWith(placement.prefix)) {
                String packageName = action.substring(placement.prefix.length());
                if (PACKAGE_NAME.matcher(packageName).matches()) {
                    return new ClusterAppAction(placement, packageName);
                }
            }
        }
        return null;
    }
}
