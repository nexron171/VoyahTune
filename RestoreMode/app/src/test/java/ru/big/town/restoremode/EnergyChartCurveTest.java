package ru.big.town.restoremode;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class EnergyChartCurveTest {
    private static final class Trace implements EnergyChartCurve.Sink {
        final List<double[]> moves=new ArrayList<>(),curves=new ArrayList<>();
        double x,y;
        @Override public void moveTo(double x,double y){this.x=x;this.y=y;moves.add(new double[]{x,y});}
        @Override public void cubicTo(double x1,double y1,double x2,double y2,double x,double y) {
            curves.add(new double[]{this.x,this.y,x1,y1,x2,y2,x,y});this.x=x;this.y=y;
        }
    }
    private Trace trace(float[] x,float[] y,boolean[] gaps,double from) {
        Trace result=new Trace();EnergyChartCurve.trace(x,y,gaps,x.length,from,result);return result;
    }
    private double bezier(double[] segment,int axis,double t) {
        double u=1-t;
        return u*u*u*segment[axis]+3*u*u*t*segment[2+axis]+3*u*t*t*segment[4+axis]+t*t*t*segment[6+axis];
    }
    @Test public void curvesPassThroughEverySampleWithContinuousTangents() {
        float[] x={0,.1f,.4f,.6f,1,1.1f},y={80,78,77,77,79,78};
        float[] original=x.clone(),originalY=y.clone();
        Trace result=trace(x,y,null,0);
        assertEquals(1,result.moves.size());assertEquals(x.length-1,result.curves.size());
        boolean bends=false;
        for(int i=0;i<result.curves.size();i++) {
            double[] segment=result.curves.get(i);
            assertEquals(x[i],segment[0],0);assertEquals(y[i],segment[1],0);
            assertEquals(x[i+1],segment[6],0);assertEquals(y[i+1],segment[7],0);
            bends|=Math.abs(bezier(segment,1,.5)-(y[i]+y[i+1])/2d)>.001;
            if(i>0) {
                double[] before=result.curves.get(i-1);
                assertEquals((before[7]-before[5])/(before[6]-before[4]),
                        (segment[3]-segment[1])/(segment[2]-segment[0]),.000001);
            }
        }
        assertTrue("Changes in slope should be curved",bends);
        assertArrayEquals(original,x,0);assertArrayEquals(originalY,y,0);
    }
    @Test public void irregularSpacingRefillsAndPlateausNeverOvershootOrReverse() {
        Random random=new Random(150);float[] x=new float[1501],y=new float[1501];
        for(int i=0;i<x.length;i++) {
            x[i]=i==0?0:x[i-1]+.01f+random.nextFloat()*2;
            y[i]=i>0&&i%3==0?y[i-1]:random.nextInt(101);
        }
        for(double[] segment:trace(x,y,null,0).curves) {
            double low=Math.min(segment[1],segment[7]),high=Math.max(segment[1],segment[7]);
            double previous=segment[1],previousX=segment[0],sign=Math.signum(segment[7]-segment[1]);
            for(int step=1;step<=100;step++) {
                double value=bezier(segment,1,step/100d),distance=bezier(segment,0,step/100d);
                assertTrue(value>=low-.000001&&value<=high+.000001);
                assertTrue((value-previous)*sign>=-.000001);assertTrue(distance>previousX);
                previous=value;previousX=distance;
            }
        }
    }
    @Test public void explicitGapsDoNotConnectOrInfluenceNeighboringCurves() {
        float[] x={0,1,2,3},y={50,49,100,99};
        Trace result=trace(x,y,new boolean[]{false,false,true,false},0);
        assertEquals(2,result.moves.size());assertEquals(2,result.curves.size());
        assertEquals(1,result.curves.get(0)[6],0);assertEquals(2,result.curves.get(1)[0],0);
        assertEquals(49.5,bezier(result.curves.get(0),1,.5),.000001);
        assertEquals(99.5,bezier(result.curves.get(1),1,.5),.000001);
        assertEquals(3,trace(x,y,null,0).curves.size());
        assertEquals(3,trace(x,y,new boolean[]{false},0).curves.size());
    }
    @Test public void invalidLevelsBreakOnlyTheirOwnSeries() {
        float[] x={0,1,2,3,4};
        for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1,101}) {
            Trace result=trace(x,new float[]{50,49,invalid,47,46},null,0);
            assertEquals(2,result.moves.size());assertEquals(2,result.curves.size());
            assertEquals(1,result.curves.get(0)[6],0);assertEquals(3,result.curves.get(1)[0],0);
        }
        assertEquals(4,trace(x,new float[]{30,30,29,29,28},null,0).curves.size());
    }
    @Test public void invalidOrNonIncreasingDistancesStartSeparateSegments() {
        for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1,1,.5f}) {
            Trace result=trace(new float[]{0,1,invalid,2,3},new float[]{50,49,48,47,46},null,0);
            assertEquals(2,result.moves.size());
            for(double[] segment:result.curves) {
                assertTrue(segment[6]>segment[0]);
                for(double coordinate:segment)assertTrue(Double.isFinite(coordinate));
            }
        }
    }
    @Test public void windowFiltersOldPointsAndKeepsSparseEndpoints() {
        float[] x={0,1,2,3},y={100,70,69,68};
        Trace result=trace(x,y,null,1.5);
        assertEquals(1,result.curves.size());assertEquals(2,result.moves.get(0)[0],0);
        assertEquals(68.5,bezier(result.curves.get(0),1,.5),.000001);
        result=trace(x,y,null,3);
        assertEquals(1,result.moves.size());assertTrue(result.curves.isEmpty());
        assertTrue(trace(x,y,null,4).moves.isEmpty());
        assertTrue(trace(new float[0],new float[0],null,0).moves.isEmpty());
    }
    @Test public void shortArraysAndCountLimitDoNotReadUnpublishedSamples() {
        Trace result=trace(new float[]{0,1,2},new float[]{50,49},null,0);
        assertEquals(1,result.curves.size());
        result=new Trace();
        EnergyChartCurve.trace(new float[]{0,1,2},new float[]{50,49,48},null,1,0,result);
        assertEquals(1,result.moves.size());assertTrue(result.curves.isEmpty());
    }
    @Test public void consumptionCurveAcceptsSignedAbsoluteValuesBeyondPercentRange() {
        Trace result=new Trace();
        EnergyChartCurve.trace(new float[]{1,2,3,4,5},new float[]{-2.15f,0,200,Float.NaN,.56f},null,5,0,
                Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,result);
        assertEquals(2,result.moves.size());assertEquals(2,result.curves.size());
        assertEquals(-2.15,result.curves.get(0)[1],.00001);assertEquals(200,result.curves.get(1)[7],0);
    }
}
