package ru.big.town.common;

/** Unknown never enables Full and is not evidence of an installed Light package. */
public enum InstallModeValue {
    FULL, LIGHT, UNKNOWN;
    public static InstallModeValue parse(String value) {
        if ("full".equals(value)) return FULL;
        if ("light".equals(value)) return LIGHT;
        return UNKNOWN;
    }
}
