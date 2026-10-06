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
    private String version(String snapshot,int version) {
        byte[] bytes=Base64.getDecoder().decode(snapshot);ByteBuffer.wrap(bytes).putInt(version);
        CRC32 crc=new CRC32();crc.update(bytes,0,bytes.length-8);
        ByteBuffer.wrap(bytes).putLong(bytes.length-8,crc.getValue());
        return Base64.getEncoder().encodeToString(bytes);
    }
    @Test public void absoluteQuantitiesSignedRecoveryAndRefills() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.1,79.9f,49);
        EnergyConsumptionHistory.Point p=h.points().get(0);assertEquals(.043,p.electricity(43),.00001);assertEquals(.56,p.fuel(56),.00001);
        sample(h,.2,80,60);p=h.points().get(1);assertEquals(-.043,p.electricity(43),.00001);assertEquals(0,p.fuel(56),0);
    }
    @Test public void refillBetweenDropsWhileParkedKeepsBothDecreases() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.02,80,49);
        sample(h,.02,80,60);sample(h,.1,80,59);assertEquals(1.12,h.points().get(0).fuel(56),.00001);
    }
    @Test public void chargeThresholdIsStrictAndIndependentOfFuel() {
        for(float rise:new float[]{5,5.01f}) {
            EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.1,80+rise,49);
            EnergyConsumptionHistory.Point p=h.points().get(0);assertEquals(.56,p.fuel(56),.00001);
            if(rise==5)assertEquals(-2.15,p.electricity(43),.00001);else assertTrue(Float.isNaN(p.electricity(43)));
        }
    }
    @Test public void multipleRisesAndLaterDropCannotHideCharge() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.01,83,50);
        sample(h,.02,79,50);sample(h,.02,82,50);sample(h,.1,77,50);
        assertEquals(6,h.points().get(0).evRise,0);assertTrue(Float.isNaN(h.points().get(0).electricity(43)));
        sample(h,.2,76,49);assertEquals(.43,h.points().get(1).electricity(43),.00001);
    }
    @Test public void partialAndZeroIntervalsAreDistinct() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.099,80,50);
        assertTrue(h.points().isEmpty());sample(h,.1,80,50);
        assertEquals(0,h.points().get(0).electricity(43),0);assertEquals(0,h.points().get(0).fuel(56),0);
    }
    @Test public void thresholdToleranceStillAdvancesAndRepeatedDistanceCannotCompleteAnEmptyInterval() throws Exception {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);
        sample(h,.0999,80,50);assertEquals(1,h.points().size());
        sample(h,.0999,80,50);assertEquals(1,h.points().size());
        assertTrue(h.snapshot().target>h.snapshot().cursor);
        assertEquals(1,EnergyHistoryState.decode(frame(h).encode()).consumption.points.size());
    }
    @Test public void recordsTwentyFiveIntervalsFromOriginalEventsAndTrimsOldData() throws Exception {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();
        for(int i=0;i<=50;i++)sample(h,i*.1,80-i*.01f,50-i*.01f);
        assertEquals(25,h.points().size());assertEquals(2.5,h.points().get(0).start,.00001);assertEquals(5,h.points().get(24).end,.00001);
        for(EnergyConsumptionHistory.Point p:h.points())assertEquals(.1,p.end-p.start,.00001);
        assertEquals(25,EnergyHistoryState.decode(frame(h).encode()).consumption.points.size());
        sample(h,5.05,77,47);assertEquals(24,h.points().size());
    }
    @Test public void hundredMeterSourceStoresOnlyActualIntervalsAndNeverCreatesAnEmptyRepeat() throws Exception {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();for(int i=0;i<=100;i++)sample(h,i/10d,80-i*.01f,50);
        assertEquals(25,h.points().size());assertEquals(7.5,h.points().get(0).start,.00001);
        assertEquals(7.6,h.points().get(0).end,.00001);assertEquals(10,h.points().get(24).end,.00001);
        for(EnergyConsumptionHistory.Point p:h.points())assertEquals(.1,p.end-p.start,.00001);
        sample(h,10,79,50);assertEquals(25,h.points().size());
        assertTrue(h.snapshot().target>h.snapshot().cursor);
        assertEquals(25,EnergyHistoryState.decode(frame(h).encode()).consumption.points.size());
    }
    @Test public void overshootRequiresAnotherRealHundredMetersAndLargeGapsStartNewObservation() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.15,79,49);
        assertEquals(1,h.points().size());assertEquals(.15,h.points().get(0).end,0);
        assertEquals(.25,h.snapshot().target,.00001);
        sample(h,.15,78,49);sample(h,.2,77,49);assertEquals(1,h.points().size());
        sample(h,.25,77,49);assertEquals(2,h.points().size());
        assertEquals(.1,h.points().get(1).end-h.points().get(1).start,.00001);
        assertEquals(.86,h.points().get(1).electricity(43),.00001);
        sample(h,.55,70,40);assertEquals(2,h.points().size());
        sample(h,.65,69,39);assertEquals(3,h.points().size());assertTrue(h.points().get(2).gap);
        assertEquals(.43,h.points().get(2).electricity(43),.00001);
    }
    @Test public void invalidSensorBreaksOnlyItsChannelUntilNextInterval() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.02,Float.NaN,49);sample(h,.1,79,49);
        assertTrue(Float.isNaN(h.points().get(0).electricity(43)));assertEquals(.56,h.points().get(0).fuel(56),.00001);
        sample(h,.2,78,48);assertEquals(.43,h.points().get(1).electricity(43),.00001);
    }
    @Test public void staleSensorAndDistanceCannotBecomeMeasuredZeros() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);
        time+=100;h.sample(.02,79,49,time,time,-1,time);sample(h,.1,79,49);
        assertTrue(Float.isNaN(h.points().get(0).electricity(43)));
        time+=100;h.sample(.14,78,48,time,-1,time,time);sample(h,.2,78,48);
        assertTrue(Float.isNaN(h.points().get(1).fuel(56)));assertTrue(h.points().get(1).gap);
    }
    @Test public void discontinuitiesDoNotBridgeUnknownConsumption() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.1,79,49);
        sample(h,2,70,40);assertEquals(1,h.points().size());sample(h,2.1,69,39);
        assertTrue(h.points().get(1).gap);assertEquals(.43,h.points().get(1).electricity(43),.00001);
        sample(h,1,68,38);assertEquals(2,h.points().size());
    }
    @Test public void longSamplePauseInvalidatesPartialButKeepsEarlierPoints() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.1,79,49);sample(h,.14,78,48);
        time+=20_000;sample(h,.2,70,40);
        assertEquals(.43,h.points().get(0).electricity(43),.00001);assertTrue(Float.isNaN(h.points().get(1).electricity(43)));
    }
    @Test public void capacitiesRecalculateWithoutChangingRawDeltas() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.1,79,49);
        EnergyConsumptionHistory.Point p=h.points().get(0);assertEquals(.43,p.electricity(43),.00001);
        assertEquals(.505,p.electricity(50.5f),.00001);assertEquals(.6,p.fuel(60),.00001);assertEquals(1,p.evDrop,0);
    }
    @Test public void completedAndUnfinishedDataPersistWithoutRestoringFreshness()throws Exception {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.1,79,49);sample(h,.14,78,48);
        EnergyHistoryState decoded=EnergyHistoryState.decode(frame(h).encode());
        assertEquals(1,decoded.consumption.evDrop,0);assertEquals(.14,decoded.consumption.cursor,0);
        EnergyConsumptionHistory restored=new EnergyConsumptionHistory();restored.restore(decoded.consumption);
        sample(restored,.2,70,40);assertEquals(.43,restored.points().get(0).electricity(43),.00001);
        assertTrue(Float.isNaN(restored.points().get(1).electricity(43)));
        sample(restored,.3,69,39);assertEquals(.43,restored.points().get(2).electricity(43),.00001);
    }
    @Test public void legacyVersionTwoLoadsWithoutFabricatingHundredMeterHistory()throws Exception {
        EnergyHistoryState s=new EnergyHistoryState();s.points=Collections.emptyList();s.distance=new double[]{100,30,10};
        s.tripBattery=new double[]{4,30};s.tripFuel=new double[]{3,30};
        // V2 header without points: 64 bytes followed immediately by its CRC.
        byte[] old=Arrays.copyOf(Base64.getDecoder().decode(s.encode()),72);ByteBuffer.wrap(old).putInt(2);
        CRC32 crc=new CRC32();crc.update(old,0,64);ByteBuffer.wrap(old).putLong(64,crc.getValue());
        EnergyHistoryState decoded=EnergyHistoryState.decode(Base64.getEncoder().encodeToString(old));
        assertEquals(100,decoded.distance[0],0);assertEquals(4,decoded.tripBattery[0],0);assertTrue(decoded.consumption.points.isEmpty());
    }
    @Test public void legacyQuarterKilometerBufferIsValidatedThenDiscardedWithoutLosingLevelHistory() throws Exception {
        EnergyHistoryState old=new EnergyHistoryState();old.distance=new double[]{10,10,10};
        old.tripBattery=new double[]{4,10};old.tripFuel=new double[]{2,10};
        old.points=Arrays.asList(new EnergyHistory.Point(9.9f,80,50,true,1,1,9.9,9.9),
                new EnergyHistory.Point(10,79,49,false,2,2,10,10));
        old.consumption.cursor=old.consumption.start=10;old.consumption.target=10.25;old.consumption.pending=true;
        old.consumption.evDrop=.1;
        for(int i=0;i<40;i++)old.consumption.points.add(new EnergyConsumptionHistory.Point(i*.25,(i+1)*.25,.1,0,.1,0,0,i==0));
        for(int schema:new int[]{3,4}) {
            EnergyHistoryState saved=EnergyHistoryState.decode(version(old.encode(),schema));
            assertArrayEquals(old.distance,saved.distance,0);assertArrayEquals(old.tripBattery,saved.tripBattery,0);
            assertEquals(2,saved.points.size());assertEquals(2,saved.points.get(1).evDrop,0);
            assertTrue(saved.consumption.points.isEmpty());assertFalse(saved.consumption.pending);
            EnergyConsumptionHistory h=new EnergyConsumptionHistory();h.restore(saved.consumption);
            sample(h,10,70,40);sample(h,10.1,69,39);
            assertEquals(1,h.points().size());assertEquals(.43,h.points().get(0).electricity(43),.00001);
        }
        old.consumption.target=9;
        try{EnergyHistoryState.decode(version(old.encode(),4));fail();}catch(IOException expected){}
    }
    @Test public void fiftyMeterSourceUpdatesWaitForARealHundredMeterInterval() {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.05,79.95f,49.5f);
        assertTrue(h.points().isEmpty());sample(h,.05,79.95f,49.5f);assertTrue(h.points().isEmpty());
        sample(h,.1,79.9f,49);assertEquals(1,h.points().size());
        assertEquals(0,h.points().get(0).start,0);assertEquals(.1,h.points().get(0).end,0);
        assertEquals(.043,h.points().get(0).electricity(43),.00001);
    }
    @Test public void versionFiveMergesActualFiftyMeterBoundsWithoutLosingLevelsOrAverages() throws Exception {
        EnergyHistoryState old=new EnergyHistoryState();old.distance=new double[]{2.5,2.5,50};
        old.tripBattery=new double[]{2,2.5};old.tripFuel=new double[]{2,2.5};
        old.points=Arrays.asList(new EnergyHistory.Point(0,80,50,true),
                new EnergyHistory.Point(2.5f,78,48,false,2,2,2.5,2.5));
        old.consumption.cursor=old.consumption.start=2.5;old.consumption.target=2.55;old.consumption.pending=true;
        for(int i=0;i<50;i++)old.consumption.points.add(new EnergyConsumptionHistory.Point(i*.05,(i+1)*.05,.1,0,.2,0,0,i==0));
        EnergyHistoryState migrated=EnergyHistoryState.decode(version(old.encode(),5));
        assertArrayEquals(old.distance,migrated.distance,0);assertArrayEquals(old.tripBattery,migrated.tripBattery,0);
        assertEquals(2,migrated.points.size());assertEquals(2,migrated.points.get(1).evDrop,0);
        assertEquals(25,migrated.consumption.points.size());assertFalse(migrated.consumption.pending);
        for(int i=0;i<25;i++) {
            EnergyConsumptionHistory.Point p=migrated.consumption.points.get(i);
            assertEquals(i*.1,p.start,.00001);assertEquals((i+1)*.1,p.end,.00001);
            assertEquals(.086,p.electricity(43),.00001);assertEquals(.224,p.fuel(56),.00001);
        }
        assertEquals(25,EnergyHistoryState.decode(migrated.encode()).consumption.points.size());
    }
    @Test public void versionFiveDoesNotBridgeGapsOrHideAnInvalidChannel() throws Exception {
        EnergyHistoryState old=new EnergyHistoryState();old.distance=new double[]{.2,.2,4};old.points=Collections.emptyList();
        old.consumption.cursor=old.consumption.start=.2;old.consumption.target=.25;old.consumption.pending=true;
        old.consumption.points.add(new EnergyConsumptionHistory.Point(0,.05,.1,0,.2,0,0,true));
        old.consumption.points.add(new EnergyConsumptionHistory.Point(.1,.15,.1,0,.2,0,1,true));
        old.consumption.points.add(new EnergyConsumptionHistory.Point(.15,.2,.1,0,.2,0,0,false));
        EnergyHistoryState migrated=EnergyHistoryState.decode(version(old.encode(),5));
        assertEquals(1,migrated.consumption.points.size());EnergyConsumptionHistory.Point p=migrated.consumption.points.get(0);
        assertEquals(.1,p.start,0);assertEquals(.2,p.end,0);assertTrue(p.gap);
        assertTrue(Float.isNaN(p.electricity(43)));assertEquals(.224,p.fuel(56),.00001);
    }
    @Test public void corruptAndSemanticallyInvalidConsumptionSnapshotsAreRejected()throws Exception {
        EnergyConsumptionHistory h=new EnergyConsumptionHistory();sample(h,0,80,50);sample(h,.1,79,49);
        EnergyHistoryState s=frame(h);s.consumption.evRise=Double.NaN;
        try{EnergyHistoryState.decode(s.encode());fail();}catch(IOException expected){}
        s=frame(h);s.consumption.target=.01;
        try{EnergyHistoryState.decode(s.encode());fail();}catch(IOException expected){}
        byte[] bytes=Base64.getDecoder().decode(frame(h).encode());bytes[bytes.length-12]^=1;
        try{EnergyHistoryState.decode(Base64.getEncoder().encodeToString(bytes));fail();}catch(IOException expected){}
    }
}
