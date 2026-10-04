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
            assertEquals(EnergyWidgetLayout.minHeight(id),TileSizeStore.height(prefs(saved),id,1));
            TileSizeStore.setWidth(prefs,id,12);TileSizeStore.setHeight(prefs,id,5);
            assertEquals(EnergyWidgetLayout.maxWidth(id),TileSizeStore.width(prefs(saved),id,1));
            assertEquals(EnergyWidgetLayout.maxHeight(id),TileSizeStore.height(prefs(saved),id,1));
        }
        assertEquals(286,EnergyWidgetLayout.pixelsWide(2));assertEquals(580,EnergyWidgetLayout.pixelsWide(4));
        assertEquals(1168,EnergyWidgetLayout.pixelsWide(8));assertEquals(373,EnergyWidgetLayout.pixelsHigh("tirePressureWidget",3));
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
