package ru.big.town.restoremode;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import ru.big.town.common.EnergyWidgetSettings;
import static org.junit.Assert.*;

public class EnergyWidgetTest {
    private SharedPreferences prefs(Map<String,Object> values) {
        final Object[] editor=new Object[1];
        editor[0]=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(p,m,a)->{
            if(m.getName().startsWith("put")){values.put((String)a[0],a[1]);return editor[0];}return null;});
        return (SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("edit"))return editor[0];
            if(m.getName().startsWith("get")&&a.length==2)return values.getOrDefault((String)a[0],a[1]);return null;});
    }
    @Test public void approvedSizesClampLegacySettingsAndSurviveRecreation() {
        Map<String,Object> saved=new HashMap<>();SharedPreferences prefs=prefs(saved);
        for(String id:new String[]{"energyWidget","energyTripWidget","tirePressureWidget","odometerWidget"}) {
            TileSizeStore.setWidth(prefs,id,1);TileSizeStore.setHeight(prefs,id,1);
            assertEquals(EnergyWidgetLayout.minWidth(id),TileSizeStore.width(prefs(saved),id,1));
            assertEquals(EnergyWidgetLayout.minHeight(id,EnergyWidgetLayout.minWidth(id)),TileSizeStore.height(prefs(saved),id,1));
            TileSizeStore.setWidth(prefs,id,12);TileSizeStore.setHeight(prefs,id,5);
            assertEquals(EnergyWidgetLayout.maxWidth(id),TileSizeStore.width(prefs(saved),id,1));
            assertEquals(EnergyWidgetLayout.maxHeight(id,EnergyWidgetLayout.maxWidth(id)),TileSizeStore.height(prefs(saved),id,1));
        }
        assertEquals(286,EnergyWidgetLayout.pixelsWide(2));assertEquals(580,EnergyWidgetLayout.pixelsWide(4));
        assertEquals(1168,EnergyWidgetLayout.pixelsWide(8));assertEquals(373,EnergyWidgetLayout.pixelsHigh("tirePressureWidget",3));
    }
    @Test public void narrowEnergyWidthsForceFiveRowsAndResetWhenWidened() {
        Map<String,Object> saved=new HashMap<>();SharedPreferences prefs=prefs(saved);
        for(int columns:new int[]{2,3}) {
            TileSizeStore.setWidth(prefs,"energyWidget",columns);
            assertEquals(5,saved.get("tileHeight_energyWidget"));
            assertArrayEquals(new int[]{columns,5},TileSizeStore.dimensions(prefs(saved),"energyWidget",8,3));
            TileSizeStore.setHeight(prefs,"energyWidget",3);
            assertEquals(5,TileSizeStore.height(prefs(saved),"energyWidget",3));
            for(int wide=4;wide<=8;wide++) {
                TileSizeStore.setWidth(prefs,"energyWidget",wide);
                assertArrayEquals(new int[]{wide,3},TileSizeStore.dimensions(prefs(saved),"energyWidget",8,3));
                assertEquals(3,saved.get("tileHeight_energyWidget"));
            }
        }
        assertEquals(627,EnergyWidgetLayout.pixelsHigh("energyWidget",5));
    }
    @Test public void staleEnergyHeightIsNormalizedFromStoredWidth() {
        Map<String,Object> saved=new HashMap<>();
        saved.put("tileWidth_energyWidget",2);saved.put("tileHeight_energyWidget",3);
        assertArrayEquals(new int[]{2,5},TileSizeStore.dimensions(prefs(saved),"energyWidget",8,3));
        saved.put("tileWidth_energyWidget",3);saved.put("tileHeight_energyWidget",1);
        assertArrayEquals(new int[]{3,5},TileSizeStore.dimensions(prefs(saved),"energyWidget",8,3));
        saved.put("tileWidth_energyWidget",6);saved.put("tileHeight_energyWidget",5);
        assertArrayEquals(new int[]{6,3},TileSizeStore.dimensions(prefs(saved),"energyWidget",8,3));
        saved.remove("tileWidth_energyWidget");
        assertArrayEquals(new int[]{8,3},TileSizeStore.dimensions(prefs(saved),"energyWidget",8,3));
    }
    @Test public void narrowEnergyRulesDoNotChangeTireOrOdometerSizes() {
        Map<String,Object> saved=new HashMap<>();SharedPreferences prefs=prefs(saved);
        TileSizeStore.setWidth(prefs,"tirePressureWidget",2);
        TileSizeStore.setHeight(prefs,"tirePressureWidget",4);
        TileSizeStore.setWidth(prefs,"odometerWidget",2);
        assertArrayEquals(new int[]{2,4},TileSizeStore.dimensions(prefs(saved),"tirePressureWidget",4,4));
        assertArrayEquals(new int[]{2,1},TileSizeStore.dimensions(prefs(saved),"odometerWidget",4,1));
    }
    @Test public void narrowTripAllowsFourOrFiveRowsAndKeepsChoiceAcrossWidths() {
        Map<String,Object> saved=new HashMap<>();SharedPreferences prefs=prefs(saved);
        for(int columns:new int[]{2,3}) {
            TileSizeStore.setWidth(prefs,"energyTripWidget",columns);
            assertArrayEquals(new int[]{columns,4},TileSizeStore.dimensions(prefs(saved),"energyTripWidget",8,2));
            TileSizeStore.setHeight(prefs,"energyTripWidget",5);
            TileSizeStore.setWidth(prefs,"energyTripWidget",columns==2?3:2);
            assertArrayEquals(new int[]{columns==2?3:2,5},TileSizeStore.dimensions(prefs(saved),"energyTripWidget",8,2));
            TileSizeStore.setHeight(prefs,"energyTripWidget",3);
            assertEquals(4,TileSizeStore.height(prefs(saved),"energyTripWidget",2));
            TileSizeStore.setHeight(prefs,"energyTripWidget",8);
            assertEquals(5,TileSizeStore.height(prefs(saved),"energyTripWidget",2));
            TileSizeStore.setWidth(prefs,"energyTripWidget",6);
            assertArrayEquals(new int[]{6,2},TileSizeStore.dimensions(prefs(saved),"energyTripWidget",8,2));
        }
    }
    @Test public void storedTripHeightIsConstrainedByWidthWithoutLosingValidFiveRows() {
        Map<String,Object> saved=new HashMap<>();
        saved.put("tileWidth_energyTripWidget",2);saved.put("tileHeight_energyTripWidget",2);
        assertArrayEquals(new int[]{2,4},TileSizeStore.dimensions(prefs(saved),"energyTripWidget",8,2));
        saved.put("tileHeight_energyTripWidget",5);
        assertArrayEquals(new int[]{2,5},TileSizeStore.dimensions(prefs(saved),"energyTripWidget",8,2));
        saved.put("tileWidth_energyTripWidget",3);
        assertArrayEquals(new int[]{3,5},TileSizeStore.dimensions(prefs(saved),"energyTripWidget",8,2));
        saved.put("tileWidth_energyTripWidget",8);
        assertArrayEquals(new int[]{8,2},TileSizeStore.dimensions(prefs(saved),"energyTripWidget",8,2));
    }
    @Test public void tripDrawingHeightMatchesSelectedRows() {
        assertEquals(245,EnergyWidgetLayout.pixelsHigh("energyTripWidget",2));
        assertEquals(500,EnergyWidgetLayout.pixelsHigh("energyTripWidget",4));
        assertEquals(627,EnergyWidgetLayout.pixelsHigh("energyTripWidget",5));
        assertTrue(EnergyWidgetLayout.vertical("energyTripWidget",2));
        assertTrue(EnergyWidgetLayout.vertical("energyTripWidget",3));
        assertFalse(EnergyWidgetLayout.vertical("energyTripWidget",4));
    }
    @Test public void capacitiesDefaultsDecimalsAndInvalidStoredValues() {
        Map<String,Object> saved=new HashMap<>();
        assertEquals(43,EnergyWidgetPreferences.capacity(prefs(saved),EnergyWidgetSettings.BATTERY_KEY,43),0);
        saved.put(EnergyWidgetSettings.BATTERY_KEY,50.5f);
        assertEquals(50.5,EnergyWidgetPreferences.capacity(prefs(saved),EnergyWidgetSettings.BATTERY_KEY,43),0);
        saved.put(EnergyWidgetSettings.BATTERY_KEY,Float.NaN);
        assertEquals(43,EnergyWidgetPreferences.capacity(prefs(saved),EnergyWidgetSettings.BATTERY_KEY,43),0);
        assertEquals(50.5,EnergyWidgetSettings.parseCapacity("50,5"),0);
        for(String s:new String[]{"0","-43","NaN","","2.55"})assertTrue(Float.isNaN(EnergyWidgetSettings.parseCapacity(s)));
    }
    @Test public void scaleMigrationAndDefaultAreDefined() {
        assertEquals(25,EnergyWidgetSettings.window(5));assertEquals(75,EnergyWidgetSettings.window(15));
        assertEquals(150,EnergyWidgetSettings.window(30));assertEquals(75,EnergyWidgetSettings.window(-1));
        for(int w:new int[]{25,75,150})assertEquals(w,EnergyWidgetSettings.window(w));
    }
    @Test public void odometerAxisCountsBackwardsInWholeKilometersAtEveryScale() {
        for(int window:new int[]{25,75,150}) {
            EnergyChartAxis axis=new EnergyChartAxis(180,window,15417.6f);
            for(int i=0;i<=5;i++)assertEquals(15418-window*i/5d,axis.tick(i),.001);
            assertEquals(1,axis.position(180),.00001);
            assertEquals(0,axis.position(180-window),.00001);
            assertEquals(15418-window/2d,axis.odometerAt(axis.distanceAt(.5f)),.001);
        }
    }
    @Test public void shortHistoryStaysOnRightWithoutInventingOdometer() {
        EnergyChartAxis axis=new EnergyChartAxis(5,25,15417);
        assertEquals(.8,axis.position(0),.00001);
        assertEquals(1,axis.position(5),.00001);
        assertEquals(15412,axis.odometerAt(0),.001);
        assertEquals(-20,axis.distanceAt(0),.001);
        for(float unavailable:new float[]{Float.NaN,Float.POSITIVE_INFINITY,0,-1})
            assertTrue(Double.isNaN(new EnergyChartAxis(5,25,unavailable).tick(0)));
        assertTrue(Double.isNaN(new EnergyChartAxis(0,25,10).tick(5)));
    }
    @Test public void selectedWindowUsesCumulativeDropsAndActualObservedDistance() {
        float[] x={0,50,100,150};double[] drop={0,4,6,10},distance={0,50,100,150};
        EnergyPeriodEstimate all=EnergyPeriodEstimate.calculate(150,x,drop,drop,distance,distance,43,56);
        assertEquals(430f/150,all.battery,.001);assertEquals(560f/150,all.fuel,.001);
        EnergyPeriodEstimate partial=EnergyPeriodEstimate.calculate(75,x,drop,drop,distance,distance,43,56);
        assertEquals(50,partial.batteryKm,0);assertEquals(4*43f/50,partial.battery,.001);
        // Refill does not subtract an earlier decrease; coalescing a parked point preserves counters.
        double[] withParkedDrop={0,4,6,11};
        assertEquals(5*43f/50,EnergyPeriodEstimate.calculate(75,x,withParkedDrop,drop,distance,distance,43,56).battery,.001);
        double[] observed={0,40,80,100};
        assertEquals(4*56f/20,EnergyPeriodEstimate.calculate(75,x,drop,drop,observed,observed,43,56).fuel,.001);
    }
    @Test public void unavailableShortAndMalformedHistoryDoesNotBecomeZero() {
        float[] x={0,.5f};double[] drops={0,1},km={0,.5};
        assertTrue(Float.isNaN(EnergyPeriodEstimate.calculate(25,x,drops,drops,km,km,43,56).battery));
        assertTrue(Float.isNaN(EnergyPeriodEstimate.calculate(25,x,null,drops,km,km,43,56).fuel));
        assertTrue(Float.isNaN(EnergyPeriodEstimate.calculate(25,new float[0],drops,drops,km,km,43,56).fuel));
    }
    @Test public void consumptionInsideFirstBucketIsNotErasedByParkedUpdates() {
        float[] x={0,10};double[] drops={1,2},km={0,10},startDrops={0,2};
        EnergyPeriodEstimate result=EnergyPeriodEstimate.calculate(25,x,drops,drops,km,km,43,56,startDrops,startDrops,km,km);
        assertEquals(8.6,result.battery,.001);assertEquals(11.2,result.fuel,.001);
    }
}
