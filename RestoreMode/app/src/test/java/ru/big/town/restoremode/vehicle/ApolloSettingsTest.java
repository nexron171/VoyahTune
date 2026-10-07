package ru.big.town.restoremode.vehicle;

import static org.junit.Assert.assertEquals;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class ApolloSettingsTest {
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
