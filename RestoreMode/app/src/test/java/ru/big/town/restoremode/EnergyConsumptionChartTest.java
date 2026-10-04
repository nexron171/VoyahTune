package ru.big.town.restoremode;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnergyConsumptionChartTest {
    private EnergyConsumptionChart chart(float[] ev,float[] fuel) {
        double[] starts=new double[ev.length],ends=new double[ev.length];
        for(int i=0;i<ev.length;i++){starts[i]=i*.25;ends[i]=(i+1)*.25;}
        return new EnergyConsumptionChart(ev.length*.25,starts,ends,ev,fuel,null);
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
    @Test public void currentCursorAnchorsTenKmAndDropsPeaksOutsideWindow() {
        EnergyConsumptionChart c=new EnergyConsumptionChart(12,new double[]{0,2,11.75},new double[]{.25,2.25,12},
                new float[]{30,.043f,-.043f},new float[]{20,.56f,0},null);
        assertEquals(2,c.end.length);assertEquals(.25,c.position[0],.0001);assertEquals(10,c.position[1],0);
        assertTrue(c.electricMax<1);assertTrue(c.fuelMax<1);assertTrue(c.gaps[1]);
        assertEquals(0,c.nearest(0));assertEquals(1,c.nearest(1));
    }
    @Test public void malformedIntervalsAndMissingArraysCannotMakePlausibleValues() {
        EnergyConsumptionChart c=new EnergyConsumptionChart(1,new double[]{0,.25,.5},new double[]{.25,.2,.75},
                new float[]{.1f,.1f,Float.POSITIVE_INFINITY},new float[]{.2f,.2f,-1},null);
        assertEquals(2,c.end.length);assertTrue(c.gaps[1]);assertTrue(Float.isNaN(c.battery[1]));assertTrue(Float.isNaN(c.fuel[1]));
        c=new EnergyConsumptionChart(1,null,null,null,null,null);assertEquals(0,c.end.length);assertEquals(-1,c.nearest(.5f));
        c=new EnergyConsumptionChart(1,new double[]{0,.25},new double[]{.25,.5},new float[]{0},new float[]{0},null);assertEquals(1,c.end.length);
    }
    @Test public void scalesAndPositionsStayFiniteForTinyAndLargeQuantities() {
        for(float value:new float[]{.000001f,.043f,.56f,1000,Float.MAX_VALUE/2}) {
            EnergyConsumptionChart c=chart(new float[]{value,-value},new float[]{value,0});
            assertTrue(c.electricMax>=value);assertTrue(c.electricMin<=-value);assertTrue(c.fuelMax>=value);
            for(double y:new double[]{c.electricPosition(value),c.electricPosition(-value),c.fuelPosition(value)})assertTrue(Double.isFinite(y)&&y>=0&&y<=1);
        }
    }
}
