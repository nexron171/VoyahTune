package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnergyTelemetryTest {
    private static int f(float v) { return Float.floatToIntBits(v); }

    @Test public void levelsUseSocFloatAndFuelPercentageNotCapacityOrConsumption() {
        assertEquals(48.1f, EnergyTelemetrySample.decode(EnergyTelemetrySample.SOC,
                new int[]{0x42406666}).values[0], .0001f);
        int[] fuel = {52, 0, f(30), 0, f(9), f(5), f(6)};
        assertEquals(30, EnergyTelemetrySample.decode(EnergyTelemetrySample.FUEL, fuel).values[0], 0);
        assertArrayEquals(new int[]{71,80,70,1,9}, EnergyTelemetrySample.TRANSACTIONS);
    }

    @Test public void preservesElectricAverageAndTripCounterWithoutUsingOemFuelAverage() {
        int[] trip=new int[20]; trip[0]=f(42.3f);trip[1]=f(18.7f);trip[2]=55;trip[3]=f(1.9f);trip[4]=28;
        EnergyTelemetrySample decoded=EnergyTelemetrySample.decode(EnergyTelemetrySample.TRIP,trip);
        assertEquals(42.3f,decoded.values[0],0);assertEquals(18.7f,decoded.values[1],0);
        assertTrue(Float.isNaN(decoded.values[2]));assertEquals(28,decoded.tripCounter);
        int[] tires=new int[11];tires[7]=f(2.6f);tires[8]=f(2.7f);tires[9]=f(2.8f);tires[10]=f(2.9f);
        assertArrayEquals(new float[]{2.6f,2.7f,2.8f,2.9f},EnergyTelemetrySample.decode(2,tires).values,0);
        int[] odo=new int[10];odo[0]=350;odo[1]=15278;odo[3]=4699;odo[6]=f(15278);
        assertEquals(15278,EnergyTelemetrySample.decode(3,odo).values[0],0);
    }

    @Test public void invalidLevelsNeverBecomeZeroAndEmptyOrFullRemainValid() {
        for(float bad:new float[]{-1,-1000,-9999,101,102.3f,Float.MIN_VALUE,Float.NaN,Float.POSITIVE_INFINITY}) {
            assertTrue(Float.isNaN(EnergyTelemetrySample.decode(0,new int[]{f(bad)}).values[0]));
            int[] fuel=new int[7];fuel[2]=f(bad);
            assertTrue(Float.isNaN(EnergyTelemetrySample.decode(4,fuel).values[0]));
        }
        for(int kind=0;kind<EnergyTelemetrySample.COUNT;kind++)
            for(float v:EnergyTelemetrySample.decode(kind,new int[0]).values) assertTrue(Float.isNaN(v));
        for(float valid:new float[]{0,100}) {
            assertEquals(valid,EnergyTelemetrySample.decode(0,new int[]{f(valid)}).values[0],0);
            int[] fuel=new int[7];fuel[2]=f(valid);
            assertEquals(valid,EnergyTelemetrySample.decode(4,fuel).values[0],0);
        }
    }

    @Test public void historyUsesHundredMeterStepsIncludingConstantLevelsAndCharging() {
        EnergyHistory h=new EnergyHistory();
        assertTrue(h.sample(0,48,30,1000,1000,1000,1000));
        assertFalse(h.sample(.04f,48,30,1500,1500,1500,1500));
        assertTrue(h.sample(.1f,48,30,2000,2000,2000,2000));
        assertFalse(h.points().get(1).gap);
        assertTrue(h.sample(.1f,49,30,3000,3000,3000,3000)); // parked charging updates endpoint
        assertEquals(2,h.points().size());assertEquals(49,h.points().get(1).ev,0);
        h.sample(.2f,48.9f,30,4000,4000,4000,4000);
        assertEquals(3,h.points().size());
    }

    @Test public void staleGroupsAreIndependentAndDisconnectOrDistanceJumpBreaksLine() {
        EnergyHistory h=new EnergyHistory();
        h.sample(0,48,30,0,0,0,0);
        h.sample(.1f,48,30,20000,20000,20000,0);
        assertTrue(Float.isNaN(h.points().get(1).fuel));assertEquals(48,h.points().get(1).ev,0);
        assertFalse(h.sample(.2f,48,30,40000,40000,20000,0));
        h.sample(.2f,47,29,41000,41000,41000,41000);
        assertTrue(h.points().get(2).gap);
        h.breakSegment();h.sample(.3f,47,29,42000,42000,42000,42000);
        assertTrue(h.points().get(3).gap);
        h.sample(1,47,29,43000,43000,43000,43000);assertTrue(h.points().get(4).gap);
        h.sample(0,47,29,44000,44000,44000,44000);
        assertEquals(1,h.points().size());assertTrue(h.points().get(0).gap);
    }

    @Test public void historyIsBoundedAndRestartCannotBridgeStoredPoints() {
        EnergyHistory h=new EnergyHistory();
        for(int i=0;i<1000;i++)h.sample(i*.1f,48,30,i*100L,i*100L,i*100L,i*100L);
        assertTrue(h.points().size()<=301);
        assertTrue(h.points().get(h.points().size()-1).km-h.points().get(0).km<=30);
        EnergyHistory restored=new EnergyHistory();restored.restore(h.points());
        restored.sample(100,48,30,100000,100000,100000,100000);
        assertTrue(restored.points().get(restored.points().size()-1).gap);
    }

    @Test public void continuousSignalsAtLowSpeedDoNotCreateArtificialGaps() {
        EnergyHistory h=new EnergyHistory();h.sample(0,48,30,0,0,0,0);
        for(int i=1;i<30;i++)h.sample(0,48,30,i*1000L,i*1000L,i*1000L,i*1000L);
        h.sample(.1f,48,30,30000,30000,30000,30000);
        assertEquals(2,h.points().size());assertFalse(h.points().get(1).gap);
    }

    @Test public void routerKeepsIndependentSocAndFuelAndRefreshesUnchangedLevels() {
        CanBusEventRouter r=new CanBusEventRouter();java.util.List<CanBusEvent> out=new java.util.ArrayList<>();
        r.subscribe(CanBusEventRouter.INTEREST_ENERGY_TELEMETRY,null,Runnable::run,out::add);
        EnergyTelemetrySample s=new EnergyTelemetrySample(0,48);
        r.dispatch(CanBusEvent.telemetry(CanBusEvent.Origin.LIVE,1,1,1000,s));
        r.dispatch(CanBusEvent.telemetry(CanBusEvent.Origin.LIVE,1,2,2000,s));
        r.dispatch(CanBusEvent.telemetry(CanBusEvent.Origin.LIVE,1,3,3000,new EnergyTelemetrySample(4,30)));
        assertEquals(3,out.size());
        r.invalidateThrough(1);
        r.dispatch(CanBusEvent.telemetry(CanBusEvent.Origin.LIVE,1,4,4000,s));assertEquals(3,out.size());
    }
}
