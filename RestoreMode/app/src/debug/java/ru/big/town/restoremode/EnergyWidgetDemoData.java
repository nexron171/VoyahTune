package ru.big.town.restoremode;

import android.os.Bundle;
import android.os.SystemClock;
import ru.big.town.common.EnergyWidgetProtocol;
import ru.big.town.common.EnergyWidgetSettings;

/** TEMPORARY: the HTML prototype's synthetic 180 km route, retaining its last 150 km. */
final class EnergyWidgetDemoData {
    static final long TRIP_MS=(42*60+18)*1000L;
    static final float ODOMETER=15417;
    private static final double[][] ANCHORS={{0,82},{30,72},{60,56},{90,68},{120,64},{150,53},
            {154,52.2},{158,51.1},{161,51.8},{165,50.1},{168,49.5},{171,50.2},{175,48.6},{180,47.3}};
    final float[] x=new float[1501],battery=new float[1501],fuel=new float[1501];
    final double[] batteryDrop=new double[1501],fuelDrop=new double[1501],observed=new double[1501];
    final boolean[] gaps=new boolean[1501];
    private double tripBatteryStart,tripFuelStart;

    EnergyWidgetDemoData() {
        double batteryTotal=0,fuelTotal=0,previousBattery=Double.NaN,previousFuel=Double.NaN;
        for(int i=0;i<=1800;i++) {
            double km=i/10d;
            int anchor=1;
            while(ANCHORS[anchor][0]<km)anchor++;
            double[] a=ANCHORS[anchor-1],b=ANCHORS[anchor];
            double ev=Math.round((a[1]+(b[1]-a[1])*(km-a[0])/(b[0]-a[0]))*10)/10d;
            double petrol=54-Math.floor(km/7.5)-(km<80?20:km<162?5:0);
            if(i>0){batteryTotal+=Math.max(0,previousBattery-ev);fuelTotal+=Math.max(0,previousFuel-petrol);}
            previousBattery=ev;previousFuel=petrol;
            if(i==1500){tripBatteryStart=batteryTotal;tripFuelStart=fuelTotal;}
            if(i>=300){int j=i-300;x[j]=(float)km;battery[j]=(float)ev;fuel[j]=(float)petrol;
                batteryDrop[j]=batteryTotal;fuelDrop[j]=fuelTotal;observed[j]=km;}
        }
        gaps[0]=true;
    }
    float tripBattery(float capacity) {return EnergyWidgetSettings.average(batteryDrop[1500]-tripBatteryStart,30,capacity);}
    float tripFuel(float capacity) {return EnergyWidgetSettings.average(fuelDrop[1500]-tripFuelStart,30,capacity);}

    Bundle snapshot(float batteryKwh,float tankLiters) {
        Bundle b=new Bundle();
        b.putInt(EnergyWidgetProtocol.SCHEMA,EnergyWidgetProtocol.VERSION);
        b.putBoolean(EnergyWidgetProtocol.CONNECTED,true);b.putLong(EnergyWidgetProtocol.UPDATED,SystemClock.elapsedRealtime());
        b.putLong("previewTripMs",TRIP_MS);
        b.putFloat(EnergyWidgetProtocol.BATTERY_KWH,batteryKwh);b.putFloat(EnergyWidgetProtocol.TANK_LITERS,tankLiters);
        b.putFloatArray(EnergyWidgetProtocol.LEVELS,new float[]{battery[1500],fuel[1500]});
        b.putFloatArray(EnergyWidgetProtocol.TRIP,new float[]{30,tripBattery(batteryKwh),tripFuel(tankLiters)});
        b.putFloatArray(EnergyWidgetProtocol.TRIP_OBSERVED_KM,new float[]{30,30});
        b.putFloatArray(EnergyWidgetProtocol.ODOMETER,new float[]{ODOMETER});
        b.putFloatArray(EnergyWidgetProtocol.TIRES,new float[]{2.6f,2.7f,2.7f,2.7f});
        b.putFloatArray(EnergyWidgetProtocol.HISTORY_X,x);b.putFloatArray(EnergyWidgetProtocol.HISTORY_EV,battery);
        b.putFloatArray(EnergyWidgetProtocol.HISTORY_FUEL,fuel);b.putBooleanArray(EnergyWidgetProtocol.HISTORY_BREAK,gaps);
        b.putDoubleArray(EnergyWidgetProtocol.HISTORY_EV_DROP,batteryDrop);b.putDoubleArray(EnergyWidgetProtocol.HISTORY_FUEL_DROP,fuelDrop);
        b.putDoubleArray(EnergyWidgetProtocol.HISTORY_EV_KM,observed);b.putDoubleArray(EnergyWidgetProtocol.HISTORY_FUEL_KM,observed);
        b.putDoubleArray(EnergyWidgetProtocol.HISTORY_START_EV_DROP,batteryDrop);b.putDoubleArray(EnergyWidgetProtocol.HISTORY_START_FUEL_DROP,fuelDrop);
        b.putDoubleArray(EnergyWidgetProtocol.HISTORY_START_EV_KM,observed);b.putDoubleArray(EnergyWidgetProtocol.HISTORY_START_FUEL_KM,observed);
        return b;
    }
}
