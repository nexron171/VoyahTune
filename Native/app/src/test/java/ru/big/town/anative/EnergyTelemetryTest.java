package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnergyTelemetryTest {
    private static int f(float v) { return Float.floatToIntBits(v); }
    @Test public void decodesFloatAndIntegerParcelPositionsWithoutUsingPercentages() {
        int[] words = new int[35];
        words[0]=f(85); words[32]=76; words[33]=f(-8); words[34]=f(2.5f);
        assertArrayEquals(new float[]{-8,2.5f},EnergyTelemetrySample.decode(0,words).values,0);
        assertEquals(76,EnergyTelemetrySample.decode(0,words).sourceIndex);
        int[] trip=new int[20]; trip[0]=f(42.3f);trip[1]=f(18.7f);trip[2]=55;trip[3]=f(1.9f);trip[4]=28;
        assertArrayEquals(new float[]{42.3f,18.7f,1.9f},EnergyTelemetrySample.decode(1,trip).values,0);
        int[] tires=new int[11];tires[7]=f(2.6f);tires[8]=f(2.7f);tires[9]=f(2.8f);tires[10]=f(2.9f);
        assertArrayEquals(new float[]{2.6f,2.7f,2.8f,2.9f},EnergyTelemetrySample.decode(2,tires).values,0);
        int[] odo=new int[10];odo[0]=350;odo[1]=15278;odo[3]=4699;odo[6]=f(15278);
        assertEquals(15278,EnergyTelemetrySample.decode(3,odo).values[0],0);
    }
    @Test public void unknownInvalidNonfiniteAndShortParcelsNeverBecomeZero() {
        for(float bad:new float[]{-1000,-9999,Float.MIN_VALUE,Float.NaN,Float.POSITIVE_INFINITY}) {
            int[] words=new int[35];words[33]=f(bad);words[34]=f(bad);
            for(float v:EnergyTelemetrySample.decode(0,words).values) assertTrue(Float.isNaN(v));
        }
        for(float v:EnergyTelemetrySample.decode(2,new int[10]).values) assertTrue(Float.isNaN(v));
        assertTrue(Float.isNaN(EnergyTelemetrySample.decode(3,new int[10]).values[0]));
        int[] zero=new int[35];assertEquals(0,EnergyTelemetrySample.decode(0,zero).values[0],0);
    }
    @Test public void historyUsesDistanceAndBreaksAcrossStaleInputReconnectAndRollback() {
        EnergyHistory h=new EnergyHistory();
        assertTrue(h.sample(0,12,0,1000,1000,1000));
        assertFalse(h.sample(0,15,0,1500,1500,1500));
        assertTrue(h.sample(.1f,15,0,2000,2000,2000));
        assertFalse(h.points().get(1).gap);
        assertFalse(h.sample(.2f,15,0,20000,20000,2000));
        assertTrue(h.sample(.2f,15,0,21000,21000,21000));
        assertTrue(h.points().get(2).gap);
        h.breakSegment(); h.sample(.3f,12,0,22000,22000,22000);
        assertTrue(h.points().get(3).gap);
        h.sample(0,10,0,23000,23000,23000);
        assertEquals(1,h.points().size());assertTrue(h.points().get(0).gap);
    }
    @Test public void historyIsBoundedAndRestartCannotBridgeStoredPoints() {
        EnergyHistory h=new EnergyHistory();
        for(int i=0;i<1000;i++)h.sample(i*.1f,10,0,i*100L,i*100L,i*100L);
        assertTrue(h.points().size()<=301);
        assertTrue(h.points().get(h.points().size()-1).km-h.points().get(0).km<=30);
        EnergyHistory restored=new EnergyHistory();restored.restore(h.points());
        restored.sample(100,11,0,100000,100000,100000);
        assertTrue(restored.points().get(restored.points().size()-1).gap);
    }
    @Test public void continuousSignalsAtLowSpeedDoNotCreateArtificialGaps() {
        EnergyHistory h=new EnergyHistory();
        h.sample(0,12,0,0,0,0);
        for(int i=1;i<30;i++)h.sample(0,12,0,i*1000L,i*1000L,i*1000L);
        h.sample(.1f,12,0,30000,30000,30000);
        assertEquals(2,h.points().size());assertFalse(h.points().get(1).gap);
    }
    @Test public void routerKeepsDifferentTelemetryKindsAndRefreshesIdenticalValues() {
        CanBusEventRouter r=new CanBusEventRouter();java.util.List<CanBusEvent> out=new java.util.ArrayList<>();
        r.subscribe(CanBusEventRouter.INTEREST_ENERGY_TELEMETRY,null,Runnable::run,out::add);
        EnergyTelemetrySample s=new EnergyTelemetrySample(0,0,0);
        r.dispatch(CanBusEvent.telemetry(CanBusEvent.Origin.LIVE,1,1,1000,s));
        r.dispatch(CanBusEvent.telemetry(CanBusEvent.Origin.LIVE,1,2,2000,s));
        r.dispatch(CanBusEvent.telemetry(CanBusEvent.Origin.LIVE,1,3,3000,new EnergyTelemetrySample(2,2.6f,2.6f,2.6f,2.7f)));
        assertEquals(3,out.size());
        r.invalidateThrough(1);
        r.dispatch(CanBusEvent.telemetry(CanBusEvent.Origin.LIVE,1,4,4000,s));assertEquals(3,out.size());
    }

    private static EnergyTelemetrySample instant(int index, float ev, float fuel) {
        int[] words = new int[35]; words[32]=index; words[33]=f(ev); words[34]=f(fuel);
        return EnergyTelemetrySample.decode(EnergyTelemetrySample.INSTANT,words);
    }

    @Test public void frozenOemGetterCannotCreateLiveZeroHistoryWhileDistanceIncreases() {
        EnergyInstantFreshness freshness = new EnergyInstantFreshness();
        EnergyHistory history = new EnergyHistory();
        EnergyTelemetrySample cached = instant(329,0,0); // observed on H97X in motion
        for(int i=0;i<20;i++) {
            long now=i*5000L;
            long measuredAt=freshness.observe(cached,now,false);
            assertEquals(-1,measuredAt);
            assertFalse(history.sample(i*.1f,0,0,now,now,measuredAt));
        }
        assertTrue(history.points().isEmpty());
    }

    @Test public void changedIndexOrValuesConfirmSamplesButRepeatedCacheCannotKeepThemFresh() {
        EnergyInstantFreshness freshness = new EnergyInstantFreshness();
        assertEquals(-1,freshness.observe(instant(329,0,0),1000,false));
        assertEquals(2000,freshness.observe(instant(330,0,0),2000,false));
        assertEquals(2000,freshness.observe(instant(330,0,0),50000,false));
        assertEquals(2000,freshness.observe(instant(330,0,0),51000,true));
        assertEquals(52000,freshness.observe(instant(330,-8,2.5f),52000,false));
        assertEquals(53000,freshness.observe(instant(1,-8,2.5f),53000,true));
        freshness.reset();
        assertEquals(-1,freshness.observe(instant(1,-8,2.5f),54000,false));
        assertEquals(-1,freshness.observe(EnergyTelemetrySample.unavailable(0),55000,false));
        assertEquals(-1,freshness.observe(instant(1,-8,2.5f),56000,false));
    }

    @Test public void liveFirstCallbackAndInvalidSampleIndicesAreHandledSeparately() {
        EnergyInstantFreshness freshness = new EnergyInstantFreshness();
        assertEquals(1000,freshness.observe(instant(329,0,0),1000,true));
        for(int invalid:new int[]{0,-1000,-9999,511})
            assertEquals(-1,freshness.observe(instant(invalid,0,0),2000,true));
        assertEquals(-1,freshness.observe(instant(330,Float.NaN,Float.NaN),3000,true));
        assertEquals(4000,freshness.observe(instant(330,12,Float.NaN),4000,true));
    }

    @Test public void liveTripInvalidFuelRemainsUnavailableAlongsideValidElectricAverage() {
        int[] trip = new int[20];
        trip[0]=0x3f800000; trip[1]=0x41c00000; trip[2]=7;
        trip[3]=0xc61c3c00; trip[4]=7; // OEM: 1.0 km, 24.0 kWh/100km, INVALID fuel
        float[] values=EnergyTelemetrySample.decode(EnergyTelemetrySample.TRIP,trip).values;
        assertEquals(1,values[0],0); assertEquals(24,values[1],0);
        assertTrue(Float.isNaN(values[2]));
    }
}
