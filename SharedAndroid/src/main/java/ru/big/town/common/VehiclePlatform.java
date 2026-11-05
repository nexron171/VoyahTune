package ru.big.town.common;

import java.lang.reflect.Method;

/** OEM vehicle identity, independent of APK metadata and installation history. */
public enum VehiclePlatform {
    H97C,
    H97X,
    H97Y,
    H97D,
    OTHER;

    private static final class Device {
        static final VehiclePlatform PLATFORM = readDevice();
    }

    public static VehiclePlatform current() {
        return Device.PLATFORM;
    }

    public boolean isH97XY() {
        return this == H97X || this == H97Y;
    }

    public static VehiclePlatform detect(String vehicleType, String product, String thirdParty) {
        String digits = vehicleType == null ? "" : vehicleType.replaceAll("[^0-9]", "");
        if ("13402".equals(digits)) return H97X;
        if ("13401".equals(digits)) return H97Y;
        if (!digits.isEmpty() && !"0".equals(digits) && !"134".equals(digits)) return OTHER;
        // VehicleConfigHelper remaps the generic NVRAM type using these exact product names.
        if ("f6811".equals(product) || "f6811".equals(thirdParty)) return H97X;
        if ("f6805h".equals(product) || "f6805h".equals(thirdParty)) return H97Y;
        if ("f6808".equals(product) || "f6808".equals(thirdParty)) return H97D;
        if ("134".equals(digits) || "f6802h".equals(product) || "f6802h".equals(thirdParty)) {
            return H97C;
        }
        return OTHER;
    }

    private static VehiclePlatform readDevice() {
        String vehicleType = "";
        try {
            Class<?> properties = Class.forName("com.qinggan.os.SystemProperties");
            Object value = properties.getMethod("nvramRead", int.class).invoke(null, 43);
            if (value instanceof String) vehicleType = (String) value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // Product properties remain readable when the optional OEM NVRAM API is absent.
        }
        return detect(
                vehicleType, property("ro.build.product"), property("ro.build.product_3rdparty"));
    }

    private static String property(String name) {
        try {
            Method get =
                    Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            Object value = get.invoke(null, name);
            return value instanceof String ? (String) value : "";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return "";
        }
    }
}
