package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import ru.big.town.common.CabinTemperatureProtocol;

public class CabinTemperatureTest {
    @Test
    public void decodesMeasuredFramesAndPreservesHalfDegrees() {
        assertEquals(20, CabinTemperatureProtocol.decode(120), 0);
        assertEquals(20.5, CabinTemperatureProtocol.decode(121), 0);
        assertEquals(18, CabinTemperatureProtocol.decode(116), 0);
        assertEquals(22, CabinTemperatureProtocol.decode(124), 0);
        assertEquals(-40, CabinTemperatureProtocol.decode(0), 0);
        assertTrue(Double.isNaN(CabinTemperatureProtocol.decode(255)));
        assertTrue(Double.isNaN(CabinTemperatureProtocol.decode(-1)));
        assertTrue(Double.isNaN(CabinTemperatureProtocol.decode(256)));
    }

    @Test
    public void retainsLastKnownValueWithoutExpiryAndIgnoresInvalidSamples() {
        CabinTemperatureReading reading = new CabinTemperatureReading();
        assertTrue(Double.isNaN(reading.value()));
        reading.accept(121, 1000, 1000);
        assertEquals(20.5, reading.value(), 0);
        reading.accept(255, 2000, 2000);
        assertEquals(20.5, reading.value(), 0);
        reading.accept(124, 3001, 3000);
        assertEquals(20.5, reading.value(), 0);
        reading.accept(124, 3000, 3000);
        reading.accept(120, 2999, 100_000_000);
        assertEquals(22, reading.value(), 0);
    }
}
