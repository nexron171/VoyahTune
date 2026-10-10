package ru.big.town.common;

/** CarPlay retention extensions of the signature-protected widget/display message. */
public final class CarPlayWidgetProtocol {
    public static final int MESSAGE = 34;
    public static final String DETACH = "embeddedDetach";
    public static final String QUERY = "embeddedCarPlayQuery";
    public static final String GENERATION = "carPlayWidgetGeneration";
    public static final String WIDGETS = "carPlayWidgets";
    public static final String DPI = "carPlayWidgetDpi";

    private CarPlayWidgetProtocol() {}
}
