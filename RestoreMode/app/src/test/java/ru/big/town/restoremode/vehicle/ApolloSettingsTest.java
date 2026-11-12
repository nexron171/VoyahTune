package ru.big.town.restoremode.vehicle;

import static org.junit.Assert.assertEquals;

import android.content.SharedPreferences;

import org.junit.Test;

import ru.big.town.common.VehiclePlatform;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class ApolloSettingsTest {
    @Test
    public void stockMenuPreservesSavedChoiceAndExcludesXAndY() {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences prefs = preferences(values);
        for (VehiclePlatform platform : VehiclePlatform.values()) {
            assertEquals(false, ApolloSettings.stockUiEnabled(prefs, platform));
        }
        values.put(ApolloSettings.STOCK_UI, true);
        for (VehiclePlatform platform : VehiclePlatform.values()) {
            assertEquals(
                    platform != VehiclePlatform.H97X && platform != VehiclePlatform.H97Y,
                    ApolloSettings.stockUiEnabled(prefs, platform));
        }
        values.put(ApolloSettings.STOCK_UI, false);
        assertEquals(false, ApolloSettings.stockUiEnabled(prefs, VehiclePlatform.H97C));
    }

    private static SharedPreferences preferences(Map<String, Object> values) {
        return (SharedPreferences)
                Proxy.newProxyInstance(
                        SharedPreferences.class.getClassLoader(),
                        new Class<?>[] {SharedPreferences.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getInt")
                                    || method.getName().equals("getBoolean")) {
                                return values.containsKey(args[0]) ? values.get(args[0]) : args[1];
                            }
                            throw new AssertionError(
                                    "Unexpected preference mutation/read: " + method.getName());
                        });
    }

    @Test
    public void legacyAutoIsOnlyModeFallbackAndDoesNotEnableMaster() {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences prefs = preferences(values);
        assertEquals(4, ApolloSettings.speedMode(prefs));
        values.put(ApolloSettings.CRUISE_SPEED_ADJUSTMENT, true);
        assertEquals(2, ApolloSettings.speedMode(prefs));
        assertEquals(false, prefs.getBoolean(ApolloSettings.SPEED_SIGNS, false));
        values.put(ApolloSettings.SPEED_MODE, 3);
        assertEquals(3, ApolloSettings.speedMode(prefs));
        values.put(ApolloSettings.SPEED_MODE, 4);
        assertEquals(4, ApolloSettings.speedMode(prefs));
        values.put(ApolloSettings.SPEED_MODE, 99);
        assertEquals(4, ApolloSettings.speedMode(prefs));
    }
}
