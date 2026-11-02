package ru.big.town.restoremode.appearance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class VisibleTileInvalidationTest {
    @Test
    public void viewportChangesInvalidateOnlyCurrentlyVisibleTilesIncludingPartialOnes() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            FrameLayout viewport = new FrameLayout(activity);
            FrameLayout tiles = new FrameLayout(activity);
            viewport.addView(tiles, new FrameLayout.LayoutParams(300, 100));
            CountingView first = addTile(tiles, 0);
            CountingView partial = addTile(tiles, 90);
            CountingView offscreen = addTile(tiles, 160);
            CountingView hidden = addTile(tiles, 10);
            hidden.setVisibility(View.INVISIBLE);
            activity.setContentView(viewport);
            viewport.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
            viewport.layout(0, 0, 100, 100);
            tiles.layout(0, 0, 300, 100);
            try (DashboardAppearance appearance = new DashboardAppearance(viewport,
                    activity.getSharedPreferences("visible-tiles", Context.MODE_PRIVATE))) {
                appearance.apply(tiles);
                reset(first, partial, offscreen, hidden);
                appearance.onViewportChanged();
                assertEquals(1, first.invalidations);
                assertEquals(1, partial.invalidations);
                assertEquals(0, offscreen.invalidations);
                assertEquals(0, hidden.invalidations);

                reset(first, partial, offscreen, hidden);
                appearance.apply(tiles);
                assertEquals(0, offscreen.invalidations);
                assertEquals(0, hidden.invalidations);

                viewport.scrollTo(100, 0);
                reset(first, partial, offscreen, hidden);
                appearance.onViewportChanged();
                assertEquals(0, first.invalidations);
                assertEquals(1, partial.invalidations);
                assertEquals(1, offscreen.invalidations);
                assertEquals(0, hidden.invalidations);

                tiles.removeAllViews();
                CountingView replacement = addTile(tiles, 120);
                appearance.apply(tiles);
                assertNotNull(replacement.getBackground());
            }
        }
    }

    private static CountingView addTile(FrameLayout tiles, int left) {
        CountingView tile = new CountingView(tiles.getContext());
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(50, 100);
        layout.leftMargin = left;
        tiles.addView(tile, layout);
        tile.layout(left, 0, left + 50, 100);
        return tile;
    }

    private static void reset(CountingView... tiles) {
        for (CountingView tile : tiles) {
            tile.invalidations = 0;
        }
    }

    private static final class CountingView extends View {
        int invalidations;

        CountingView(Context context) {
            super(context);
        }

        @Override
        public void invalidate() {
            invalidations++;
            super.invalidate();
        }
    }
}
