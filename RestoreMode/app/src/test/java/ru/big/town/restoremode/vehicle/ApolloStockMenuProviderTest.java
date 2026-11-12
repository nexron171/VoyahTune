package ru.big.town.restoremode.vehicle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import ru.big.town.common.VehiclePlatform;
import ru.big.town.restoremode.integration.config.SettingsContentProvider;

@RunWith(RobolectricTestRunner.class)
@Config(
        sdk = 30,
        application = Application.class,
        shadows = ApolloStockMenuProviderTest.VehicleIdentity.class)
public class ApolloStockMenuProviderTest {
    @Test
    public void activationSurvivesProviderRestartAndCanBeDisabled() {
        verifyActivation(VehiclePlatform.H97C, 1);
    }

    @Test
    public void xIgnoresActivationEvenWhenSaved() {
        verifyActivation(VehiclePlatform.H97X, 0);
    }

    @Test
    public void yIgnoresActivationEvenWhenSaved() {
        verifyActivation(VehiclePlatform.H97Y, 0);
    }

    @Implements(value = VehiclePlatform.class, isInAndroidSdk = false)
    public static class VehicleIdentity {
        static VehiclePlatform platform;

        @Implementation
        protected static VehiclePlatform current() {
            return platform;
        }
    }

    private void verifyActivation(VehiclePlatform platform, int expected) {
        VehicleIdentity.platform = platform;
        SharedPreferences preferences =
                RuntimeEnvironment.getApplication().getSharedPreferences("DrivePreferences", 0);
        preferences.edit().clear().putBoolean(ApolloSettings.STOCK_UI, true).commit();
        SettingsContentProvider provider = createProvider();
        assertTrue(preferences.getBoolean(ApolloSettings.STOCK_UI, false));
        assertActivation(provider, expected);
        provider = createProvider();
        assertTrue(preferences.getBoolean(ApolloSettings.STOCK_UI, false));
        assertActivation(provider, expected);

        preferences.edit().putBoolean(ApolloSettings.STOCK_UI, false).commit();
        assertActivation(provider, 0);
    }

    private SettingsContentProvider createProvider() {
        return Robolectric.buildContentProvider(SettingsContentProvider.class).create().get();
    }

    private void assertActivation(SettingsContentProvider provider, int expected) {
        try (Cursor cursor =
                provider.query(
                        Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                        null,
                        null,
                        null,
                        null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(28, cursor.getColumnIndex(ApolloSettings.STOCK_UI));
            assertEquals(expected, cursor.getInt(28));
            assertEquals(0, cursor.getInt(cursor.getColumnIndex(ApolloSettings.TLC)));
        }
    }
}
