package ru.big.town.restoremode;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnergyConsumptionChartTest {
    private EnergyConsumptionChart chart(float[] ev,float[] fuel) {
        double[] starts=new double[ev.length],ends=new double[ev.length];
        for(int i=0;i<ev.length;i++){starts[i]=i*.1;ends[i]=(i+1)*.1;}
        return new EnergyConsumptionChart(ev.length*.1,starts,ends,ev,fuel,null);
    }
    @Test public void scalesExpandAndShrinkWithVisiblePeaks() {
        EnergyConsumptionChart small=chart(new float[]{.043f,-.012f},new float[]{.056f,0});
        EnergyConsumptionChart large=chart(new float[]{.43f,-.43f},new float[]{.56f,0});
        assertTrue(large.electricMax>small.electricMax);assertTrue(large.electricMin<small.electricMin);assertTrue(large.fuelMax>small.fuelMax);
        EnergyConsumptionChart again=chart(new float[]{.043f,-.012f},new float[]{.056f,0});
        assertEquals(small.electricMax,again.electricMax,0);assertEquals(small.fuelMax,again.fuelMax,0);
    }
    @Test public void zerosAndMissingChannelsHaveNoInventedRangeOrNanCoordinates() {
        EnergyConsumptionChart c=chart(new float[]{0,Float.NaN},new float[]{0,Float.NaN});
        assertEquals(0,c.electricMax,0);assertEquals(0,c.electricMin,0);assertEquals(0,c.fuelMax,0);
        assertEquals(1,c.zero(),0);assertEquals(1,c.electricPosition(0),0);assertEquals(1,c.fuelPosition(0),0);
        assertTrue(Float.isNaN(c.battery[1]));assertTrue(Float.isNaN(c.fuel[1]));
    }
    @Test public void positiveOnlyAndRegenerationOnlyKeepFuelVisibleAndShareZero() {
        EnergyConsumptionChart c=chart(new float[]{.043f},new float[]{.56f});
        assertEquals(0,c.electricMin,0);assertEquals(1,c.zero(),0);
        c=chart(new float[]{-.043f},new float[]{.56f});
        assertEquals(0,c.electricMax,0);assertEquals(.5,c.zero(),0);
        assertTrue(c.electricPosition(-.043)>c.zero());assertTrue(c.fuelPosition(.56)<c.zero());
        assertEquals(c.electricPosition(0),c.fuelPosition(0),0);
        c=chart(new float[]{-.043f},new float[]{0});assertEquals(0,c.zero(),0);assertTrue(c.electricPosition(-.043)>0);
    }
    @Test public void currentCursorAnchorsTwoPointFiveKmAndDropsPeaksOutsideWindow() {
        EnergyConsumptionChart c=new EnergyConsumptionChart(3,new double[]{0,.5,2.9},new double[]{.1,.6,3},
                new float[]{30,.043f,-.043f},new float[]{20,.56f,0},null);
        assertEquals(2,c.end.length);assertEquals(.1,c.position[0],.0001);assertEquals(2.5,c.position[1],0);
        assertTrue(c.electricMax<1);assertTrue(c.fuelMax<1);assertTrue(c.gaps[1]);
        assertEquals(0,c.nearest(0));assertEquals(1,c.nearest(1));
    }
    @Test public void hundredMeterPointsFillWindowAndSelectionReachesItsActualEnds() {
        double[] starts=new double[26],ends=new double[26];float[] ev=new float[26],fuel=new float[26];
        for(int i=0;i<26;i++){starts[i]=i*.1;ends[i]=(i+1)*.1;ev[i]=i==0?30:.043f;fuel[i]=.056f;}
        EnergyConsumptionChart c=new EnergyConsumptionChart(2.6,starts,ends,ev,fuel,null);
        assertEquals(25,c.end.length);assertEquals(.1,c.start[0],.00001);
        assertEquals(.1,c.position[0],.0001);assertEquals(2.5,c.position[24],.0001);
        assertTrue(c.electricMax<1);assertEquals(0,c.nearest(0));assertEquals(24,c.nearest(1));
    }
    @Test public void malformedIntervalsAndMissingArraysCannotMakePlausibleValues() {
        EnergyConsumptionChart c=new EnergyConsumptionChart(.3,new double[]{0,.1,.2},new double[]{.1,.09,.3},
                new float[]{.1f,.1f,Float.POSITIVE_INFINITY},new float[]{.2f,.2f,-1},null);
        assertEquals(2,c.end.length);assertTrue(c.gaps[1]);assertTrue(Float.isNaN(c.battery[1]));assertTrue(Float.isNaN(c.fuel[1]));
        c=new EnergyConsumptionChart(1,null,null,null,null,null);assertEquals(0,c.end.length);assertEquals(-1,c.nearest(.5f));
        c=new EnergyConsumptionChart(.2,new double[]{0,.1},new double[]{.1,.2},new float[]{0},new float[]{0},null);assertEquals(1,c.end.length);
    }
    @Test public void scalesAndPositionsStayFiniteForTinyAndLargeQuantities() {
        for(float value:new float[]{.000001f,.043f,.56f,1000,Float.MAX_VALUE/2}) {
            EnergyConsumptionChart c=chart(new float[]{value,-value},new float[]{value,0});
            assertTrue(c.electricMax>=value);assertTrue(c.electricMin<=-value);assertTrue(c.fuelMax>=value);
            for(double y:new double[]{c.electricPosition(value),c.electricPosition(-value),c.fuelPosition(value)})assertTrue(Double.isFinite(y)&&y>=0&&y<=1);
        }
    }

    @Test public void subHundredMeterIntervalsAreNotDisplayedAsCompletedMeasurements() {
        EnergyConsumptionChart c=new EnergyConsumptionChart(.1,new double[]{0,.05},new double[]{.05,.1},
                new float[]{.01f,.01f},new float[]{.02f,.02f},null);
        assertEquals(0,c.end.length);assertEquals(-1,c.nearest(.5f));
    }
}
