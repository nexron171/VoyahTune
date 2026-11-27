package ru.big.town.restoremode.widgets.suspension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import ru.big.town.restoremode.R;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SuspensionMaintenanceTileTest {

    @Test
    public void badgeFitsBothStatesInRegularAndCompactTiles() {
        View tile = LayoutInflater.from(RuntimeEnvironment.getApplication())
                .inflate(R.layout.tile_suspension_maintenance, null);
        TextView title = tile.findViewById(R.id.suspensionMaintenanceTitle);
        TextView badge = tile.findViewById(R.id.suspensionMaintenanceBadge);

        for (int[] size : new int[][] {{284, 128}, {250, 100}, {200, 80}}) {
            for (boolean active : new boolean[] {false, true}) {
                badge.setText(active ? "активно" : "не активно");
                badge.setBackgroundResource(active ? R.drawable.pill_active : R.drawable.pill_inactive);
                tile.measure(
                        View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
                tile.layout(0, 0, size[0], size[1]);

                assertEquals(1, badge.getLineCount());
                int badgeContentHeight = badge.getLayout().getHeight()
                        + badge.getCompoundPaddingTop() + badge.getCompoundPaddingBottom();
                assertTrue("Clipped badge at " + size[0] + "x" + size[1] + ": " + badge.getText(),
                        badge.getHeight() >= badgeContentHeight);
                assertTrue("Clipped title at " + size[0] + "x" + size[1] + ": "
                                + title.getLayout().getHeight() + "px in " + title.getHeight() + "px",
                        title.getHeight() >= title.getLayout().getHeight());
                assertTrue(badge.getTop() >= title.getBottom());
                assertTrue(badge.getBottom() <= tile.getHeight() - tile.getPaddingBottom());
            }
        }
    }
}
