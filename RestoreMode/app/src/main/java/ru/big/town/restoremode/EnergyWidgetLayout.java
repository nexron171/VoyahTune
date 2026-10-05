package ru.big.town.restoremode;

/** Approved tile ranges and design geometry, independent of actual display scaling. */
final class EnergyWidgetLayout {
    private EnergyWidgetLayout() {}
    static boolean isWidget(String id) {
        return "energyWidget".equals(id)||"energyTripWidget".equals(id)
                ||"tirePressureWidget".equals(id)||"odometerWidget".equals(id);
    }
    static int minWidth(String id) { return 2; }
    static int maxWidth(String id) { return "energyWidget".equals(id)||"energyTripWidget".equals(id)?8:4; }
    static int minHeight(String id) { return "energyWidget".equals(id)||"tirePressureWidget".equals(id)?3:"energyTripWidget".equals(id)?2:1; }
    static int maxHeight(String id) { return "tirePressureWidget".equals(id)?4:minHeight(id); }
    static boolean vertical(String id,int columns) { return ("energyWidget".equals(id)||"energyTripWidget".equals(id))&&width(id,columns)<=3; }
    static int minHeight(String id,int columns) { return vertical(id,columns)?"energyTripWidget".equals(id)?4:5:minHeight(id); }
    static int maxHeight(String id,int columns) { return vertical(id,columns)?5:maxHeight(id); }
    static int width(String id,int value) { return Math.max(minWidth(id),Math.min(maxWidth(id),value)); }
    static int height(String id,int columns,int value) { return Math.max(minHeight(id,columns),Math.min(maxHeight(id,columns),value)); }
    static int pixelsWide(int columns) {return columns*147-8;}
    static int pixelsHigh(String id,int rows) {return "odometerWidget".equals(id)?118:"energyTripWidget".equals(id)&&rows==2?245:rows*127-8;}
}
