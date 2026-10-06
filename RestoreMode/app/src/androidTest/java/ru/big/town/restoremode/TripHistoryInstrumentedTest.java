package ru.big.town.restoremode;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TripHistoryInstrumentedTest {
    private Bitmap snapshot(Instrumentation i, TripHistoryActivity a) {
        AtomicReference<Bitmap> image = new AtomicReference<>();
        i.runOnMainSync(() -> {
            View decor = a.getWindow().getDecorView();
            Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(bitmap)); image.set(bitmap);
        });
        return image.get();
    }
    private void drawn(Instrumentation i, TripHistoryActivity a) throws Exception {
        CountDownLatch frame = new CountDownLatch(1);
        i.runOnMainSync(() -> a.getWindow().getDecorView().postOnAnimation(
                () -> a.getWindow().getDecorView().postOnAnimation(frame::countDown)));
        assertTrue("Card layout drawn", frame.await(5, TimeUnit.SECONDS));
    }
    private int count(View v, Class<?> type) {
        int total = type.isInstance(v) ? 1 : 0;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)v).getChildCount(); i++) total += count(((ViewGroup)v).getChildAt(i), type);
        return total;
    }
    private <T extends View> T first(View v, Class<T> type) {
        if (type.isInstance(v)) return type.cast(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)v).getChildCount(); i++) {
            T child = first(((ViewGroup)v).getChildAt(i), type);
            if (child != null) return child;
        }
        return null;
    }
    private View content(TripHistoryActivity activity) {
        // R8 can remove R fields that are referenced only by instrumentation.
        int id = activity.getResources().getIdentifier("tripContent", "id", activity.getPackageName());
        assertTrue(id != 0);
        return activity.findViewById(id);
    }
    private void touch(TripMapView map, long down, long offset, int action, int[] ids, float... positions) {
        float density = map.getResources().getDisplayMetrics().density;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = positions[i * 2] * density; coordinates[i].y = positions[i * 2 + 1] * density;
            coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down, down + offset, action, ids.length, properties, coordinates,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { assertTrue(map.dispatchTouchEvent(event)); } finally { event.recycle(); }
    }
    private double[] renderedMarkers(TripMapView map) {
        Bitmap bitmap = Bitmap.createBitmap(map.getWidth(), map.getHeight(), Bitmap.Config.ARGB_8888);
        map.draw(new Canvas(bitmap));
        try {
            assertEquals("Top-left corner is rounded", 0, Color.alpha(bitmap.getPixel(0, 0)));
            assertEquals("Top-right corner is rounded", 0, Color.alpha(bitmap.getPixel(bitmap.getWidth() - 1, 0)));
            assertEquals("Bottom-left corner is rounded", 0, Color.alpha(bitmap.getPixel(0, bitmap.getHeight() - 1)));
            assertEquals("Bottom-right corner is rounded", 0, Color.alpha(bitmap.getPixel(bitmap.getWidth() - 1, bitmap.getHeight() - 1)));
            assertTrue(Color.alpha(bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2)) > 0);
            double[] result = new double[4]; int[] counts = new int[2];
            for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
                int color = bitmap.getPixel(x, y), marker = color == 0xff64d8ff ? 0 : color == 0xfff04444 ? 1 : -1;
                if (marker >= 0) { result[marker * 2] += x; result[marker * 2 + 1] += y; counts[marker]++; }
            }
            float density = map.getResources().getDisplayMetrics().density;
            for (int i = 0; i < 2; i++) {
                assertTrue("Route endpoint remains visible", counts[i] > 20);
                result[i * 2] /= counts[i] * density; result[i * 2 + 1] /= counts[i] * density;
            }
            return result;
        } finally { bitmap.recycle(); }
    }
    @Test public void pinchKeepsFocusRoundsCornersAndContinuesWithRemainingFinger() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            List<double[]> track = new ArrayList<>();
            track.add(new double[]{0, 10000, 55.75, 37.61, 0, 0, 0, 1});
            track.add(new double[]{.1, 20000, 55.7508, 37.61, 100, 10000, 70, 0});
            TripMapView map = new TripMapView(instrumentation.getTargetContext(), track);
            int side = Math.round(500 * map.getResources().getDisplayMetrics().density);
            map.layout(0, 0, side, side);
            AtomicInteger clicks = new AtomicInteger(); map.setOnClickListener(v -> clicks.incrementAndGet());
            double[] before = renderedMarkers(map);
            long down = SystemClock.uptimeMillis();
            // Use a span beyond Android's minimum scaling span, including several moves
            // after recognition. A gesture ending at that threshold has not scaled yet.
            touch(map, down, 0, MotionEvent.ACTION_DOWN, new int[]{0}, 220, 250);
            touch(map, down, 10, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[]{0, 1}, 220, 250, 380, 250);
            touch(map, down, 20, MotionEvent.ACTION_MOVE, new int[]{0, 1}, 190, 250, 410, 250);
            touch(map, down, 30, MotionEvent.ACTION_MOVE, new int[]{0, 1}, 170, 250, 430, 250);
            touch(map, down, 40, MotionEvent.ACTION_MOVE, new int[]{0, 1}, 150, 250, 450, 250);
            touch(map, down, 50, MotionEvent.ACTION_MOVE, new int[]{0, 1}, 130, 250, 470, 250);
            double[] enlarged = renderedMarkers(map);
            double ratio = Math.hypot(enlarged[2] - enlarged[0], enlarged[3] - enlarged[1])
                    / Math.hypot(before[2] - before[0], before[3] - before[1]);
            assertTrue("Spreading fingers enlarges the route smoothly: " + ratio, ratio > 1.3);
            for (int i = 0; i < 2; i++) {
                assertEquals("Longitude under the fingers stays anchored", 300 + (before[i * 2] - 300) * ratio, enlarged[i * 2], 2);
                assertEquals("Latitude under the fingers stays anchored", 250 + (before[i * 2 + 1] - 250) * ratio, enlarged[i * 2 + 1], 2);
            }
            touch(map, down, 60, MotionEvent.ACTION_POINTER_UP, new int[]{0, 1}, 130, 250, 470, 250);
            touch(map, down, 70, MotionEvent.ACTION_MOVE, new int[]{1}, 475, 256);
            touch(map, down, 80, MotionEvent.ACTION_UP, new int[]{1}, 475, 256);
            double[] panned = renderedMarkers(map);
            for (int i = 0; i < 2; i++) {
                assertEquals("Remaining finger pans without a jump", enlarged[i * 2] + 5, panned[i * 2], 2);
                assertEquals(enlarged[i * 2 + 1] + 6, panned[i * 2 + 1], 2);
            }
            assertEquals("Pinching does not click a segment or attribution", 0, clicks.get());
            down += 100;
            touch(map, down, 0, MotionEvent.ACTION_DOWN, new int[]{0}, 20, 250);
            touch(map, down, 10, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[]{0, 1}, 20, 250, 380, 250);
            touch(map, down, 20, MotionEvent.ACTION_MOVE, new int[]{0, 1}, 40, 250, 360, 250);
            touch(map, down, 30, MotionEvent.ACTION_MOVE, new int[]{0, 1}, 60, 250, 340, 250);
            touch(map, down, 40, MotionEvent.ACTION_MOVE, new int[]{0, 1}, 80, 250, 320, 250);
            touch(map, down, 50, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[]{0, 1}, 80, 250, 320, 250);
            touch(map, down, 60, MotionEvent.ACTION_UP, new int[]{0}, 80, 250);
            double[] reduced = renderedMarkers(map);
            assertTrue("Pinching fingers together reduces the route", Math.hypot(reduced[2] - reduced[0], reduced[3] - reduced[1])
                    < Math.hypot(panned[2] - panned[0], panned[3] - panned[1]) * .8);
            down += 100;
            touch(map, down, 0, MotionEvent.ACTION_DOWN, new int[]{0}, 470, 65);
            touch(map, down, 10, MotionEvent.ACTION_UP, new int[]{0}, 470, 65);
            assertArrayEquals("Former zoom button area is a normal map tap", reduced, renderedMarkers(map), 1);
            assertEquals(1, clicks.get());
            down += 100;
            touch(map, down, 0, MotionEvent.ACTION_DOWN, new int[]{0}, 200, 480);
            touch(map, down, 10, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[]{0, 1}, 200, 480, 220, 480);
            touch(map, down, 20, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[]{0, 1}, 200, 480, 220, 480);
            touch(map, down, 30, MotionEvent.ACTION_UP, new int[]{0}, 200, 480);
            assertEquals("Even a stationary two-finger gesture is not an attribution tap", 1, clicks.get());
        });
    }
    @Test public void longRouteKeepsEverySpeedColorVisibleAtOverviewScale() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        List<double[]> track = new ArrayList<>();
        long time = 10000;
        for (int i = 0; i <= 180; i++) {
            double speed = new double[]{20, 40, 70, 100, 150}[(i / 3) % 5];
            double duration = i == 0 ? 0 : 360000 / speed;
            time += Math.round(duration);
            track.add(new double[]{i / 10d, time, 55.75 + i * .00082, 37.61,
                    i == 0 ? 0 : 100, duration, i == 0 ? 0 : speed, i == 0 ? 1 : 0});
        }
        AtomicReference<Bitmap> image = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            TripMapView map = new TripMapView(instrumentation.getTargetContext(), track);
            map.layout(0, 0, 320, 320);
            Bitmap bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
            map.draw(new Canvas(bitmap)); image.set(bitmap);
        });
        Bitmap bitmap = image.get();
        try {
            for (int color : TripHistoryPresentation.COLORS) {
                int pixels = 0;
                for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++)
                    if (bitmap.getPixel(x, y) == color) pixels++;
                assertTrue("Speed color visible on a complete 18 km route: " + Integer.toHexString(color), pixels >= 16);
            }
        } finally { bitmap.recycle(); }
    }
    @Test public void recordedTripShowsLevelsChartsAndColoredTrackWhileMissingTrackHasNoMap() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TripHistoryActivity activity = (TripHistoryActivity)instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), TripHistoryActivity.class).putExtra("tripsJson", "[]").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONObject summary = new JSONObject().put("start", 1791194400000L).put("end", 1791198000000L)
                .put("durationMs", 1800000).put("distanceKm", 1.9).put("startSoc", 80).put("endSoc", 76)
                .put("startFuel", 50).put("endFuel", 49).put("electricityKwh", 1.72).put("fuelLiters", .56);
        List<double[]> energy = new ArrayList<>(), track = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            energy.add(new double[]{i / 10d, 10000 + i * 10000, 80 - i * .2, 50 - i * .05, i * .2, i * .05, i / 10d, i / 10d, i == 0 ? 1 : 0});
            double speed = new double[]{20, 40, 70, 100, 150}[i % 5];
            track.add(new double[]{i / 10d, 10000 + i * 10000, 55.75 + i * .0007, 37.61 + Math.sin(i / 3d) * .002,
                    i == 0 ? 0 : 100, i == 0 ? 0 : 360000 / speed, speed, i == 0 ? 1 : 0});
        }
        try {
            instrumentation.runOnMainSync(() -> {
                activity.renderTripLog(new JSONArray().put(summary).toString());
                assertTrue(activity.findViewById(R.id.tripDeleteAll).isEnabled());
                TextView electricRate = activity.findViewById(R.id.tripElectricityPer100);
                TextView fuelRate = activity.findViewById(R.id.tripFuelPer100);
                assertEquals("~90,5 кВт·ч", electricRate.getText().toString());
                assertEquals("~29,5 л", fuelRate.getText().toString());
                assertEquals(EnergyWidgetStyle.GREEN, electricRate.getCurrentTextColor());
                assertEquals(EnergyWidgetStyle.BLUE, fuelRate.getCurrentTextColor());
                TextView metrics = activity.findViewById(R.id.tripSummary); assertTrue(metrics.getText().toString().contains("80"));
                TextView title = activity.findViewById(R.id.tripTitle);
                assertEquals(1, title.getMaxLines()); assertTrue(title.getText().toString().contains("30 мин"));
                assertTrue(title.getText().toString().contains("1.9 км") || title.getText().toString().contains("1,9 км"));
                View body = activity.findViewById(R.id.tripBody), header = activity.findViewById(R.id.tripHeader);
                LinearLayout details = activity.findViewById(R.id.tripDetails), route = activity.findViewById(R.id.tripRoute);
                assertEquals("New cards start collapsed", View.GONE, body.getVisibility());
                assertEquals("Samples load only after expansion", 0, details.getChildCount());
                activity.showDetails(summary, energy, Collections.emptyList(), details, route);
                assertTrue(header.performClick());
                assertEquals(View.VISIBLE, body.getVisibility());
                assertEquals(View.GONE, route.getVisibility());
                assertEquals(0, count(body, TripMapView.class)); assertEquals(2, count(body, TripChartView.class));
            });
            instrumentation.waitForIdleSync();
            drawn(instrumentation, activity);
            instrumentation.runOnMainSync(() -> {
                assertEquals("Without GPS the content fills the card",
                        activity.findViewById(R.id.tripBody).getWidth(), content(activity).getWidth());
                LinearLayout details = activity.findViewById(R.id.tripDetails);
                TripChartView chart = first(details, TripChartView.class);
                int metricsWidth = Math.round(200 * activity.getResources().getDisplayMetrics().density);
                int gap = Math.round(24 * activity.getResources().getDisplayMetrics().density);
                assertEquals("Without GPS charts use the whole width after the metrics column",
                        content(activity).getWidth() - metricsWidth - gap, chart.getWidth());
            });
            Bitmap screenshot = snapshot(instrumentation, activity);
            assertNotNull("Native UI screenshot", screenshot);
            ContentValues image = new ContentValues(); image.put(MediaStore.Images.Media.DISPLAY_NAME, "trip-history-test.png");
            image.put(MediaStore.Images.Media.MIME_TYPE, "image/png"); image.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VoyahTune Tests");
            Uri saved = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, image);
            assertNotNull(saved);
            try (java.io.OutputStream out = activity.getContentResolver().openOutputStream(saved)) { screenshot.compress(Bitmap.CompressFormat.PNG, 100, out); }
            instrumentation.runOnMainSync(() -> {
                View body = activity.findViewById(R.id.tripBody), header = activity.findViewById(R.id.tripHeader);
                assertTrue(header.performClick()); assertEquals("Header collapses the card", View.GONE, body.getVisibility());
                activity.showDetails(summary, energy, track, activity.findViewById(R.id.tripDetails), activity.findViewById(R.id.tripRoute));
                assertTrue(header.performClick()); assertEquals(View.VISIBLE, body.getVisibility());
                assertEquals(1, count(body, TripMapView.class)); assertEquals(2, count(body, TripChartView.class));
            });
            instrumentation.waitForIdleSync();
            drawn(instrumentation, activity);
            instrumentation.runOnMainSync(() -> {
                View content = content(activity);
                TripMapView map = first(activity.findViewById(R.id.tripBody), TripMapView.class);
                assertNotNull(map); assertTrue(map.getWidth() > 0);
                assertEquals("Route map is square", map.getWidth(), map.getHeight());
                int[] contentPosition = new int[2], mapPosition = new int[2];
                content.getLocationOnScreen(contentPosition); map.getLocationOnScreen(mapPosition);
                assertTrue("Map is to the right of all trip content", mapPosition[0] >= contentPosition[0] + content.getWidth());
                ArrayList<View> charts = new ArrayList<>();
                activity.findViewById(R.id.tripBody).findViewsWithText(charts, "График", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                assertEquals(2, charts.size());
                int[] levelsPosition = new int[2], ratesPosition = new int[2];
                charts.get(0).getLocationOnScreen(levelsPosition); charts.get(1).getLocationOnScreen(ratesPosition);
                assertTrue("Charts are stacked vertically", ratesPosition[1] >= levelsPosition[1] + charts.get(0).getHeight());
                assertEquals(levelsPosition[0], ratesPosition[0]);
                assertEquals("Charts have the same full column width", charts.get(0).getWidth(), charts.get(1).getWidth());
                View metrics = (View)activity.findViewById(R.id.tripElectricity).getParent();
                int[] metricsPosition = new int[2]; metrics.getLocationOnScreen(metricsPosition);
                int gap = Math.round(24 * activity.getResources().getDisplayMetrics().density);
                assertEquals("Charts start to the right of the metrics", metricsPosition[0] + metrics.getWidth() + gap, levelsPosition[0]);
                assertEquals("Charts fill all available space before the map",
                        content.getWidth() - metrics.getWidth() - gap, charts.get(0).getWidth());
                assertEquals(metricsPosition[1], levelsPosition[1]);
                int[] electricPosition = new int[2], fuelPosition = new int[2];
                View electric = activity.findViewById(R.id.tripElectricity);
                electric.getLocationOnScreen(electricPosition); activity.findViewById(R.id.tripFuel).getLocationOnScreen(fuelPosition);
                assertTrue("Fuel follows electricity vertically", fuelPosition[1] >= electricPosition[1] + electric.getHeight());
                int[] electricRatePosition = new int[2], fuelRatePosition = new int[2];
                View electricRate = activity.findViewById(R.id.tripElectricityPer100), fuelRate = activity.findViewById(R.id.tripFuelPer100);
                electricRate.getLocationOnScreen(electricRatePosition); fuelRate.getLocationOnScreen(fuelRatePosition);
                assertTrue("Average fuel follows average electricity", fuelRatePosition[1] >= electricRatePosition[1] + electricRate.getHeight());
                assertTrue("Average readings precede trip totals", electricPosition[1] >= fuelRatePosition[1] + fuelRate.getHeight());
            });
            screenshot = snapshot(instrumentation, activity); assertNotNull(screenshot);
            image.put(MediaStore.Images.Media.DISPLAY_NAME, "trip-history-map-test.png");
            saved = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, image); assertNotNull(saved);
            try (java.io.OutputStream out = activity.getContentResolver().openOutputStream(saved)) { screenshot.compress(Bitmap.CompressFormat.PNG, 100, out); }
            instrumentation.runOnMainSync(() -> {
                activity.findViewById(R.id.tripHeader).performClick();
                assertEquals(View.GONE, activity.findViewById(R.id.tripBody).getVisibility());
                activity.findViewById(R.id.tripHeader).performClick();
                assertEquals("Reopening reuses the recorded map", 1, count(activity.findViewById(R.id.tripBody), TripMapView.class));
                activity.renderTripLog("[]");
                assertFalse("Empty history disables bulk deletion", activity.findViewById(R.id.tripDeleteAll).isEnabled());
            });
        } finally { instrumentation.runOnMainSync(activity::finish); instrumentation.waitForIdleSync(); }
    }
}
