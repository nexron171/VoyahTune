package ru.big.town.anative;

import java.io.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;

/** Versioned, bounded snapshot; no elapsedRealtime/boot identity is used as persistent freshness. */
final class EnergyHistoryState {
    double[] distance, tripBattery, tripFuel;
    List<EnergyHistory.Point> points;
    EnergyConsumptionHistory.State consumption=new EnergyConsumptionHistory.State();

    String encode() throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(3);
        for(double v:distance) out.writeDouble(v);
        for(double v:tripBattery) out.writeDouble(v);
        for(double v:tripFuel) out.writeDouble(v);
        out.writeInt(points.size());
        for(EnergyHistory.Point p:points) {
            out.writeFloat(p.km);out.writeFloat(p.ev);out.writeFloat(p.fuel);out.writeBoolean(p.gap);
            out.writeDouble(p.evDrop);out.writeDouble(p.fuelDrop);out.writeDouble(p.evKm);out.writeDouble(p.fuelKm);
            out.writeDouble(p.startEvDrop);out.writeDouble(p.startFuelDrop);out.writeDouble(p.startEvKm);out.writeDouble(p.startFuelKm);
        }
        consumption.write(out);
        out.flush();
        CRC32 crc=new CRC32();crc.update(bytes.toByteArray());out.writeLong(crc.getValue());out.flush();
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    static EnergyHistoryState decode(String encoded) throws IOException {
        try {
            if(encoded==null || encoded.length()>250_000) throw new IOException("Invalid snapshot size");
            byte[] bytes=Base64.getDecoder().decode(encoded);
            if(bytes.length<72) throw new IOException("Truncated snapshot");
            CRC32 crc=new CRC32();crc.update(bytes,0,bytes.length-8);
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
            int version=in.readInt();if(version!=2&&version!=3) throw new IOException("Unknown snapshot schema");
            EnergyHistoryState state=new EnergyHistoryState();
            state.distance=read(in,3);state.tripBattery=read(in,2);state.tripFuel=read(in,2);
            int n=in.readInt();if(n<0 || n>EnergyHistory.MAX_POINTS) throw new IOException("Invalid point count");
            state.points=new ArrayList<>(n);
            for(int i=0;i<n;i++) state.points.add(new EnergyHistory.Point(in.readFloat(),in.readFloat(),in.readFloat(),
                    in.readBoolean(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),
                    in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble()));
            if(version==3)state.consumption=EnergyConsumptionHistory.State.read(in);
            if(in.readLong()!=crc.getValue() || in.available()!=0) throw new IOException("Snapshot checksum mismatch");
            validate(state);
            return state;
        } catch(IllegalArgumentException e) {throw new IOException("Invalid snapshot encoding",e);}
    }
    private static double[] read(DataInputStream in,int n)throws IOException {
        double[] values=new double[n];for(int i=0;i<n;i++) values[i]=in.readDouble();return values;
    }
    private static void validate(EnergyHistoryState state)throws IOException {
        double[] axis=state.distance;
        if(!nonnegative(axis[0]) || (!nonnegative(axis[1]) && !(state.points.isEmpty()&&Double.isNaN(axis[1])))
                || !Double.isFinite(axis[2]) || axis[2]<-1 || axis[2]>Integer.MAX_VALUE || axis[2]!=Math.rint(axis[2]))
            throw new IOException("Invalid distance baseline");
        for(double v:state.tripBattery)if(!nonnegative(v))throw new IOException("Invalid trip total");
        for(double v:state.tripFuel)if(!nonnegative(v))throw new IOException("Invalid trip total");
        state.consumption.validate(axis[0]);
        EnergyHistory.Point previous=null;
        for(EnergyHistory.Point p:state.points) {
            if(!nonnegative(p.km)||!level(p.ev)||!level(p.fuel)||!nonnegative(p.evDrop)||!nonnegative(p.fuelDrop)
                    ||!nonnegative(p.evKm)||!nonnegative(p.fuelKm)||p.km>axis[0]+.1)
                throw new IOException("Invalid history point");
            if(!nonnegative(p.startEvDrop)||!nonnegative(p.startFuelDrop)||!nonnegative(p.startEvKm)||!nonnegative(p.startFuelKm)
                    ||p.startEvDrop>p.evDrop||p.startFuelDrop>p.fuelDrop||p.startEvKm>p.evKm||p.startFuelKm>p.fuelKm)
                throw new IOException("Invalid bucket baseline");
            if(previous!=null&&(p.km<=previous.km||p.evDrop<previous.evDrop||p.fuelDrop<previous.fuelDrop
                    ||p.evKm<previous.evKm||p.fuelKm<previous.fuelKm||p.startEvDrop<previous.evDrop
                    ||p.startFuelDrop<previous.fuelDrop||p.startEvKm<previous.evKm||p.startFuelKm<previous.fuelKm))
                throw new IOException("Non-monotonic history");
            previous=p;
        }
    }
    private static boolean nonnegative(double v){return Double.isFinite(v)&&v>=0;}
    private static boolean level(float v){return Float.isNaN(v)||(Float.isFinite(v)&&v>=0&&v<=100);}
}
