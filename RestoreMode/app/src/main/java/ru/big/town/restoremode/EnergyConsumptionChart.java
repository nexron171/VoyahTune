package ru.big.town.restoremode;

import java.util.ArrayList;
import java.util.List;

/** Validated view of the last 10 km; no synthetic samples and no per-100-km conversion. */
final class EnergyConsumptionChart {
    static final double WINDOW=10,EPS=.0001;
    final double cursor;
    final double[] start,end;
    final float[] position,battery,fuel;
    final boolean[] gaps;
    final double electricMax,electricMin,fuelMax;
    EnergyConsumptionChart(double cursor,double[] starts,double[] ends,float[] ev,float[] petrol,boolean[] breaks) {
        this.cursor=Double.isFinite(cursor)&&cursor>=0?cursor:0;
        int n=starts==null||ends==null||ev==null||petrol==null?0:Math.min(Math.min(starts.length,ends.length),Math.min(ev.length,petrol.length));
        List<Integer> visible=new ArrayList<>();double previous=Double.NEGATIVE_INFINITY;
        for(int i=0;i<n;i++) {
            if(!Double.isFinite(starts[i])||!Double.isFinite(ends[i])||starts[i]<0||ends[i]<=starts[i]
                    ||starts[i]<this.cursor-WINDOW-EPS||ends[i]>this.cursor+EPS||starts[i]<previous-EPS)continue;
            visible.add(i);previous=ends[i];
        }
        if(visible.size()>40)visible=visible.subList(visible.size()-40,visible.size());
        n=visible.size();start=new double[n];end=new double[n];position=new float[n];battery=new float[n];fuel=new float[n];gaps=new boolean[n];
        double high=0,low=0,gas=0;
        for(int j=0;j<n;j++) {
            int i=visible.get(j);start[j]=starts[i];end[j]=ends[i];position[j]=(float)(WINDOW+end[j]-this.cursor);
            battery[j]=Float.isFinite(ev[i])?ev[i]:Float.NaN;fuel[j]=Float.isFinite(petrol[i])&&petrol[i]>=0?petrol[i]:Float.NaN;
            gaps[j]=(breaks!=null&&i<breaks.length&&breaks[i])||(j>0&&(i!=visible.get(j-1)+1||start[j]>end[j-1]+EPS));
            if(Float.isFinite(battery[j])){high=Math.max(high,battery[j]);low=Math.max(low,-battery[j]);}
            if(Float.isFinite(fuel[j]))gas=Math.max(gas,fuel[j]);
        }
        electricMax=ceiling(high);electricMin=-ceiling(low);fuelMax=ceiling(gas);
    }
    private static double ceiling(double value) {
        if(!(value>0))return 0;
        double magnitude=Math.pow(10,Math.floor(Math.log10(value)));
        for(double step:new double[]{1,2,2.5,4,5,6,8,10})if(step>=value/magnitude-1e-7)return Math.max(value,step*magnitude);
        return value;
    }
    double zero() {
        if(electricMin>=0)return 1;
        if(electricMax>0)return electricMax/(electricMax-electricMin);
        return fuelMax>0?.5:0;
    }
    double electricPosition(double value) {
        double zero=zero();
        return value>=0?zero-(electricMax>0?value/electricMax*zero:0):zero+value/electricMin*(1-zero);
    }
    double fuelPosition(double value) {return zero()-(fuelMax>0?value/fuelMax*zero():0);}
    int nearest(float fraction) {
        int selected=-1;double best=Double.POSITIVE_INFINITY;
        for(int i=0;i<end.length;i++){double delta=Math.abs(position[i]-fraction*WINDOW);if(delta<best){best=delta;selected=i;}}
        return selected;
    }
}
