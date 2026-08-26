package ru.big.town.anative;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small Android-free contract for the Apollo VehicleSettings current-boot loader transport.
 *
 * <p>The file is deliberately bound to Linux' per-boot UUID. The file itself may remain in app
 * storage after a reboot, but it can never enable the hook in a later boot.</p>
 */
final class ApolloSettingsRuntimeFlag {
    static final String FILE_NAME = "apollo_settings_runtime.v1";
    static final int MAX_PAYLOAD_CHARS = 192;
    private static final Pattern BOOT_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private ApolloSettingsRuntimeFlag() {}

    static String encodeEnabled(String bootId) {
        String normalized = normalizeBootId(bootId);
        if (normalized == null) throw new IllegalArgumentException("invalid boot id");
        return "v=1\nboot=" + normalized + "\nenabled=1\n";
    }

    static boolean isEnabledForBoot(String payload, String currentBootId) {
        String expectedBoot = normalizeBootId(currentBootId);
        if (payload == null || expectedBoot == null
                || payload.length() == 0 || payload.length() > MAX_PAYLOAD_CHARS) {
            return false;
        }

        String version = null;
        String boot = null;
        String enabled = null;
        Set<String> seen = new HashSet<>();
        String[] lines = payload.split("\\n", -1);
        for (String line : lines) {
            if (line.isEmpty()) continue;
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('=')) return false;
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!seen.add(key)) return false;
            switch (key) {
                case "v":
                    version = value;
                    break;
                case "boot":
                    boot = value;
                    break;
                case "enabled":
                    enabled = value;
                    break;
                default:
                    return false;
            }
        }
        return seen.size() == 3
                && "1".equals(version)
                && expectedBoot.equals(boot)
                && "1".equals(enabled);
    }

    static String normalizeBootId(String bootId) {
        if (bootId == null) return null;
        String normalized = bootId.trim().toLowerCase(java.util.Locale.ROOT);
        return BOOT_ID.matcher(normalized).matches() ? normalized : null;
    }
}
