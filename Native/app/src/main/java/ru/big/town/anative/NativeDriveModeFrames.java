package ru.big.town.anative;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Android-free mapping for the legacy libqg_hal drive-mode frames. */
final class NativeDriveModeFrames {
    private static final Map<String, String[]> FRAMES;

    static {
        Map<String, String[]> frames = new HashMap<>();
        frames.put("ECO", new String[]{
                "6c 08 00 3e 5a 01 88 01 00 20",
                "68 08 02 00 00 f0 2c 04 08 00"
        });
        frames.put("COMFORT", new String[]{
                "6c 08 00 3e 5a 01 88 01 00 40",
                "68 08 02 00 00 f0 2c 04 10 00"
        });
        frames.put("SPORT", new String[]{
                "6c 08 00 3e 5a 01 88 01 00 60",
                "68 08 03 00 00 f0 2c 04 18 00",
                "6f 08 0d 00 80 13 83 00 00 40"
        });
        frames.put("OUTING", new String[]{
                "6c 08 00 3e 5a 01 88 01 00 80",
                "68 08 02 00 00 f0 2c 04 18 00"
        });
        frames.put("SNOW", new String[]{
                "6c 08 40 3e 5a 01 88 01 00 c0",
                "68 08 02 00 00 f0 2c 04 10 00"
        });
        // This is intentionally the proven v3.2 mapping. The OEM TX77 profile-aware variant
        // must not be mixed with native frames in the same apply pass.
        frames.put("INDIVIDUAL", new String[]{
                "6c 08 00 3e 5a 01 88 01 00 a0",
                "68 08 03 00 00 f0 2c 04 18 00"
        });
        FRAMES = Collections.unmodifiableMap(frames);
    }

    private NativeDriveModeFrames() {}

    static byte[][] forMode(String mode) {
        String[] encoded = FRAMES.get(mode);
        if (encoded == null) return new byte[0][];
        byte[][] decoded = new byte[encoded.length][];
        for (int i = 0; i < encoded.length; i++) {
            decoded[i] = decode(encoded[i]);
        }
        return decoded;
    }

    private static byte[] decode(String encoded) {
        String compact = encoded.replace(" ", "");
        byte[] result = new byte[compact.length() / 2];
        for (int i = 0; i < compact.length(); i += 2) {
            result[i / 2] = (byte) Integer.parseInt(compact.substring(i, i + 2), 16);
        }
        return result;
    }
}
