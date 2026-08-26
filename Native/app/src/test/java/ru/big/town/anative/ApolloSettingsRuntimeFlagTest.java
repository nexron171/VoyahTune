package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ApolloSettingsRuntimeFlagTest {
    private static final String BOOT_A = "12345678-1234-4abc-8def-1234567890ab";
    private static final String BOOT_B = "87654321-4321-4cba-8fed-ba0987654321";

    @Test
    public void exactCurrentBootOptInIsEnabled() {
        assertTrue(ApolloSettingsRuntimeFlag.isEnabledForBoot(
                ApolloSettingsRuntimeFlag.encodeEnabled(BOOT_A), BOOT_A));
    }

    @Test
    public void rebootInvalidatesPreviouslyEnabledFlag() {
        assertFalse(ApolloSettingsRuntimeFlag.isEnabledForBoot(
                ApolloSettingsRuntimeFlag.encodeEnabled(BOOT_A), BOOT_B));
    }

    @Test
    public void missingMalformedDuplicateAndUnknownFieldsFailClosed() {
        assertFalse(ApolloSettingsRuntimeFlag.isEnabledForBoot(null, BOOT_A));
        assertFalse(ApolloSettingsRuntimeFlag.isEnabledForBoot("v=1\nboot=nope\nenabled=1\n", BOOT_A));
        assertFalse(ApolloSettingsRuntimeFlag.isEnabledForBoot(
                "v=1\nboot=" + BOOT_A + "\nenabled=1\nenabled=1\n", BOOT_A));
        assertFalse(ApolloSettingsRuntimeFlag.isEnabledForBoot(
                "v=1\nboot=" + BOOT_A + "\nenabled=1\nextra=1\n", BOOT_A));
        assertFalse(ApolloSettingsRuntimeFlag.isEnabledForBoot(
                "v=1\nboot=" + BOOT_A + "\nenabled=0\n", BOOT_A));
    }
}
