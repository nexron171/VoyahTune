package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

public class CarPlayUltraTelemetryTest {
    @Test
    public void rangesUsePetrolAndRealElectricFieldsAndRejectUnknownSentinel() {
        int[] words = {350, 15278, 0, 800, 0, 0, 0, 72, 999, 666};
        assertArrayEquals(
                new float[] {15278, 350, 72},
                EnergyTelemetrySample.decode(EnergyTelemetrySample.ODOMETER, words).values,
                0);
        words[0] = 1023;
        words[7] = -1;
        float[] unavailable =
                EnergyTelemetrySample.decode(EnergyTelemetrySample.ODOMETER, words).values;
        assertTrue(Float.isNaN(unavailable[1]));
        assertTrue(Float.isNaN(unavailable[2]));
        words[0] = words[7] = 0;
        assertEquals(
                0,
                EnergyTelemetrySample.decode(EnergyTelemetrySample.ODOMETER, words).values[1],
                0);
    }
}
