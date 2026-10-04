package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Base64;

public class EnergyHistoryStateTest {
    private EnergyHistoryState state() {
        EnergyHistory h=new EnergyHistory();
        for(int i=0;i<=1600;i++)h.sample(i/10d,80-i/100f,50,i*1000L,i*1000L,i*1000L,i*1000L);
        EnergyHistoryState s=new EnergyHistoryState();s.points=h.points();s.distance=new double[]{160,160,1600};
        s.tripBattery=new double[]{16,160};s.tripFuel=new double[]{0,160};return s;
    }
    @Test public void persistsFull150KmAndIndependentLongerTripTotals() throws Exception {
        EnergyHistoryState s=state(),r=EnergyHistoryState.decode(s.encode());
        assertEquals(1501,r.points.size());assertEquals(10,r.points.get(0).km,0);
        assertEquals(160,r.points.get(1500).km,0);assertArrayEquals(s.distance,r.distance,0);
        assertArrayEquals(s.tripBattery,r.tripBattery,0);
        assertEquals(s.points.get(1500).evDrop,r.points.get(1500).evDrop,0);
    }
    @Test public void corruptionTruncationAndFutureFormatsAreRejected() throws Exception {
        byte[] bytes=Base64.getDecoder().decode(state().encode());bytes[30]^=1;
        for(String bad:new String[]{"garbage",Base64.getEncoder().encodeToString(bytes),state().encode().substring(0,100)}) {
            try{EnergyHistoryState.decode(bad);fail();}catch(IOException expected){}
        }
    }
    @Test public void parkedRefillCannotEraseEarlierConsumptionFromSavedEndpoint() throws Exception {
        EnergyHistory h=new EnergyHistory();h.sample(0,80,50,0,0,0,0);
        h.sample(.1,79,49,1000,1000,1000,1000);h.sample(.1,90,80,2000,2000,2000,2000);
        h.sample(.1,89,79,3000,3000,3000,3000);
        EnergyHistory.Point p=h.points().get(1);assertEquals(2,p.evDrop,0);assertEquals(2,p.fuelDrop,0);
        EnergyHistoryState s=state();s.points=h.points();s.distance=new double[]{.1,.1,3};
        EnergyHistory restored=new EnergyHistory();restored.restore(EnergyHistoryState.decode(s.encode()).points);
        restored.sample(.2,88,78,4000,4000,4000,4000);
        assertEquals(2,restored.points().get(2).evDrop,0); // break on restart, no bridging
        restored.sample(.3,87,77,5000,5000,5000,5000);
        assertEquals(3,restored.points().get(3).evDrop,0);
    }
    @Test public void semanticallyInvalidSnapshotIsRejectedEvenWithValidChecksum() throws Exception {
        EnergyHistoryState s=state();s.distance[0]=0;
        try{EnergyHistoryState.decode(s.encode());fail();}catch(IOException expected){}
        s=state();s.tripBattery[0]=-1;
        try{EnergyHistoryState.decode(s.encode());fail();}catch(IOException expected){}
    }
    @Test public void legacyLevelsRestoreWithoutFabricatingOldConsumption() {
        EnergyHistory h=new EnergyHistory();
        h.restore(java.util.Arrays.asList(new EnergyHistory.Point(30,80,40,true),new EnergyHistory.Point(30.1f,79,39,false)));
        h.sample(30.2,78,38,1000,1000,1000,1000);
        assertEquals(3,h.points().size());assertEquals(0,h.points().get(2).evDrop,0);
        h.sample(30.3,77,37,2000,2000,2000,2000);
        assertEquals(1,h.points().get(3).evDrop,0);
    }
    @Test public void firstBucketKeepsItsBaselineAcrossParkedDropsAndRestoration() throws Exception {
        EnergyHistory h=new EnergyHistory();h.sample(0,80,50,0,0,0,0);
        h.sample(0,79,49,1000,1000,1000,1000);h.sample(0,90,80,2000,2000,2000,2000);
        assertEquals(0,h.points().get(0).startEvDrop,0);assertEquals(1,h.points().get(0).evDrop,0);
        EnergyHistoryState s=state();s.points=h.points();s.distance=new double[]{0,0,3};
        EnergyHistory restored=new EnergyHistory();restored.restore(EnergyHistoryState.decode(s.encode()).points);
        assertEquals(0,restored.points().get(0).startEvDrop,0);assertEquals(1,restored.points().get(0).evDrop,0);
    }
}
