package ru.big.town.anative;

/** Android-free contract for the Android 11/H97C manual wash request. */
final class WashModePolicy {
    static final String CLEANING_MODE = "CAR_CLEANING_MODE_SWITCH";
    static final int CLEANING_MODE_ID = 1133;

    static final int PARKING_ORDINAL = 0;
    static final int PARKING_VALUE = 0;
    static final int CLEANING_OFF = 0;
    static final int CLEANING_ON = 1;

    enum Outcome {
        ACCEPTED,
        NOT_IN_PARK,
        TRANSPORT_FAILURE
    }

    private WashModePolicy() {
    }

    /** Both fields are checked because the OEM Parcelable carries an enum ordinal and value. */
    static boolean isParking(int ordinal, int value) {
        return ordinal == PARKING_ORDINAL && value == PARKING_VALUE;
    }
}
