package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class BatteryConsumptionTest {
    @Test public void smallRecoveryOffsetsUseAndOnePercentagePointIsInclusive() {
        BatteryConsumption c = new BatteryConsumption();
        assertEquals(1, c.delta(80, 79), 0);
        assertEquals(-.5, c.delta(79, 79.5), 0);
        assertEquals(-.5, c.delta(79.5, 80), 0);
        c.breakSegment();
        assertEquals(-1, c.delta(20, 21), 0); // Absolute p.p., not 1% of the starting SOC.
        assertEquals(-1, new BatteryConsumption().delta(79.9f, 80.9f), .00001);
        assertEquals(-1, new BatteryConsumption().delta(63.3f, 64.3f), .00001);
    }
    @Test public void largerRiseExcludesTheEntireEpisodeRatherThanCreditingItsFirstPercent() {
        BatteryConsumption c = new BatteryConsumption();
        double used = c.delta(80, 79);
        used += c.delta(79, 79.4);
        assertEquals(.6, used, .00001);
        used += c.delta(79.4, 79.4); // Identical getter/callback does not reset the rise.
        used += c.delta(79.4, 80);
        assertEquals(0, used, .00001);
        used += c.delta(80, 80.1);
        assertEquals(1, used, .00001);
        used += c.delta(80.1, 90);
        used += c.delta(90, 89);
        assertEquals(2, used, .00001);
        assertEquals(0, new BatteryConsumption().delta(79, 80.001), 0);
        assertEquals(0, new BatteryConsumption().delta(79, 80.00002), 0);
    }
    @Test public void decreasesSeparateIndependentRecoveryEpisodes() {
        BatteryConsumption c = new BatteryConsumption();
        double used = c.delta(80, 79) + c.delta(79, 80);
        used += c.delta(80, 79) + c.delta(79, 80);
        assertEquals(0, used, 0); // Each rise is 1 p.p., although their sum is 2.
    }
    @Test public void unknownObservationsAndExplicitGapsDoNotCarryAnUnfinishedEpisode() {
        BatteryConsumption c = new BatteryConsumption();
        assertEquals(-.8, c.delta(79, 79.8), .00001);
        c.breakSegment();
        assertEquals(-.8, c.delta(90, 90.8), .00001);
        assertEquals(0, c.delta(90.8, Double.NaN), 0);
        assertEquals(0, c.delta(Double.NaN, 95), 0);
        assertEquals(-.8, c.delta(95, 95.8), .00001);
    }
}
