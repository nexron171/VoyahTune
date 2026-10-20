package ru.big.town.common;

/** Climate messages share the signature-protected CarPlay vehicle subscription. */
public final class CarPlayClimateProtocol {
    private CarPlayClimateProtocol() {}

    public static final int SET = 180;
    public static final int RESULT = 181;
    public static final String STATE = "climate";
    public static final String REQUEST = "request";
    public static final String FIELD = "field";
    public static final String VALUE = "value";
    public static final String ERROR = "error";
    public static final String DEADLINE = "deadline";
    public static final String DRIVER_TEMPERATURE = "driverTemperature";
    public static final String PASSENGER_TEMPERATURE = "passengerTemperature";
    public static final String POWER = "power";
    public static final String COMPRESSOR = "compressor";
    public static final String FAN = "fan";
    public static final String AUTO = "auto";
    public static final String SYNC = "sync";
    public static final String RECIRCULATION = "recirculation";
    public static final String VENT_UPPER = "ventUpper";
    public static final String VENT_MIDDLE = "ventMiddle";
    public static final String VENT_LOWER = "ventLower";
    public static final String DRIVER_HEATING_LEVEL = "driverSeatHeatingLevel";
    public static final String DRIVER_HEATING_ON = "driverSeatHeatingOn";
    public static final String PASSENGER_HEATING_LEVEL = "passengerSeatHeatingLevel";
    public static final String PASSENGER_HEATING_ON = "passengerSeatHeatingOn";
    public static final String REAR_LEFT_HEATING_LEVEL = "rearLeftSeatHeatingLevel";
    public static final String REAR_LEFT_HEATING_ON = "rearLeftSeatHeatingOn";
    public static final String REAR_RIGHT_HEATING_LEVEL = "rearRightSeatHeatingLevel";
    public static final String REAR_RIGHT_HEATING_ON = "rearRightSeatHeatingOn";
    public static final String DRIVER_VENTILATION_LEVEL = "driverSeatVentilationLevel";
    public static final String DRIVER_VENTILATION_ON = "driverSeatVentilationOn";
    public static final String PASSENGER_VENTILATION_LEVEL = "passengerSeatVentilationLevel";
    public static final String PASSENGER_VENTILATION_ON = "passengerSeatVentilationOn";
    public static final String STEERING_WHEEL_HEATING_LEVEL = "steeringWheelHeatingLevel";

    public static final int SUCCESS = 0;
    public static final int UNAVAILABLE = -6727;
    public static final int INVALID = -6705;
    public static final int TIMEOUT = -6722;
    public static final int BUSY = -6721;
}
