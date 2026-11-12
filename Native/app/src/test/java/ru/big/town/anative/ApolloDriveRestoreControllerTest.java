package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ApolloDriveRestoreControllerTest {
    private int applications;
    private long wakeGeneration;
    private final ApolloDriveRestoreController controller =
            new ApolloDriveRestoreController(() -> applications++, () -> wakeGeneration);

    @Test
    public void firstDriveAfterDoorAppliesOnceAndNextOpeningRearms() {
        gear(CanBusEvent.Origin.LIVE, 0);
        door(CanBusEvent.Origin.LIVE, 1);
        door(CanBusEvent.Origin.LIVE, 0);
        gear(CanBusEvent.Origin.LIVE, 2);
        assertEquals(0, applications);

        gear(CanBusEvent.Origin.LIVE, 3);
        gear(CanBusEvent.Origin.LIVE, 3);
        gear(CanBusEvent.Origin.LIVE, 2);
        gear(CanBusEvent.Origin.LIVE, 3);
        assertEquals(1, applications);

        door(CanBusEvent.Origin.LIVE, 1);
        gear(CanBusEvent.Origin.LIVE, 0);
        gear(CanBusEvent.Origin.LIVE, 3);
        assertEquals(2, applications);
    }

    @Test
    public void snapshotsAndReplayNeitherArmNorConsumeDrive() {
        for (CanBusEvent.Origin origin :
                new CanBusEvent.Origin[] {CanBusEvent.Origin.SEED, CanBusEvent.Origin.REPLAY}) {
            door(origin, 1);
            gear(CanBusEvent.Origin.LIVE, 0);
            gear(CanBusEvent.Origin.LIVE, 3);
        }
        assertEquals(0, applications);

        gear(CanBusEvent.Origin.LIVE, 0);
        door(CanBusEvent.Origin.LIVE, 1);
        gear(CanBusEvent.Origin.SEED, 3);
        gear(CanBusEvent.Origin.REPLAY, 3);
        assertEquals(0, applications);
        gear(CanBusEvent.Origin.LIVE, 3);
        assertEquals(1, applications);
    }

    @Test
    public void reconnectDiscardsPendingDoorAndRequiresANewOpening() {
        door(CanBusEvent.Origin.LIVE, 1);
        controller.onEvent(CanBusEvent.connectionLost(2, 1, 0, 1));
        controller.onEvent(CanBusEvent.connection(3, 2, 0));
        door(CanBusEvent.Origin.SEED, 1);
        gear(CanBusEvent.Origin.LIVE, 3);
        assertEquals(0, applications);

        door(CanBusEvent.Origin.LIVE, 0);
        door(CanBusEvent.Origin.LIVE, 1);
        gear(CanBusEvent.Origin.LIVE, 0);
        gear(CanBusEvent.Origin.LIVE, 3);
        assertEquals(1, applications);
    }

    @Test
    public void invalidStatesAndDriveWithoutDoorDoNotApply() {
        door(CanBusEvent.Origin.LIVE, -1);
        gear(CanBusEvent.Origin.LIVE, -1);
        gear(CanBusEvent.Origin.LIVE, 3);
        assertEquals(0, applications);

        door(CanBusEvent.Origin.LIVE, 1);
        gear(CanBusEvent.Origin.LIVE, -1);
        gear(CanBusEvent.Origin.LIVE, 3);
        assertEquals(0, applications);
        gear(CanBusEvent.Origin.LIVE, 0);
        gear(CanBusEvent.Origin.LIVE, 3);
        assertEquals(1, applications);
    }

    private void door(CanBusEvent.Origin origin, int state) {
        controller.onEvent(CanBusEvent.door(origin, 1, 0, 0, state));
    }

    @Test
    public void sleepDiscardsPendingDoorWithoutRequiringAReconnect() {
        door(CanBusEvent.Origin.LIVE, 1);
        wakeGeneration++;
        gear(CanBusEvent.Origin.LIVE, 3);
        assertEquals(0, applications);

        door(CanBusEvent.Origin.LIVE, 1);
        gear(CanBusEvent.Origin.LIVE, 0);
        gear(CanBusEvent.Origin.LIVE, 3);
        assertEquals(1, applications);
    }

    private void gear(CanBusEvent.Origin origin, int state) {
        controller.onEvent(CanBusEvent.gear(origin, 1, 0, 0, state));
    }
}
