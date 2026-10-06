package ru.big.town.common;

/** Shared, signature-protected trip contract. Track data never travels in UI broadcasts. */
public final class TripProtocol {
    private TripProtocol() { }
    public static final String PERMISSION = "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
    public static final String NATIVE = "ru.big.town.anative", UI = "ru.big.town.restoremode";
    public static final String UPDATE = NATIVE + ".TRIP_UPDATE", REQUEST = NATIVE + ".REQUEST_TRIP_UPDATE";
    public static final String STOP = NATIVE + ".TRIP_STOP", WAITING_FOR_MOVEMENT = "waitingForMovement";
    public static final String DELETE = NATIVE + ".TRIP_DELETE";
    public static final String DELETE_START = "deleteStart", DELETE_ALL = "deleteAll";
    public static final String LOCATION = NATIVE + ".TRIP_LOCATION", LOCATION_CHANGED = NATIVE + ".TRIP_LOCATION_CHANGED";
    public static final String LOCATION_SERVICE = UI + ".TripLocationService";
    public static final String LOCATION_PERMISSION_STATE = "tripLocationPermission";
    public static final String AUTHORITY = NATIVE + ".trips";
}
