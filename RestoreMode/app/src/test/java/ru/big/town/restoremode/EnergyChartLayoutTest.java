package ru.big.town.restoremode;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnergyChartLayoutTest {
    @Test public void narrowChartsOccupySeparateHalvesAndRemainTouchable() {
        for(int columns:new int[]{2,3}) {
            float width=EnergyWidgetLayout.pixelsWide(columns),height=EnergyWidgetLayout.pixelsHigh("energyWidget",5);
            EnergyChartLayout layout=new EnergyChartLayout(width,height,true,true);
            assertEquals(100,layout.bottom-layout.top,0);
            assertEquals(layout.bottom-layout.top,layout.shortBottom-layout.shortTop,0);
            assertTrue(layout.axis<layout.splitY);
            assertTrue(layout.shortTop>layout.splitY);
            assertTrue(layout.shortTop>height/2);
            assertTrue(layout.shortTop<layout.shortBottom);
            assertTrue(layout.shortAxis<height);
            assertTrue(layout.right-layout.left>150);
            assertTrue(layout.shortRight-layout.shortLeft>150);
            assertTrue(layout.containsHistory(150,215));
            assertFalse(layout.containsConsumption(150,215));
            assertTrue(layout.containsConsumption(150,450));
            assertFalse(layout.containsHistory(150,450));
            assertFalse(layout.containsHistory(150,layout.splitY));
            assertFalse(layout.containsConsumption(150,layout.splitY));
        }
    }
    @Test public void narrowWindowButtonsFitAboveReadingsAndDoNotSelectGraphs() {
        for(int columns:new int[]{2,3}) {
            float width=EnergyWidgetLayout.pixelsWide(columns);
            EnergyChartLayout layout=new EnergyChartLayout(width,627,true,true);
            for(int slot=0;slot<3;slot++) {
                float x=layout.buttonLeft+(slot+.5f)*layout.buttonWidth;
                assertEquals(slot,layout.windowSlot(x,57));
                assertFalse(layout.containsHistory(x,57));
                assertFalse(layout.containsConsumption(x,57));
            }
            assertEquals(-1,layout.windowSlot(150,215));
            assertEquals(-1,layout.windowSlot(150,450));
            assertEquals(-1,layout.windowSlot(0,57));
            assertEquals(-1,layout.windowSlot(width,57));
        }
    }
    @Test public void horizontalChartsKeepSideBySideTouchRegionsAndOldGeometry() {
        for(int columns=4;columns<=8;columns++) {
            EnergyChartLayout layout=new EnergyChartLayout(EnergyWidgetLayout.pixelsWide(columns),373,columns<=5,false);
            assertEquals(183,layout.top,0);assertEquals(253,layout.bottom,0);assertEquals(276,layout.axis,0);
            assertEquals(layout.top,layout.shortTop,0);assertEquals(layout.bottom,layout.shortBottom,0);
            float historyX=(layout.left+layout.right)/2,consumptionX=(layout.shortLeft+layout.shortRight)/2;
            assertTrue(layout.containsHistory(historyX,215));
            assertFalse(layout.containsConsumption(historyX,215));
            assertTrue(layout.containsConsumption(consumptionX,215));
            assertFalse(layout.containsHistory(consumptionX,215));
            assertEquals(0,layout.windowSlot(layout.buttonLeft+layout.buttonWidth/2,52));
        }
    }
}
