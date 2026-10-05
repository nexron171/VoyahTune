package ru.big.town.restoremode;

/** Shared drawing/touch geometry for horizontal and narrow vertical energy tiles. */
final class EnergyChartLayout {
    final float pad,split,shortColumn,shortContent,left,right,shortLeft,shortRight;
    final float top,bottom,axis,shortTop,shortBottom,shortAxis,splitY;
    final float buttonTop,buttonBottom,buttonLeft,buttonWidth;
    final boolean vertical;
    static final float TOP=183,BOTTOM=253,AXIS=276;
    EnergyChartLayout(float width,float height,boolean compact,boolean vertical) {
        this.vertical=vertical;pad=vertical?18:compact?20:27;splitY=vertical?height-215:height/2;
        buttonTop=vertical?37:24;buttonBottom=vertical?70:68;
        buttonWidth=vertical?(width-2*pad)/3:compact?65:90;
        buttonLeft=vertical?pad:width-pad-3*buttonWidth;
        if(vertical) {
            split=width-pad;shortColumn=pad;shortContent=pad;
            left=pad+48;right=width-pad;
            shortLeft=pad+52;shortRight=width-pad-44;
            top=206;bottom=306;axis=330;
            shortTop=height-178;shortBottom=height-78;shortAxis=height-55;
        } else {
            float gap=compact?12:18;
            split=pad+(width-2*pad-gap)*.7f;shortColumn=split+gap;shortContent=shortColumn+(compact?8:12);
            left=pad+(compact?48:64);right=split-12;
            shortLeft=shortContent+(compact?38:46);shortRight=width-pad-(compact?22:29);
            top=TOP;bottom=BOTTOM;axis=AXIS;shortTop=TOP;shortBottom=BOTTOM;shortAxis=AXIS;
        }
    }
    int windowSlot(float x,float y) {
        if(y<buttonTop||y>buttonBottom||x<buttonLeft||x>buttonLeft+3*buttonWidth)return -1;
        return Math.min(2,(int)((x-buttonLeft)/buttonWidth));
    }
    boolean containsHistory(float x,float y) {
        return x>=left&&x<=right&&y>=top-10&&y<=axis+3;
    }
    boolean containsConsumption(float x,float y) {
        return x>=shortLeft&&x<=shortRight&&y>=shortTop-10&&y<=shortAxis+3;
    }
}
