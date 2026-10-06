package ru.big.town.restoremode;

/** The levels plot fills the space between fixed-size readings and the average footer. */
final class EnergyChartLayout {
    final float pad,split,left,right,top,bottom,axis;
    final float buttonTop,buttonBottom,buttonLeft,buttonWidth;
    final float readingLabel,readingValue,remainingLabel,remainingValue;
    final float levelsTitle,periodLabel,periodUnits,periodValue,periodNote;
    final boolean vertical;
    EnergyChartLayout(float width,float height,boolean vertical) {this(width,height,vertical,1);}
    EnergyChartLayout(float width,float height,boolean vertical,float fontScale) {
        this.vertical=vertical;pad=18;
        float f=Math.max(1,fontScale);
        buttonTop=vertical?40*f:12;buttonBottom=buttonTop+40*f;
        buttonWidth=vertical?(width-2*pad)/3:80*f;
        buttonLeft=vertical?pad:width-pad-3*buttonWidth;
        readingLabel=vertical?buttonBottom+24*f:80*f;
        readingValue=readingLabel+46*f;
        remainingLabel=vertical?readingValue+28*f:readingLabel;
        remainingValue=remainingLabel+46*f;
        levelsTitle=Math.max(readingValue,remainingValue)+30*f;
        split=right=width-pad;left=pad+52*f;
        periodNote=height-18*f;periodValue=periodNote-24*f;
        periodUnits=periodValue-46*f;periodLabel=periodUnits-26*f;
        axis=periodLabel-28*f;top=levelsTitle+18*f;bottom=Math.max(top,axis-24*f);
    }
    int windowSlot(float x,float y) {
        if(y<buttonTop||y>buttonBottom||x<buttonLeft||x>buttonLeft+3*buttonWidth)return -1;
        return Math.min(2,(int)((x-buttonLeft)/buttonWidth));
    }
    boolean containsHistory(float x,float y) {return x>=left&&x<=right&&y>=top&&y<=bottom;}
}
