package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;
import ru.big.town.common.EnergyWidgetSettings;

public class LevelConsumptionTest {
    private void drive(LevelConsumption c,double from,double to,float start,float end) {
        c.observe(from,start);
        for(int i=1;i<=100;i++)c.observe(from+(to-from)*i/100,start+(end-start)*i/100);
    }
    @Test public void onlyDecreasesCountIncludingSmallRisesAndRefillsAtRest() {
        LevelConsumption c=new LevelConsumption();
        drive(c,0,10,50,49);c.observe(10,80);drive(c,10,20,80,79);
        assertEquals(2,c.decrease(),.0001);assertEquals(20,c.distance(),.0001);
        assertEquals(5.6,c.average(56),.0001);assertEquals(4.3,c.average(43),.0001);
        c.observe(20,79.2f);c.observe(20,79);
        assertEquals(2.2,c.decrease(),.0001);
    }
    @Test public void lateObservationAndGapsDoNotInventConsumptionOrDistance() {
        LevelConsumption c=new LevelConsumption();drive(c,35,45,50,49);
        c.breakSegment();drive(c,60,70,30,29);
        assertEquals(20,c.distance(),.0001);assertEquals(2,c.decrease(),.0001);
        c.observe(70,Float.NaN);c.observe(71,20);c.observe(71.1,19.9f);
        assertEquals(20.1,c.distance(),.0001);assertEquals(2.1,c.decrease(),.0001);
    }
    @Test public void restartRetainsTotalsButDoesNotBridgeSensorLevels() {
        LevelConsumption c=new LevelConsumption();drive(c,0,10,50,49);
        LevelConsumption restored=new LevelConsumption();restored.restore(c.snapshot());
        drive(restored,10,20,80,79);
        assertEquals(2,restored.decrease(),.0001);assertEquals(20,restored.distance(),.0001);
        restored.clear();assertTrue(Float.isNaN(restored.average(56)));
    }
    @Test public void noHistoryShortHistoryAndBadCapacityAreUnavailable() {
        LevelConsumption c=new LevelConsumption();assertTrue(Float.isNaN(c.average(43)));
        drive(c,0,.5,50,49);assertTrue(Float.isNaN(c.average(43)));
        drive(c,.5,1,49,49);assertEquals(43,c.average(43),.001);
        assertTrue(Float.isNaN(c.average(0)));assertTrue(Float.isNaN(c.average(Float.NaN)));
    }
    @Test public void capacityChangeRescalesTotalsWithoutChangingObservations() {
        LevelConsumption c=new LevelConsumption();drive(c,0,10,80,78);
        assertEquals(8.6,c.average(43),.001);assertEquals(10.1,c.average(50.5f),.001);
        assertEquals(2,c.decrease(),.001);
        assertEquals(50.5,EnergyWidgetSettings.parseCapacity("50,5"),0);
        for(String s:new String[]{"0","-1","","x","NaN","43.25"})assertTrue(Float.isNaN(EnergyWidgetSettings.parseCapacity(s)));
    }
    @Test public void electricityAverageSubtractsSmallRecoveryWithoutChangingObservedDistance() {
        LevelConsumption c = new LevelConsumption(true);
        drive(c, 0, 10, 80, 79);
        c.observe(10, 79.5f);
        assertEquals(.5, c.decrease(), .00001);
        assertEquals(10, c.distance(), .00001);
        assertEquals(2.15, c.average(43), .00001);
        drive(c, 10, 20, 79.5f, 78.5f);
        assertEquals(1.5, c.decrease(), .00001);
        assertEquals(3.225, c.average(43), .00001);
    }
    @Test public void electricityRecoveryTotalsPersistSignedButOldLevelsNeverBridgeRestart() {
        LevelConsumption c = new LevelConsumption(true);
        drive(c, 0, 10, 80, 80.5f);
        assertEquals(-2.15, c.average(43), .00001);
        LevelConsumption restored = new LevelConsumption(true); restored.restore(c.snapshot());
        assertEquals(-.5, restored.decrease(), .00001);
        drive(restored, 20, 30, 90, 89);
        assertEquals(.5, restored.decrease(), .00001);
        assertEquals(20, restored.distance(), .00001);
        assertEquals(1.075, restored.average(43), .00001);
        assertTrue(Float.isNaN(EnergyWidgetSettings.average(-.5, 10, 56))); // Fuel remains unsigned.
    }
    @Test public void twentyTwoKmAndTenPercentNetUseIsNineteenPointFiveDespiteSmallLevelCycles() {
        for (boolean fluctuations : new boolean[]{false, true}) {
            LevelConsumption net = new LevelConsumption(true), gross = new LevelConsumption();
            net.observe(0, 80); gross.observe(0, 80);
            for (int i = 1; i <= 220; i++) {
                double km = i / 10d; float soc = 80 - 10 * i / 220f;
                net.observe(km, soc); gross.observe(km, soc);
                if (fluctuations && i % 20 == 0 && i <= 200) {
                    for (float value : new float[]{soc + .5f, soc + .5f, soc}) {
                        // SOC can arrive while the odometer is unchanged; getter repeats add no distance.
                        net.observe(km, value); gross.observe(km, value);
                    }
                }
            }
            assertEquals(22, net.distance(), .00001); assertEquals(22, gross.distance(), .00001);
            assertEquals(10, net.decrease(), .00001);
            assertEquals(19.54545, net.average(43), .0001);
            assertEquals(fluctuations ? 15 : 10, gross.decrease(), .00001);
            assertEquals(fluctuations ? 29.31818 : 19.54545, gross.average(43), .0001);
        }
    }
}
