package ru.big.town.common;

/** Vehicle services use the existing CarPlay subscription and bounded command envelope. */
public final class CarPlayUltraProtocol {
    private CarPlayUltraProtocol() {}

    public static final int SET = 182;
    public static final int RESULT = 183;
    public static final String STATE = "ultra";
    public static final String ENABLED = "ultraEnabled";
}
