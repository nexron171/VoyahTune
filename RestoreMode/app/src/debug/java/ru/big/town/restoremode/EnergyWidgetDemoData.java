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
    final double[] consumptionStart=new double[40],consumptionEnd=new double[40];
    final boolean[] consumptionGaps=new boolean[40];
    private final double[] consumptionEvDelta=new double[40],consumptionFuelDrop=new double[40];
    private double tripBatteryStart,tripFuelStart;

    EnergyWidgetDemoData() {
        double batteryTotal=0,fuelTotal=0,previousBattery=Double.NaN,previousFuel=Double.NaN;
        for(int i=0;i<=1800;i++) {
            double km=i/10d;
            double ev=batteryAt(km),petrol=fuelAt(km);
            if(i>0){batteryTotal+=Math.max(0,previousBattery-ev);fuelTotal+=Math.max(0,previousFuel-petrol);}
            previousBattery=ev;previousFuel=petrol;
            if(i==1500){tripBatteryStart=batteryTotal;tripFuelStart=fuelTotal;}
            if(i>=300){int j=i-300;x[j]=(float)km;battery[j]=(float)ev;fuel[j]=(float)petrol;
                batteryDrop[j]=batteryTotal;fuelDrop[j]=fuelTotal;observed[j]=km;}
        }
        gaps[0]=true;
        // Synthetic 50 m observations, matching the prototype; never resample real vehicle data.
        for(int i=0;i<40;i++) {
            consumptionStart[i]=170+i*.25;consumptionEnd[i]=consumptionStart[i]+.25;
            double previousEv=batteryAt(consumptionStart[i]),previousPetrol=fuelAt(consumptionStart[i]),rise=0;
            for(int j=1;j<=5;j++) {
                double km=consumptionStart[i]+j*.05,ev=batteryAt(km),petrol=fuelAt(km);
                consumptionEvDelta[i]+=previousEv-ev;rise+=Math.max(0,ev-previousEv);
                consumptionFuelDrop[i]+=Math.max(0,previousPetrol-petrol);
                previousEv=ev;previousPetrol=petrol;
            }
            if(rise>5.0001)consumptionEvDelta[i]=Double.NaN;
        }
        consumptionGaps[0]=true;
    }
    private static double batteryAt(double km) {
        int anchor=1;while(ANCHORS[anchor][0]<km)anchor++;
        double[] a=ANCHORS[anchor-1],b=ANCHORS[anchor];
        return Math.round((a[1]+(b[1]-a[1])*(km-a[0])/(b[0]-a[0]))*10)/10d;
    }
    private static double fuelAt(double km) {return 54-Math.floor(km/7.5)-(km<80?20:km<162?5:0);}
    float[] consumptionBattery(float capacity) {return amounts(consumptionEvDelta,capacity);}
    float[] consumptionFuel(float capacity) {return amounts(consumptionFuelDrop,capacity);}
    private static float[] amounts(double[] deltas,float capacity) {
        float[] result=new float[deltas.length];for(int i=0;i<result.length;i++)result[i]=(float)(deltas[i]*capacity/100);
        return result;
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
        b.putDouble(EnergyWidgetProtocol.RECORDED_KM,180);
        b.putDoubleArray(EnergyWidgetProtocol.CONSUMPTION_START,consumptionStart);b.putDoubleArray(EnergyWidgetProtocol.CONSUMPTION_END,consumptionEnd);
        b.putFloatArray(EnergyWidgetProtocol.CONSUMPTION_EV,consumptionBattery(batteryKwh));b.putFloatArray(EnergyWidgetProtocol.CONSUMPTION_FUEL,consumptionFuel(tankLiters));
        b.putBooleanArray(EnergyWidgetProtocol.CONSUMPTION_BREAK,consumptionGaps);
        return b;
    }
}
