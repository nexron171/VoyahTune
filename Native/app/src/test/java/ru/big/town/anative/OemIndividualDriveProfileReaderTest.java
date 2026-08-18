package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class OemIndividualDriveProfileReaderTest {
    @Test
    public void accountIdUsesOemSecondLastPercentField() {
        assertEquals("123456", OemIndividualDriveProfileReader.parseAccountId(
                "nickname%phone%token%123456%tail"));
    }

    @Test
    public void guestProfileUsesGuestSettingsSuffix() {
        assertEquals("guest", OemIndividualDriveProfileReader.parseAccountId(" guest\n"));
    }

    @Test
    public void malformedAccountPayloadIsRejected() {
        assertNull(OemIndividualDriveProfileReader.parseAccountId(null));
        assertNull(OemIndividualDriveProfileReader.parseAccountId(""));
        assertNull(OemIndividualDriveProfileReader.parseAccountId("too%short"));
        assertNull(OemIndividualDriveProfileReader.parseAccountId("a%b%c%d%%"));
    }

    @Test
    public void savedIndividualValuesAreKeptExactly() {
        DriveModeCanPolicy.IndividualProfile profile =
                OemIndividualDriveProfileReader.parseProfile("3", "2");

        assertEquals(3, profile.steering);
        assertEquals(2, profile.accelerator);
    }

    @Test
    public void missingSettingsUseStockOemDefaults() {
        DriveModeCanPolicy.IndividualProfile profile =
                OemIndividualDriveProfileReader.parseProfile(null, null);

        assertEquals(2, profile.steering);
        assertEquals(1, profile.accelerator);
    }

    @Test
    public void invalidIndividualSettingsAreRejectedInsteadOfOverwritten() {
        assertNull(OemIndividualDriveProfileReader.parseProfile("1", "2"));
        assertNull(OemIndividualDriveProfileReader.parseProfile("2", "4"));
        assertNull(OemIndividualDriveProfileReader.parseProfile("bad", "2"));
        assertNull(OemIndividualDriveProfileReader.parseProfile("2", "bad"));
    }
}
