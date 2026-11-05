package ru.big.town.restoremode.widgets.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MediaSourceCatalogTest {
    @Test
    public void installedPlayersGoBeforeSystemOnesAlphabetically() {
        Map<String, String> labels = new HashMap<>();
        labels.put("com.qinggan.media", "Штатный плеер");
        labels.put("com.spotify.music", "Spotify");
        labels.put("com.android.music", "Музыка");
        labels.put("ru.yandex.music", "Яндекс Музыка");

        List<String> ordered =
                MediaSourceCatalog.order(
                        Arrays.asList(
                                "com.qinggan.media",
                                "com.spotify.music",
                                "com.android.music",
                                "ru.yandex.music"),
                        labels);

        assertEquals(
                Arrays.asList(
                        "com.spotify.music",
                        "ru.yandex.music",
                        "com.android.music",
                        "com.qinggan.media"),
                ordered);
    }

    @Test
    public void missingLabelsFallBackToPackageNames() {
        List<String> ordered =
                MediaSourceCatalog.order(
                        Arrays.asList("com.z.player", "com.a.player", "com.m.player"),
                        new HashMap<>());

        assertEquals(
                Arrays.asList("com.a.player", "com.m.player", "com.z.player"), ordered);
    }

    @Test
    public void knownLabelDecidesThePositionOfItsPackage() {
        Map<String, String> labels = new HashMap<>();
        labels.put("com.b.player", "Alpha Player");

        List<String> ordered =
                MediaSourceCatalog.order(
                        Arrays.asList("com.a.player", "com.b.player"), labels);

        assertEquals(Arrays.asList("com.b.player", "com.a.player"), ordered);

        labels.put("com.b.player", "Zeta Player");
        ordered =
                MediaSourceCatalog.order(Arrays.asList("com.a.player", "com.b.player"), labels);
        assertEquals(Arrays.asList("com.a.player", "com.b.player"), ordered);
    }

    @Test
    public void infrastructureAndOwnPackagesAreNotOffered() {
        assertTrue(MediaSourceCatalog.isHidden("ru.big.town.restoremode", "ru.big.town.restoremode"));
        assertTrue(MediaSourceCatalog.isHidden("ru.big.town.anative", "ru.big.town.restoremode"));
        assertTrue(MediaSourceCatalog.isHidden("ru.big.town.updater", "ru.big.town.restoremode"));
        assertTrue(MediaSourceCatalog.isHidden("com.android.systemui", "ru.big.town.restoremode"));
        assertFalse(MediaSourceCatalog.isHidden("com.spotify.music", "ru.big.town.restoremode"));
    }

    @Test
    public void cloudSpaceIsNotOfferedAsAMediaSource() {
        assertTrue(MediaSourceCatalog.isHidden("com.huawei.hidisk.fa", "ru.big.town.restoremode"));
    }

    @Test
    public void oemVendorPackagesCountAsSystem() {
        assertTrue(MediaSourceCatalog.isSystemPackage("com.qinggan.media"));
        assertTrue(MediaSourceCatalog.isSystemPackage("com.android.music"));
        assertTrue(MediaSourceCatalog.isSystemPackage("com.tencent.qqmusic"));
        assertFalse(MediaSourceCatalog.isSystemPackage("com.spotify.music"));
    }
}
