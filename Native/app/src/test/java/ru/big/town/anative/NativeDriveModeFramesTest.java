package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class NativeDriveModeFramesTest {
    @Test
    public void mappingsMatchProvenV32Frames() {
        assertFrames("ECO",
                "6c08003e5a0188010020", "6808020000f02c040800");
        assertFrames("COMFORT",
                "6c08003e5a0188010040", "6808020000f02c041000");
        assertFrames("SPORT",
                "6c08003e5a0188010060", "6808030000f02c041800",
                "6f080d00801383000040");
        assertFrames("OUTING",
                "6c08003e5a0188010080", "6808020000f02c041800");
        assertFrames("SNOW",
                "6c08403e5a01880100c0", "6808020000f02c041000");
        assertFrames("INDIVIDUAL",
                "6c08003e5a01880100a0", "6808030000f02c041800");
    }

    @Test
    public void unsupportedModeCannotProduceFrames() {
        assertEquals(0, NativeDriveModeFrames.forMode(null).length);
        assertEquals(0, NativeDriveModeFrames.forMode("UNKNOWN").length);
    }

    private static void assertFrames(String mode, String... expectedHex) {
        byte[][] actual = NativeDriveModeFrames.forMode(mode);
        assertEquals(expectedHex.length, actual.length);
        for (int i = 0; i < expectedHex.length; i++) {
            assertArrayEquals(decode(expectedHex[i]), actual[i]);
        }
    }

    private static byte[] decode(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            result[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return result;
    }
}
