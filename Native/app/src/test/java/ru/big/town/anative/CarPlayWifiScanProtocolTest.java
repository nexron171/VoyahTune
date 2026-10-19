package ru.big.town.anative;

import org.junit.Test;

import ru.big.town.common.CarPlayWifiScanProtocol;

import static org.junit.Assert.assertEquals;

public final class CarPlayWifiScanProtocolTest {
    @Test
    public void preservesTheOriginalHeartbeatTimeAcrossWorkerDelays() {
        assertEquals("7:1000", CarPlayWifiScanProtocol.leaseValue(7, 1000, 5000));
        assertEquals("", CarPlayWifiScanProtocol.leaseValue(7, 1000, 11000));
    }

    @Test
    public void invalidBootFutureHeartbeatAndReleaseCannotPauseScanning() {
        assertEquals("", CarPlayWifiScanProtocol.leaseValue(-1, 1000, 1000));
        assertEquals("", CarPlayWifiScanProtocol.leaseValue(7, 1001, 1000));
        assertEquals("", CarPlayWifiScanProtocol.leaseValue(7, 0, 1000));
        assertEquals("", CarPlayWifiScanProtocol.leaseValue(7, -1, 1000));
    }
}
