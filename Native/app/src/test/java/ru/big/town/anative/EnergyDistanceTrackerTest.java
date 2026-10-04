package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnergyDistanceTrackerTest {
    @Test public void newTripKeepsAxisAndSignalsOnlyTripReset() {
        EnergyDistanceTracker d=new EnergyDistanceTracker();
        assertFalse(d.observe(35,20));d.observe(35.1f,21);double before=d.distance();
        assertTrue(d.observe(0,0));assertEquals(before,d.distance(),0);
        assertFalse(d.observe(.1f,1));assertEquals(before+.1,d.distance(),.0001);
    }
    @Test public void rebootRestoresAxisWithoutInventingOfflineDistance() {
        EnergyDistanceTracker d=new EnergyDistanceTracker();d.observe(10,10);d.observe(10.1f,11);
        EnergyDistanceTracker next=new EnergyDistanceTracker();next.restore(d.snapshot());
        next.observe(20,20);assertEquals(d.distance(),next.distance(),0);
        next.observe(20.1f,21);assertEquals(d.distance()+.1,next.distance(),.0001);
    }
    @Test public void restoredBaselineDetectsNewTripAndInvalidInputDoesNotClearAxis() {
        EnergyDistanceTracker d=new EnergyDistanceTracker();d.restore(new double[]{150,70,100});
        assertTrue(d.observe(0,0));assertEquals(150,d.distance(),0);
        d.observe(Float.NaN,-1);d.observe(10,1);assertEquals(150,d.distance(),0);
    }
}
