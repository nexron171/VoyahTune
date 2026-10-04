package ru.big.town.anative;

import org.junit.Test;
import ru.big.town.common.InfrastructureProfile;
import static org.junit.Assert.*;

public class InfrastructureProfileTest {
    @Test public void signedProfileChoosesExactlyOneAutomaticRestoreSource() {
        assertFalse(InfrastructureProfile.fromValue("pi").usesAccHooks());
        assertTrue(InfrastructureProfile.fromValue("od").usesAccHooks());
    }

    @Test public void unknownProfileNeverSilentlyChangesTheRestoreMechanism() {
        for (String value : new String[]{"", "PI", "full", "light", "future"}) {
            try {
                InfrastructureProfile.fromValue(value);
                fail("Accepted unknown infrastructure: " + value);
            } catch (IllegalArgumentException expected) { }
        }
    }
}
