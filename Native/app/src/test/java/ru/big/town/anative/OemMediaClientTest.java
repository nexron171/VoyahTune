package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OemMediaClientTest {
    private static final String[] OEM_CLIENTS = {
        "com.qinggan.instrumentcard", "com.qinggan.app.launcher", "com.qinggan.app.islandapp"
    };

    @Test
    public void oemMediaSurfacesCanReadTheMirroredSession() {
        for (String client : OEM_CLIENTS) {
            assertTrue(client, OemMediaBrowserService.acceptsClient(client));
        }
    }

    @Test
    public void unrelatedPackagesCannotReadTheMirroredSession() {
        String[] rejected = {
            "", "com.qinggan.cluster", "com.qinggan.media", "com.android.bluetooth",
            "ru.big.town.anative", "com.qinggan.app.launcher.extra"
        };
        for (String client : rejected) {
            assertFalse(client, OemMediaBrowserService.acceptsClient(client));
        }
        assertFalse("null", OemMediaBrowserService.acceptsClient(null));
    }
}
