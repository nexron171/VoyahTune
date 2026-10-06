package ru.big.town.restoremode;

/** Independent 2–3 column chart; a one-row tile omits vertical labels to keep the plot readable. */
final class EnergyConsumptionLayout {
    final float left,right,top,bottom,axis,title,legend;
    final boolean axes;
    EnergyConsumptionLayout(float width,float height,int rows,float fontScale) {
        float f=Math.max(1,fontScale);axes=rows>=2;
        left=axes?64*f:12;right=width-(axes?56*f:12);
        title=30*f;legend=50*f;top=(axes?78:60)*f;
        bottom=Math.max(top,height-28*f);axis=height-6*f;
    }
    boolean contains(float x,float y) {return x>=left&&x<=right&&y>=top&&y<=bottom;}
}
