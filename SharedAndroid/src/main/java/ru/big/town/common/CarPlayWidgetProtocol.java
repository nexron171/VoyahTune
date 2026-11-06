package ru.big.town.common;

/** Embedded display retention and CarPlay lookup on the signature-protected widget channel. */
public final class CarPlayWidgetProtocol {
    public static final int MESSAGE = 34;
    public static final String GRID_SIZE = "widgetGridSize";
    public static final String DETACH = "embeddedDetach";
    public static final String QUERY = "embeddedCarPlayQuery";
    public static final String GENERATION = "carPlayWidgetGeneration";
    public static final String WIDGETS = "carPlayWidgets";
    public static final String DPI = "carPlayWidgetDpi";

    private CarPlayWidgetProtocol() {}
}
