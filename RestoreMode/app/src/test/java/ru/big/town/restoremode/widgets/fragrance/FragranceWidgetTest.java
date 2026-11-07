package ru.big.town.restoremode.widgets.fragrance;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.app.Presentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDisplayManager;

import ru.big.town.common.FragranceWidgetProtocol;
import ru.big.town.common.FragranceWidgetProtocol.Snapshot;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;
import ru.big.town.restoremode.integration.GlobalVars;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class FragranceWidgetTest {
    @Test
    public void expandedArtworkFillsFromBottomRightWithoutDistortingControls() {
        for (FragranceWidgetLayout size : FragranceWidgetLayout.values()) {
            if (!size.expanded()) continue;
            FragranceWidgetView tile =
                    new FragranceWidgetView(
                            RuntimeEnvironment.getApplication(), size, (command, value) -> {});
            tile.update(new Snapshot(2, 1, 2, 1), false, "");
            for (int[] bounds :
                    new int[][] {{size.width, size.height}, {size.width + 130, size.height - 60}}) {
                int width = bounds[0], height = bounds[1];
                tile.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                tile.layout(0, 0, width, height);
                View background = tile.getChildAt(0);
                View mist = tile.getChildAt(1);
                assertEquals(
                        1920f / 720, background.getWidth() / (float) background.getHeight(), .015f);
                assertEquals(width, background.getRight());
                assertEquals(height, background.getBottom());
                assertTrue(background.getLeft() <= 0 && background.getTop() <= 0);
                float scale = background.getHeight() / 720f;
                assertEquals(background.getLeft() + 1265 * scale, mist.getLeft(), 1f);
                assertEquals(background.getTop() + 60 * scale, mist.getTop(), 1f);
                assertEquals(397f / 424, mist.getWidth() / (float) mist.getHeight(), .015f);
                View scent = control(tile, "Свежесть бамбука");
                assertEquals(scent.getWidth(), scent.getHeight());
                View power = control(tile, "Выключить ароматизатор");
                assertEquals(power.getWidth(), power.getHeight());
                View segment = control(tile, "30 мин");
                assertEquals(segment.getWidth(), segment.getHeight() * 2, 1f);
                View level = control(tile, "Средняя");
                assertTrue(
                        level.getBottom()
                                < segment.getTop()
                                        - 20
                                                * Math.min(
                                                        width / (float) size.width,
                                                        height / (float) size.height));
                assertTrue(segment.getBottom() <= height);
                TextView unlimited = (TextView) control(tile, "Безлимит");
                assertEquals(1, unlimited.getLineCount());
                assertEquals(0, unlimited.getLayout().getEllipsisCount(0));
            }
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    public void leftHalfGradientDimsArtworkButNotControls() {
        FragranceWidgetView tile =
                new FragranceWidgetView(
                        RuntimeEnvironment.getApplication(),
                        FragranceWidgetLayout.PANEL_4X3,
                        (command, value) -> {});
        tile.update(new Snapshot(2, 1, 2, 1), false, "");
        ((ImageView) tile.getChildAt(0)).setImageDrawable(new ColorDrawable(Color.WHITE));
        View scent = control(tile, "Свежесть бамбука");
        scent.setBackgroundColor(Color.WHITE);
        tile.measure(
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(361, View.MeasureSpec.EXACTLY));
        tile.layout(0, 0, 540, 361);
        Bitmap image = Bitmap.createBitmap(540, 361, Bitmap.Config.ARGB_8888);
        tile.draw(new Canvas(image));
        int left = Color.red(image.getPixel(8, 170));
        int middle = Color.red(image.getPixel(135, 170));
        assertTrue(left < middle);
        assertTrue(middle < 200);
        assertEquals(Color.WHITE, image.getPixel(405, 170));
        assertEquals(Color.WHITE, image.getPixel(scent.getLeft() + 4, scent.getTop() + 4));
    }

    @Test
    public void onlySevenSizesAreSelectableAndPersistIndependently() {
        Application app = RuntimeEnvironment.getApplication();
        SharedPreferences driver = app.getSharedPreferences("driver", Context.MODE_PRIVATE);
        SharedPreferences passenger = app.getSharedPreferences("passenger", Context.MODE_PRIVATE);
        String id = "fragranceWidget";
        int count = 0;
        for (int w = 1; w <= 12; w++)
            for (int h = 1; h <= 5; h++) {
                if (TileSizeStore.supportsSize(id, w, h)) count++;
            }
        assertEquals(7, count);
        assertFalse(TileSizeStore.supportsSize(id, 4, 2));
        for (int[] size : new int[][] {{1, 1}, {2, 1}, {2, 2}, {4, 3}, {4, 4}, {5, 3}, {5, 4}}) {
            TileSizeStore.setSize(driver, id, size[0], size[1]);
            assertArrayEquals(size, TileSizeStore.dimensions(driver, id, 2, 2));
        }
        assertArrayEquals(new int[] {2, 2}, TileSizeStore.dimensions(passenger, id, 2, 2));
    }

    @Test
    public void savedFourByTwoResolvesToFourByThreeAndRemainsResizable() {
        SharedPreferences preferences =
                RuntimeEnvironment.getApplication()
                        .getSharedPreferences("legacyFragrance", Context.MODE_PRIVATE);
        String id = FragranceWidgetLayout.ID;
        preferences.edit().putInt("tileWidth_" + id, 4).putInt("tileHeight_" + id, 2).apply();
        assertArrayEquals(new int[] {4, 3}, TileSizeStore.dimensions(preferences, id, 2, 2));
        TileSizeStore.setHeight(preferences, id, 4);
        assertArrayEquals(new int[] {4, 4}, TileSizeStore.dimensions(preferences, id, 2, 2));
        TileSizeStore.setWidth(preferences, id, 5);
        assertArrayEquals(new int[] {5, 4}, TileSizeStore.dimensions(preferences, id, 2, 2));
        TileSizeStore.setHeight(preferences, id, 3);
        assertArrayEquals(new int[] {5, 3}, TileSizeStore.dimensions(preferences, id, 2, 2));
    }

    @Test
    public void selectingActiveScentTurnsItOffAndControlsWaitForConfirmedValues() {
        List<Integer> commands = new ArrayList<>();
        FragranceWidgetView view =
                new FragranceWidgetView(
                        RuntimeEnvironment.getApplication(),
                        FragranceWidgetLayout.PANEL_4X3,
                        (command, value) -> {
                            commands.add(command);
                            commands.add(value);
                        });
        view.update(new Snapshot(2, 1, 2, 0), false, "");
        View active = control(view, "Свежесть бамбука");
        active.performClick();
        control(view, "Пионовая красота").performClick();
        control(view, "60 мин").performClick();
        assertEquals(
                List.of(
                        FragranceWidgetProtocol.POWER,
                        1,
                        FragranceWidgetProtocol.SCENT,
                        2,
                        FragranceWidgetProtocol.TIMER,
                        2),
                commands);
        assertTrue(active.isSelected());
        view.update(new Snapshot(1, 2, 2, 0), false, "");
        assertFalse(active.isSelected());
        assertFalse(control(view, "60 мин").isEnabled());
        assertTrue(control(view, "Пионовая красота").isEnabled());
        view.update(Snapshot.UNKNOWN, false, "");
        assertFalse(active.isEnabled());
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    public void miniWaitsForNativeAndReleasesItsSubscriptionWhenHidden() throws Exception {
        List<Message> requests = new ArrayList<>();
        GlobalVars.isBound = true;
        GlobalVars.serviceMessenger =
                new Messenger(
                        new Handler(
                                Looper.getMainLooper(),
                                message -> {
                                    requests.add(Message.obtain(message));
                                    return true;
                                }));
        Activity screen = Robolectric.buildActivity(Activity.class).setup().visible().get();
        FragranceWidgetDashboard dashboard = new FragranceWidgetDashboard(screen);
        try {
            FragranceWidgetView mini = dashboard.create(FragranceWidgetLayout.MINI);
            dashboard.watch(true);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(requests.isEmpty());
            FrameLayout grid = new FrameLayout(screen);
            screen.setContentView(grid);
            grid.addView(mini, new FrameLayout.LayoutParams(160, 120));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64));
            assertTrue(mini.isVisibleTile());
            Message first = requests.get(0);
            assertEquals(FragranceWidgetProtocol.WATCH, first.what);
            reply(first, new Snapshot(1, 2, 1, 1));
            mini.performClick();
            mini.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, requests.size());
            assertEquals(FragranceWidgetProtocol.POWER, requests.get(1).what);
            assertEquals(2, requests.get(1).arg1);
            reply(first, new Snapshot(2, 2, 1, 1));
            assertTrue(mini.getContentDescription().toString().contains("Включено"));
            mini.setVisibility(View.GONE);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(FragranceWidgetProtocol.UNWATCH, requests.get(2).what);
            mini.setVisibility(View.VISIBLE);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64));
            assertEquals(FragranceWidgetProtocol.WATCH, requests.get(3).what);
            reply(first, new Snapshot(2, 2, 1, 1));
            assertTrue(mini.getContentDescription().toString().contains("Нет данных"));
        } finally {
            dashboard.close();
            GlobalVars.isBound = false;
            GlobalVars.serviceMessenger = null;
        }
    }

    @Test
    public void cardOpensStockFragranceOnItsOwnDisplay() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        int displayId = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi");
        Presentation passenger =
                new Presentation(
                        activity,
                        activity.getSystemService(DisplayManager.class).getDisplay(displayId));
        List<Intent> launches = new ArrayList<>();
        List<Bundle> options = new ArrayList<>();
        Context recording =
                new ContextWrapper(passenger.getContext()) {
                    @Override
                    public void startActivity(Intent intent, Bundle launchOptions) {
                        launches.add(intent);
                        options.add(launchOptions);
                    }
                };
        FragranceWidgetView card =
                new FragranceWidgetView(
                        recording,
                        FragranceWidgetLayout.CARD,
                        (command, value) ->
                                fail("Opening the OEM screen must not change the scent"));
        passenger.setContentView(card);
        passenger.show();
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64));
            card.performClick();
            assertEquals("com.qinggan.app.vehicle.SHOW_FRAGRANCE", launches.get(0).getAction());
            assertEquals("com.qinggan.app.vehicle", launches.get(0).getPackage());
            assertEquals(displayId, options.get(0).getInt("android.activity.launchDisplayId", -1));
        } finally {
            passenger.dismiss();
            ShadowDisplayManager.removeDisplay(displayId);
        }
    }

    private static void reply(Message watch, Snapshot state) throws Exception {
        Message response = Message.obtain(null, FragranceWidgetProtocol.STATE, 0, watch.arg2);
        response.setData(state.bundle());
        watch.replyTo.send(response);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static View control(ViewGroup group, String label) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (label.contentEquals(
                    child.getContentDescription() == null ? "" : child.getContentDescription()))
                return child;
            if (child instanceof ViewGroup) {
                View match = control((ViewGroup) child, label);
                if (match != null) return match;
            }
        }
        return null;
    }
}
