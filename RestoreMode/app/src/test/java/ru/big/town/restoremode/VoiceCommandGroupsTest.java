package ru.big.town.restoremode;

import org.junit.Test;
import java.util.EnumSet;
import java.util.Set;
import static org.junit.Assert.*;
import static ru.big.town.restoremode.VoiceCommandGroups.Group.*;

public class VoiceCommandGroupsTest {
    @Test public void completeCatalogHasOneGroupExceptDuplicatedSeatHeating() {
        int duplicated = 0;
        for (VoiceCommandCatalog.Command command : VoiceCommandCatalog.builtIns()) {
            Set<VoiceCommandGroups.Group> groups = VoiceCommandGroups.forAction(command.action);
            assertFalse(command.action, groups.isEmpty());
            if (groups.size() > 1) {
                assertTrue(command.action, command.action.startsWith("seat:") && command.action.contains(":heat:"));
                assertEquals(EnumSet.of(SEATS, HEATING), groups);
                duplicated++;
            }
            if (groups.contains(OTHER)) {
                assertTrue(command.action, command.action.equals("open_voyahtune") || command.action.equals("system_back")
                        || command.action.equals("close_all") || command.action.equals("reboot"));
            }
        }
        assertEquals(10, duplicated); // Two seats, on/off and three levels each.
    }

    @Test public void knownCanExamplesAreCategorizedButArbitraryCommandsStayInOther() {
        assertEquals(EnumSet.of(HEATING), VoiceCommandGroups.forAction("can:65080000c1c020000000"));
        assertEquals(EnumSet.of(HEATING), VoiceCommandGroups.forAction("can:65080000c1c010000000"));
        assertEquals(EnumSet.of(CAR), VoiceCommandGroups.forAction("can:7a080000000001000000"));
        assertEquals(EnumSet.of(CAR), VoiceCommandGroups.forAction("can:7a080000000002000000"));
        for (String action : new String[]{"can:custom", "app:example", "call:12345", "split:0", "future_action"})
            assertEquals(action, EnumSet.of(OTHER), VoiceCommandGroups.forAction(action));
    }

    @Test public void roofAndShadeAreWindowsButFuelAndChargeCapsAreCarSettings() {
        for (String action : new String[]{"windows:driver:open", "windows:vent", "sunroof:vent", "sunshade:close"})
            assertEquals(action, EnumSet.of(WINDOWS), VoiceCommandGroups.forAction(action));
        for (String action : new String[]{"port_cap:fuel", "port_cap:charge", "fuel_charge:80", "drive:OUTING", "suspension_maintenance:on", "suspension_maintenance:off"})
            assertEquals(action, EnumSet.of(CAR), VoiceCommandGroups.forAction(action));
        for (String action : new String[]{"seat:passenger:massage:waves", "seat:driver:vent:3"})
            assertEquals(action, EnumSet.of(SEATS), VoiceCommandGroups.forAction(action));
    }
}
