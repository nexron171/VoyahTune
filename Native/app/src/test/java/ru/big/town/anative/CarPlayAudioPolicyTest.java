package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;
import ru.big.town.common.CarPlayAudioProtocol;

public class CarPlayAudioPolicyTest {
    @Test public void focusAttributesResolveToTheFourOemVolumeGroups() {
        for (int stream : CarPlayAudioProtocol.streams()) {
            CarPlayAudioPolicy policy = CarPlayAudioPolicy.forStream(stream);
            // The relevant branches of Qinggan CarConfiguration.e(usage, content), from
            // AudioPolicy 1.0.0 on the target Android 11 HU.
            int resolved = policy.usage == 12 ? 1 : policy.usage == 2 && policy.content == 11 ? 6
                    : policy.usage == 16 && policy.content == 14 ? 22 : 3;
            assertEquals(stream, resolved);
        }
        assertEquals(2, CarPlayAudioPolicy.forStream(6).gain);
        assertEquals(3, CarPlayAudioPolicy.forStream(1).gain);
        assertEquals(3, CarPlayAudioPolicy.forStream(22).gain);
    }
    @Test public void unknownStreamsDoNotAcquireFocusOrWriteVolume() {
        for (int stream : new int[] {-1, 0, 5, 9, 100}) {
            assertFalse(CarPlayAudioProtocol.validStream(stream));
            assertNull(CarPlayAudioPolicy.forStream(stream));
        }
    }
    @Test public void volumeIsBoundedByTheReportedOemMaximum() {
        assertEquals(0, CarPlayAudioPolicy.clampVolume(0, 32));
        assertEquals(18, CarPlayAudioPolicy.clampVolume(18, 32));
        assertEquals(32, CarPlayAudioPolicy.clampVolume(100, 32));
        assertThrows(IllegalArgumentException.class, () -> CarPlayAudioPolicy.clampVolume(-1, 32));
        assertThrows(IllegalArgumentException.class, () -> CarPlayAudioPolicy.clampVolume(5, -1));
    }
}
