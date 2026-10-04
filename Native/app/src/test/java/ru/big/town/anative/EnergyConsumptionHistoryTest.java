package ru.big.town.anative;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import org.junit.Test;
import static org.junit.Assert.*;

public class EnergyConsumptionHistoryTest {
    private long time;
    private void sample(EnergyConsumptionHistory h,double km,float ev,float fuel) {
        time+=100;h.sample(km,ev,fuel,time,time,time,time);
    }
    private EnergyHistoryState frame(EnergyConsumptionHistory h) {
        EnergyHistoryState s=new EnergyHistoryState();s.points=Collections.emptyList();s.distance=new double[]{h.snapshot().cursor,0,0};
        s.tripBattery=s.tripFuel=new double[]{0,0};s.consumption=h.snapshot();return s;
    }
    @Test public void absoluteQuantitiesSignedRecoveryAndRefills() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.25,79.9f,49);
        EnergyConsumptionHistory.Point p=h.points().get(0);assertEquals(.043,p.electricity(43),.00001);assertEquals(.56,p.fuel(56),.00001);
        sample(h,.5,80,60);p=h.points().get(1);assertEquals(-.043,p.electricity(43),.00001);assertEquals(0,p.fuel(56),0);
    }
    @Test public void refillBetweenDropsWhileParkedKeepsBothDecreases() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.1,80,49);
        sample(h,.1,80,60);sample(h,.25,80,59);assertEquals(1.12,h.points().get(0).fuel(56),.00001);
    }
    @Test public void chargeThresholdIsStrictAndIndependentOfFuel() {
        for(float rise:new float[]{5,5.01f}) {
            EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.25,80+rise,49);
            EnergyConsumptionHistory.Point p=h.points().get(0);assertEquals(.56,p.fuel(56),.00001);
            if(rise==5)assertEquals(-2.15,p.electricity(43),.00001);else assertTrue(Float.isNaN(p.electricity(43)));
        }
    }
    @Test public void multipleRisesAndLaterDropCannotHideCharge() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.05,83,50);
        sample(h,.1,79,50);sample(h,.1,82,50);sample(h,.25,77,50);
        assertEquals(6,h.points().get(0).evRise,0);assertTrue(Float.isNaN(h.points().get(0).electricity(43)));
        sample(h,.5,76,49);assertEquals(.43,h.points().get(1).electricity(43),.00001);
    }
    @Test public void partialAndZeroIntervalsAreDistinct() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.249,80,50);
        assertTrue(h.points().isEmpty());sample(h,.25,80,50);
        assertEquals(0,h.points().get(0).electricity(43),0);assertEquals(0,h.points().get(0).fuel(56),0);
    }
    @Test public void recordsFortyIntervalsFromOriginalEventsAndTrimsOldData() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();
        for(int i=0;i<=240;i++)sample(h,i*.05,80-i*.01f,50-i*.01f);
        assertEquals(40,h.points().size());assertEquals(2,h.points().get(0).start,.00001);assertEquals(12,h.points().get(39).end,.00001);
        for(EnergyConsumptionHistory.Point p:h.points())assertEquals(.25,p.end-p.start,.00001);
        sample(h,12.05,77,47);assertEquals(39,h.points().size());
    }
    @Test public void hundredMeterSourceKeepsCadenceButStoresActualBoundaries() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();for(int i=0;i<=100;i++)sample(h,i/10d,80-i*.01f,50);
        assertEquals(40,h.points().size());assertEquals(.3,h.points().get(0).end,0);
        assertEquals(.3,h.points().get(1).start,0);assertEquals(.5,h.points().get(1).end,0);
        assertEquals(.2,h.points().get(1).end-h.points().get(1).start,.00001);
    }
    @Test public void invalidSensorBreaksOnlyItsChannelUntilNextInterval() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.1,Float.NaN,49);sample(h,.25,79,49);
        assertTrue(Float.isNaN(h.points().get(0).electricity(43)));assertEquals(.56,h.points().get(0).fuel(56),.00001);
        sample(h,.5,78,48);assertEquals(.43,h.points().get(1).electricity(43),.00001);
    }
    @Test public void staleSensorAndDistanceCannotBecomeMeasuredZeros() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);
        time+=100;h.sample(.1,79,49,time,time,-1,time);sample(h,.25,79,49);
        assertTrue(Float.isNaN(h.points().get(0).electricity(43)));
        time+=100;h.sample(.4,78,48,time,-1,time,time);sample(h,.5,78,48);
        assertTrue(Float.isNaN(h.points().get(1).fuel(56)));assertTrue(h.points().get(1).gap);
    }
    @Test public void discontinuitiesDoNotBridgeUnknownConsumption() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.25,79,49);
        sample(h,2,70,40);assertEquals(1,h.points().size());sample(h,2.25,69,39);
        assertTrue(h.points().get(1).gap);assertEquals(.43,h.points().get(1).electricity(43),.00001);
        sample(h,1,68,38);assertEquals(2,h.points().size());
    }
    @Test public void longSamplePauseInvalidatesPartialButKeepsEarlierPoints() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.25,79,49);sample(h,.3,78,48);
        time+=20_000;sample(h,.5,70,40);
        assertEquals(.43,h.points().get(0).electricity(43),.00001);assertTrue(Float.isNaN(h.points().get(1).electricity(43)));
    }
    @Test public void capacitiesRecalculateWithoutChangingRawDeltas() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.25,79,49);
        EnergyConsumptionHistory.Point p=h.points().get(0);assertEquals(.43,p.electricity(43),.00001);
        assertEquals(.505,p.electricity(50.5f),.00001);assertEquals(.6,p.fuel(60),.00001);assertEquals(1,p.evDrop,0);
    }
    @Test public void completedAndUnfinishedDataPersistWithoutRestoringFreshness()throws Exception {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.25,79,49);sample(h,.3,78,48);
        EnergyHistoryState decoded=EnergyHistoryState.decode(frame(h).encode());
        assertEquals(1,decoded.consumption.evDrop,0);assertEquals(.3,decoded.consumption.cursor,0);
        EnergyConsumptionHistory restored=new EnergyConsumptionHistory();restored.restore(decoded.consumption);
        sample(restored,.5,70,40);assertEquals(.43,restored.points().get(0).electricity(43),.00001);
        assertTrue(Float.isNaN(restored.points().get(1).electricity(43)));
        sample(restored,.75,69,39);assertEquals(.43,restored.points().get(2).electricity(43),.00001);
    }
    @Test public void legacyVersionTwoLoadsWithoutFabricatingQuarterKilometerHistory()throws Exception {
        EnergyHistoryState s=new EnergyHistoryState();s.points=Collections.emptyList();s.distance=new double[]{100,30,10};
        s.tripBattery=new double[]{4,30};s.tripFuel=new double[]{3,30};
        // V2 header without points: 64 bytes followed immediately by its CRC.
        byte[] old=Arrays.copyOf(Base64.getDecoder().decode(s.encode()),72);ByteBuffer.wrap(old).putInt(2);
        CRC32 crc=new CRC32();crc.update(old,0,64);ByteBuffer.wrap(old).putLong(64,crc.getValue());
        EnergyHistoryState decoded=EnergyHistoryState.decode(Base64.getEncoder().encodeToString(old));
        assertEquals(100,decoded.distance[0],0);assertEquals(4,decoded.tripBattery[0],0);assertTrue(decoded.consumption.points.isEmpty());
    }
    @Test public void corruptAndSemanticallyInvalidConsumptionSnapshotsAreRejected()throws Exception {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.25,79,49);
        EnergyHistoryState s=frame(h);s.consumption.evRise=Double.NaN;
        try{EnergyHistoryState.decode(s.encode());fail();}catch(IOException expected){}
        s=frame(h);s.consumption.target=.1;
        try{EnergyHistoryState.decode(s.encode());fail();}catch(IOException expected){}
        byte[] bytes=Base64.getDecoder().decode(frame(h).encode());bytes[bytes.length-12]^=1;
        try{EnergyHistoryState.decode(Base64.getEncoder().encodeToString(bytes));fail();}catch(IOException expected){}
    }
}
