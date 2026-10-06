package ru.big.town.restoremode;

/** Independent 1–3 column chart; a one-row tile omits vertical labels to keep the plot readable. */
final class EnergyConsumptionLayout {
    final float left,right,top,bottom,title,textInset,markerRadius;
    final boolean compact,axes;
    EnergyConsumptionLayout(float width,float height,int columns,int rows,float fontScale) {
        compact=columns==1&&rows==1;axes=rows>=2;
        float f=compact?fontScale:Math.max(1,fontScale);
        textInset=compact?8:12;markerRadius=compact?2.5f:2;
        left=axes?64*f:compact?textInset+markerRadius:10;
        right=width-(axes?56*f:compact?textInset+markerRadius:10);
        title=compact?textInset+20*f:30*f;
        top=compact?title+2+markerRadius:(axes?52:38)*f;
        // Leave only the inset plus room for Y labels or the last point's radius.
        bottom=Math.max(top,height-8-(axes?8*f:markerRadius));
    }
    boolean contains(float x,float y) {return x>=left&&x<=right&&y>=top&&y<=bottom;}
}
