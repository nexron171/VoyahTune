package ru.big.town.anative;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Absolute quantities on a separate 250 m cadence, collected BEFORE level-history decimation. */
final class EnergyConsumptionHistory {
    static final double STEP_KM=.25,WINDOW_KM=10,EPS=.0001,MAX_INTERVAL_KM=.35;
    static final int MAX_POINTS=40,EV_INVALID=1,FUEL_INVALID=2;
    static final class Point {
        final double start,end,evDrop,evRise,fuelDrop,fuelRise;
        final int invalid;
        final boolean gap;
        Point(double start,double end,double evDrop,double evRise,double fuelDrop,double fuelRise,int invalid,boolean gap) {
            this.start=start;this.end=end;this.evDrop=evDrop;this.evRise=evRise;
            this.fuelDrop=fuelDrop;this.fuelRise=fuelRise;this.invalid=invalid;this.gap=gap;
        }
        float electricity(float capacity) {return (invalid&EV_INVALID)!=0||evRise>5+EPS?Float.NaN:(float)((evDrop-evRise)*capacity/100);}
        float fuel(float capacity) {return (invalid&FUEL_INVALID)!=0?Float.NaN:(float)(fuelDrop*capacity/100);}
    }
    static final class State {
        final List<Point> points=new ArrayList<>();
        double cursor=Double.NaN,start,target,evDrop,evRise,fuelDrop,fuelRise;
        int invalid;
        boolean pending,nextGap=true;
        void write(DataOutputStream out)throws IOException {
            out.writeDouble(cursor);out.writeBoolean(pending);out.writeBoolean(nextGap);
            out.writeDouble(start);out.writeDouble(target);out.writeDouble(evDrop);out.writeDouble(evRise);
            out.writeDouble(fuelDrop);out.writeDouble(fuelRise);out.writeInt(invalid);out.writeInt(points.size());
            for(Point p:points){out.writeDouble(p.start);out.writeDouble(p.end);out.writeDouble(p.evDrop);
                out.writeDouble(p.evRise);out.writeDouble(p.fuelDrop);out.writeDouble(p.fuelRise);out.writeInt(p.invalid);out.writeBoolean(p.gap);}
        }
        static State read(DataInputStream in)throws IOException {
            State s=new State();s.cursor=in.readDouble();s.pending=in.readBoolean();s.nextGap=in.readBoolean();
            s.start=in.readDouble();s.target=in.readDouble();s.evDrop=in.readDouble();s.evRise=in.readDouble();
            s.fuelDrop=in.readDouble();s.fuelRise=in.readDouble();s.invalid=in.readInt();
            int n=in.readInt();if(n<0||n>MAX_POINTS)throw new IOException("Invalid consumption count");
            for(int i=0;i<n;i++)s.points.add(new Point(in.readDouble(),in.readDouble(),in.readDouble(),
                    in.readDouble(),in.readDouble(),in.readDouble(),in.readInt(),in.readBoolean()));
            return s;
        }
        void validate(double axis)throws IOException {
            if((!nonnegative(cursor)&&!(Double.isNaN(cursor)&&!pending&&points.isEmpty()))||cursor>axis+EPS
                    ||!totals(evDrop,evRise,fuelDrop,fuelRise)||invalid<0||invalid>3||points.size()>MAX_POINTS)
                throw new IOException("Invalid consumption state");
            if(pending&&(!nonnegative(start)||!Double.isFinite(target)||start>cursor||target<=cursor
                    ||target>start+STEP_KM+EPS))throw new IOException("Invalid consumption interval");
            Point previous=null;
            for(Point p:points) {
                if(!nonnegative(p.start)||!Double.isFinite(p.end)||p.end<=p.start||p.end-p.start>MAX_INTERVAL_KM+EPS
                        ||p.end>cursor+EPS||p.start<cursor-WINDOW_KM-EPS||!totals(p.evDrop,p.evRise,p.fuelDrop,p.fuelRise)
                        ||p.invalid<0||p.invalid>3||(previous!=null&&p.start<previous.end-EPS))
                    throw new IOException("Invalid consumption point");
                previous=p;
            }
            if(pending&&previous!=null&&start<previous.end-EPS)throw new IOException("Overlapping consumption interval");
        }
    }
    private State state=new State();
    private float lastEv=Float.NaN,lastFuel=Float.NaN;
    private long lastTime=-1;

    List<Point> points(){return new ArrayList<>(state.points);}
    boolean breakSegment() {
        boolean changed=!state.nextGap||(state.pending&&state.invalid!=3);
        state.nextGap=true;if(state.pending)state.invalid=3;
        lastEv=lastFuel=Float.NaN;lastTime=-1;return changed;
    }
    State snapshot() {
        State s=new State();s.points.addAll(state.points);s.cursor=state.cursor;s.start=state.start;s.target=state.target;
        s.evDrop=state.evDrop;s.evRise=state.evRise;s.fuelDrop=state.fuelDrop;s.fuelRise=state.fuelRise;
        s.invalid=state.invalid;s.pending=state.pending;s.nextGap=state.nextGap;return s;
    }
    void restore(State saved) {
        state=saved;state=snapshot();
        // Persist unfinished totals, but never treat old sensor baselines as fresh after a restart.
        breakSegment();
    }
    private void anchor(double km,float ev,float fuel,double target) {
        state.pending=true;state.start=state.cursor=km;state.target=target;
        state.evDrop=state.evRise=state.fuelDrop=state.fuelRise=0;
        state.invalid=(valid(ev)?0:EV_INVALID)|(valid(fuel)?0:FUEL_INVALID);lastEv=ev;lastFuel=fuel;
    }
    boolean sample(double km,float ev,float fuel,long now,long distanceAt,long batteryAt,long fuelAt) {
        if(!nonnegative(km)||!EnergyHistory.fresh(now,distanceAt))return breakSegment();
        ev=EnergyHistory.fresh(now,batteryAt)&&valid(ev)?ev:Float.NaN;
        fuel=EnergyHistory.fresh(now,fuelAt)&&valid(fuel)?fuel:Float.NaN;
        boolean changed=false;
        if(lastTime>=0&&(now<lastTime||now-lastTime>EnergyHistory.MAX_SAMPLE_AGE_MS))changed=breakSegment();
        lastTime=now;
        // Reject rollbacks without deleting recorded history. The controller supplies a continuous axis.
        if(Double.isFinite(state.cursor)&&km<state.cursor-EPS)return breakSegment()||changed;
        if(!state.pending){anchor(km,ev,fuel,km+STEP_KM);return true;}
        if(km-state.cursor>.51||km-state.start>MAX_INTERVAL_KM+EPS) {
            state.nextGap=true;anchor(km,ev,fuel,km+STEP_KM);trim();return true;
        }
        int oldInvalid=state.invalid;double oldEv=state.evDrop+state.evRise,oldFuel=state.fuelDrop+state.fuelRise;
        if(valid(lastEv)&&valid(ev)){double delta=(double)lastEv-ev;state.evDrop+=Math.max(0,delta);state.evRise+=Math.max(0,-delta);}
        else state.invalid|=EV_INVALID;
        if(valid(lastFuel)&&valid(fuel)){double delta=(double)lastFuel-fuel;state.fuelDrop+=Math.max(0,delta);state.fuelRise+=Math.max(0,-delta);}
        else state.invalid|=FUEL_INVALID;
        changed|=state.cursor!=km||oldInvalid!=state.invalid||oldEv!=state.evDrop+state.evRise||oldFuel!=state.fuelDrop+state.fuelRise;
        state.cursor=km;lastEv=ev;lastFuel=fuel;
        if(km+EPS>=state.target) {
            state.points.add(new Point(state.start,km,state.evDrop,state.evRise,state.fuelDrop,state.fuelRise,state.invalid,state.nextGap));
            state.nextGap=false;
            // Keep the nominal cadence even when an OEM 100 m step crosses the 250 m threshold.
            anchor(km,ev,fuel,state.target+STEP_KM);changed=true;
        }
        trim();return changed;
    }
    private void trim(){while(!state.points.isEmpty()&&(state.points.size()>MAX_POINTS||state.points.get(0).start<state.cursor-WINDOW_KM-EPS))state.points.remove(0);}
    private static boolean valid(float v){return Float.isFinite(v)&&v>=0&&v<=100;}
    private static boolean nonnegative(double v){return Double.isFinite(v)&&v>=0;}
    private static boolean totals(double... values){for(double v:values)if(!nonnegative(v))return false;return true;}
}
