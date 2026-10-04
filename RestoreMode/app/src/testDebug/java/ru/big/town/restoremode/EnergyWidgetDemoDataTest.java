package ru.big.town.restoremode;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnergyWidgetDemoDataTest {
    @Test public void fixtureMatchesPrototypeAndUsesRealPeriodCalculation() {
        EnergyWidgetDemoData data=new EnergyWidgetDemoData();
        assertEquals(1501,data.x.length);assertEquals(30,data.x[0],0);assertEquals(180,data.x[1500],0);
        assertEquals(47.3,data.battery[1500],.001);assertEquals(30,data.fuel[1500],0);
        assertEquals(10.1767,data.tripBattery(43),.001);assertEquals(7.4667,data.tripFuel(56),.001);
        EnergyPeriodEstimate all=EnergyPeriodEstimate.calculate(150,data.x,data.batteryDrop,data.fuelDrop,
                data.observed,data.observed,43,56);
        assertEquals(38.1*43/150,all.battery,.001);assertEquals(7.4667,all.fuel,.001);
        assertTrue(data.fuel[1320]>data.fuel[1319]); // refill at 162 km
        assertEquals(data.fuelDrop[1319],data.fuelDrop[1320],0);
        assertEquals(data.tripBattery(43)*2,data.tripBattery(86),.001);
    }
    @Test public void shortFixtureMatchesPrototypeWithRecoveryAndAbsoluteAmounts() {
        EnergyWidgetDemoData data=new EnergyWidgetDemoData();
        float[] ev=data.consumptionBattery(43),fuel=data.consumptionFuel(56);
        assertEquals(40,ev.length);assertEquals(170,data.consumptionStart[0],0);
        assertEquals(170.25,data.consumptionEnd[0],0);assertEquals(180,data.consumptionEnd[39],0);
        boolean recovery=false;
        for(int i=0;i<40;i++) {
            assertEquals(.25,data.consumptionEnd[i]-data.consumptionStart[i],0);
            assertTrue(fuel[i]>=0);recovery|=ev[i]<0;
            assertEquals(ev[i]*2,data.consumptionBattery(86)[i],.000001);
            assertEquals(fuel[i]*2,data.consumptionFuel(112)[i],.000001);
        }
        assertTrue(recovery);assertEquals(.043,ev[39],.000001);assertEquals(.56,fuel[39],.000001);
        EnergyConsumptionChart chart=new EnergyConsumptionChart(180,data.consumptionStart,data.consumptionEnd,ev,fuel,data.consumptionGaps);
        assertEquals(40,chart.end.length);assertEquals(.05,chart.electricMax,.000001);
        assertEquals(-.05,chart.electricMin,.000001);assertEquals(.6,chart.fuelMax,.000001);
    }
}
