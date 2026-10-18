package ru.big.town.restoremode.widgets.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.content.SharedPreferences;

import org.junit.Test;

import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class MediaWidgetTest {
    @Test
    public void mediaSizesClampToOneByOneWithoutLosingAStoredHeight() {
        Map<String, Object> saved = new HashMap<>();
        SharedPreferences prefs = prefs(saved);
        assertArrayEquals(
                new int[] {3, 1},
                TileSizeStore.dimensions(
                        prefs,
                        MediaWidgetLayout.MEDIA,
                        MediaWidgetLayout.DEFAULT_WIDTH,
                        MediaWidgetLayout.DEFAULT_HEIGHT));

        TileSizeStore.setWidth(prefs, MediaWidgetLayout.MEDIA, 12);
        TileSizeStore.setHeight(prefs, MediaWidgetLayout.MEDIA, 5);
        assertArrayEquals(
                new int[] {4, 2},
                TileSizeStore.dimensions(prefs, MediaWidgetLayout.MEDIA, 3, 1));

        TileSizeStore.setWidth(prefs, MediaWidgetLayout.MEDIA, 1);
        assertArrayEquals(
                new int[] {1, 1},
                TileSizeStore.dimensions(prefs, MediaWidgetLayout.MEDIA, 3, 1));

        TileSizeStore.setWidth(prefs, MediaWidgetLayout.MEDIA, 2);
        TileSizeStore.setHeight(prefs, MediaWidgetLayout.MEDIA, 2);
        assertArrayEquals(
                new int[] {2, 2},
                TileSizeStore.dimensions(prefs, MediaWidgetLayout.MEDIA, 3, 1));
    }

    @Test
    public void progressAdvancesOnlyWhilePlayingAndNeverPassesDuration() {
        assertEquals(
                17_000L,
                MediaProgress.position(10_000L, 5_000L, 30_000L, true, 12_000L));
        assertEquals(
                10_000L,
                MediaProgress.position(10_000L, 5_000L, 30_000L, false, 12_000L));
        assertEquals(
                30_000L,
                MediaProgress.position(29_000L, 5_000L, 30_000L, true, 20_000L));
        assertEquals(
                10_000L,
                MediaProgress.position(10_000L, 0L, 30_000L, true, 20_000L));
    }

    @Test
    public void progressFractionsAndTimingStayInsideDisplayedTrack() {
        assertEquals(.5f, MediaProgress.fraction(15_000L, 30_000L), 0.0001f);
        assertEquals(0f, MediaProgress.fraction(15_000L, 0L), 0f);
        assertEquals(1f, MediaProgress.fraction(40_000L, 30_000L), 0f);
        assertEquals("1:05", MediaProgress.format(65_000L));
        assertEquals("1:00:05", MediaProgress.format(3_605_000L));
        assertEquals("0:00", MediaProgress.format(-1L));
    }

    private SharedPreferences prefs(Map<String, Object> values) {
        final Object[] editor = new Object[1];
        editor[0] =
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {SharedPreferences.Editor.class},
                        (proxy, method, args) -> {
                            if (method.getName().startsWith("put")) {
                                values.put((String) args[0], args[1]);
                                return editor[0];
                            }
                            return null;
                        });
        return (SharedPreferences)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {SharedPreferences.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("edit")) {
                                return editor[0];
                            }
                            if (method.getName().startsWith("get") && args.length == 2) {
                                return values.getOrDefault((String) args[0], args[1]);
                            }
                            return null;
                        });
    }
}
