package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class TripFuelEstimateTest {
    @Test public void tenPercentOverHundredKmUsesExactlyFiftySixLiterTank() {
        TripFuelEstimate e=new TripFuelEstimate();e.observe(0,80);e.observe(100,70);
        assertEquals(5.6f,e.average(),.0001f);
    }
    @Test public void lateStartUsesObservedDistanceRatherThanWholeTrip() {
        TripFuelEstimate e=new TripFuelEstimate();e.observe(35,30);e.observe(45,29);
        assertEquals(10,e.distanceKm(),0);assertEquals(5.6f,e.average(),.0001f);
    }
    @Test public void needsOneKmButParkedFuelBurnRemainsInTripEstimate() {
        TripFuelEstimate e=new TripFuelEstimate();e.observe(0,80);e.observe(0,79);
        assertTrue(Float.isNaN(e.average()));e.observe(.9f,79);assertTrue(Float.isNaN(e.average()));
        e.observe(10,79);assertEquals(5.6f,e.average(),.0001f);
    }
    @Test public void smallReboundsDoNotAccumulateFakeConsumption() {
        TripFuelEstimate e=new TripFuelEstimate();e.observe(0,80);e.observe(10,79);e.observe(20,80);
        assertEquals(0,e.average(),0);e.observe(30,79);
        assertEquals(56f/30,e.average(),.0001f);
        e.observe(40,81);assertEquals(0,e.average(),0);
    }
    @Test public void refillKeepsConsumedFuelAndStartsNewFuelBaseline() {
        TripFuelEstimate e=new TripFuelEstimate();e.observe(0,50);e.observe(50,45);e.observe(50,90);
        e.observe(100,85);assertEquals(5.6f,e.average(),.0001f);
    }
    @Test public void gradualRefillAndSensorStepsDoNotProduceNegativeValues() {
        TripFuelEstimate e=new TripFuelEstimate();e.observe(0,50);e.observe(50,45);
        e.observe(50,48);e.observe(50,52);e.observe(50,57);e.observe(100,52);
        assertEquals(5.6f,e.average(),.0001f);
    }
    @Test public void rollbackStartsNewTripAndUnavailableFuelDoesNotResetBaseline() {
        TripFuelEstimate e=new TripFuelEstimate();e.observe(0,80);e.observe(100,70);
        e.observe(101,Float.NaN);assertEquals(5.6f,e.average(),.0001f);
        e.observe(0,70);assertTrue(Float.isNaN(e.average()));e.observe(10,69);
        assertEquals(5.6f,e.average(),.0001f);
    }
    @Test public void restartPreservesBaselineAndRefillAccounting() {
        TripFuelEstimate e=new TripFuelEstimate();e.observe(0,50);e.observe(50,45);e.observe(50,90);
        TripFuelEstimate restored=new TripFuelEstimate();restored.restore(e.snapshot());restored.observe(100,85);
        assertEquals(5.6f,restored.average(),.0001f);
        restored.clear();assertTrue(Float.isNaN(restored.average()));
        restored.restore(new float[]{0,100,50,45,101,0});assertTrue(Float.isNaN(restored.average()));
    }
}
