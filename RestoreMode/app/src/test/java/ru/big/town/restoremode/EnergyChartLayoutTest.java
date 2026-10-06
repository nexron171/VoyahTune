package ru.big.town.restoremode;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnergyChartLayoutTest {
    @Test public void levelsFillTheAvailableWidthAndHeightInAllApprovedSizes() {
        for(int[] size:new int[][]{{3,4},{3,5},{4,5},{5,5},{6,5},{7,4},{8,4}}) {
            float width=EnergyWidgetLayout.pixelsWide(size[0]),height=EnergyWidgetLayout.pixelsHigh("energyWidget",size[1]);
            EnergyChartLayout g=new EnergyChartLayout(width,height,size[0]==3);
            assertTrue(g.remainingValue<g.levelsTitle);assertTrue(g.top<g.bottom);
            assertEquals(width-g.left,g.right,0);assertEquals(g.axis-24,g.bottom,0);
            assertEquals(height-18,g.periodNote,0);
            assertTrue(g.axis<g.periodLabel);assertTrue(g.periodLabel<g.periodUnits);
            assertTrue(g.periodUnits<g.periodValue);assertTrue(g.periodValue<g.periodNote);
            assertTrue(g.containsHistory((g.left+g.right)/2,(g.top+g.bottom)/2));
            assertFalse(g.containsHistory(width-g.pad,(g.top+g.bottom)/2));
            assertFalse(g.containsHistory(g.left,g.periodValue));
        }
    }
    @Test public void windowButtonsRemainTouchableWithoutSelectingThePlot() {
        for(int columns=3;columns<=8;columns++) {
            EnergyChartLayout g=new EnergyChartLayout(EnergyWidgetLayout.pixelsWide(columns),630,columns==3);
            for(int slot=0;slot<3;slot++) {
                float x=g.buttonLeft+(slot+.5f)*g.buttonWidth,y=(g.buttonTop+g.buttonBottom)/2;
                assertEquals(slot,g.windowSlot(x,y));assertFalse(g.containsHistory(x,y));
            }
            assertEquals(-1,g.windowSlot(g.buttonLeft,g.readingValue));
        }
    }
    @Test public void separateShortChartFitsItsFiveSizesAndOmitsVerticalLabelsOnlyInOneRow() {
        for(int[] size:new int[][]{{1,1},{2,1},{2,2},{3,1},{3,2}}) {
            int columns=size[0],rows=size[1];
            float width=EnergyWidgetLayout.pixelsWide(columns),height=EnergyWidgetLayout.pixelsHigh("energyConsumptionWidget",rows);
            EnergyConsumptionLayout g=new EnergyConsumptionLayout(width,height,columns,rows,1);
            assertEquals(rows==2,g.axes);assertTrue(g.title<g.top);
            assertTrue(g.top<g.bottom);assertTrue(g.bottom<=height-8);
            assertEquals(width-(rows==1?(columns==1?10.5f:10):56),g.right,0);
            assertTrue(g.contains((g.left+g.right)/2,(g.top+g.bottom)/2));
            assertFalse(g.contains(g.left,g.title));assertFalse(g.contains(g.left,height-8));
        }
    }
}
