package ru.big.town.common;

/** A scan pause expires unless the live CarPlay owner renews it. */
public final class CarPlayWifiScanProtocol {
    public static final String PAUSED = "wifiScanPaused";
    public static final String LEASE_SETTING = "voyahtune_carplay_wifi_scan_lease";
    public static final long RENEW_INTERVAL_MILLIS = 3000;
    public static final long LEASE_MILLIS = 10000;

    private CarPlayWifiScanProtocol() {}

    public static String leaseValue(int bootCount, long requestedAt, long now) {
        if (bootCount < 0
                || requestedAt <= 0
                || requestedAt > now
                || now - requestedAt >= LEASE_MILLIS) {
            return "";
        }
        return bootCount + ":" + requestedAt;
    }
}
