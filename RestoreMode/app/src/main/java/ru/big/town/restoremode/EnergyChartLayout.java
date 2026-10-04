package ru.big.town.restoremode;

/** Shared drawing/touch geometry for the approved 70/30 chart layout. */
final class EnergyChartLayout {
    final float pad,split,shortColumn,shortContent,left,right,shortLeft,shortRight;
    static final float TOP=183,BOTTOM=253,AXIS=276;
    EnergyChartLayout(float width,boolean compact) {
        pad=compact?20:27;float gap=compact?12:18;
        split=pad+(width-2*pad-gap)*.7f;shortColumn=split+gap;shortContent=shortColumn+(compact?8:12);
        left=pad+(compact?48:64);right=split-12;
        shortLeft=shortContent+(compact?38:46);shortRight=width-pad-(compact?22:29);
    }
}
