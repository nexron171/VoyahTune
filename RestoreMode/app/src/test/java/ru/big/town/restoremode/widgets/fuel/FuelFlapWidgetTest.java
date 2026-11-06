package ru.big.town.restoremode.widgets.fuel;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import ru.big.town.common.FuelFlapWidgetProtocol;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.dashboard.tiles.TileSizeStore;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class FuelFlapWidgetTest {
    @Test
    public void gridPersistsOnlyTheFiveFuelSizes() {
        var preferences = RuntimeEnvironment.getApplication().getSharedPreferences("fuel-sizes", 0);
        String widget = FuelFlapWidgetLayout.ID;
        assertTrue(TileSizeStore.isResizable(widget));
        assertArrayEquals(new int[] {2, 1}, TileSizeStore.dimensions(preferences, widget, 2, 1));
        for (int[] size : new int[][] {{1, 1}, {1, 2}, {2, 1}, {2, 2}, {3, 1}}) {
            TileSizeStore.setSize(preferences, widget, size[0], size[1]);
            assertArrayEquals(size, TileSizeStore.dimensions(preferences, widget, 2, 1));
        }
        TileSizeStore.setSize(preferences, widget, 3, 2);
        TileSizeStore.setSize(preferences, widget, 4, 1);
        assertArrayEquals(new int[] {3, 1}, TileSizeStore.dimensions(preferences, widget, 2, 1));
    }

    private static Bundle state(boolean canOpen, int liters) {
        Bundle state = new Bundle();
        state.putBoolean(FuelFlapWidgetProtocol.CAN_OPEN, canOpen);
        state.putInt(FuelFlapWidgetProtocol.REFILL_LITERS, liters);
        return state;
    }

    @Test
    public void cardShowsRefillVolumeAndRequiresConfirmation() {
        try (var controller = Robolectric.buildActivity(Activity.class)) {
            Activity activity = controller.get();
            activity.setTheme(R.style.Theme_RestoreMode);
            controller.setup().visible();
            int[] requests = {0};
            FuelFlapWidgetView card =
                    new FuelFlapWidgetView(
                            activity, FuelFlapWidgetLayout.STANDARD, () -> requests[0]++);
            activity.setContentView(card);
            card.update(state(true, 26));
            assertEquals("Открыть лючок", card.getTooltipText());

            assertTrue(card.getContentDescription().toString().contains("Можно заправить ≈ 26 л"));
            card.performClick();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(dialog.isShowing());
            assertEquals(0, requests[0]);
            card.performClick();
            assertSame(dialog, ShadowDialog.getLatestDialog());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, requests[0]);
            card.update(state(true, 0));
            assertTrue(card.getContentDescription().toString().contains("Можно заправить ≈ 0 л"));
        }
    }

    @Test
    public void cancelAndLossOfParkingWhileDialogIsOpenNeverSendCommands() {
        try (var controller = Robolectric.buildActivity(Activity.class)) {
            Activity activity = controller.get();
            activity.setTheme(R.style.Theme_RestoreMode);
            controller.setup().visible();
            int[] requests = {0};
            FuelFlapWidgetView card =
                    new FuelFlapWidgetView(
                            activity, FuelFlapWidgetLayout.STANDARD, () -> requests[0]++);
            activity.setContentView(card);
            card.update(state(true, 26));
            card.performClick();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, requests[0]);

            card.performClick();
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            card.update(state(false, 26));
            assertFalse(dialog.isShowing());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, requests[0]);
            card.performClick();
            assertFalse(dialog.isShowing());
        }
    }

    @Test
    public void missingOrInvalidFuelNeverAppearsAsAFullTank() {
        FuelFlapWidgetView card =
                new FuelFlapWidgetView(
                        RuntimeEnvironment.getApplication(),
                        FuelFlapWidgetLayout.STANDARD,
                        () -> {});
        for (int liters : new int[] {-1, 201}) {
            Bundle state = state(false, liters);
            state.putString(FuelFlapWidgetProtocol.MESSAGE, "Переведите селектор в P");
            card.update(state);
            assertTrue(card.getContentDescription().toString().contains("Нет данных о топливе"));
            assertEquals("Переведите в P", card.getTooltipText());
        }
    }

    @Test
    public void remainingVolumeUsesVehicleCapacityAndClearsOnLegacyOrInvalidReading() {
        FuelFlapWidgetView card =
                new FuelFlapWidgetView(
                        RuntimeEnvironment.getApplication(),
                        FuelFlapWidgetLayout.STANDARD,
                        () -> {});
        Bundle reading = state(true, 39);
        reading.putInt(FuelFlapWidgetProtocol.CAPACITY_LITERS, 52);
        reading.putFloat(FuelFlapWidgetProtocol.REMAINING_PERCENT, 25f);
        card.update(reading);
        assertTrue(card.getContentDescription().toString().contains("В баке ≈ 13 л"));
        reading.putFloat(FuelFlapWidgetProtocol.REMAINING_PERCENT, Float.NaN);
        card.update(reading);
        assertFalse(card.getContentDescription().toString().contains("В баке"));
        card.update(state(true, 39));
        assertTrue(card.getContentDescription().toString().contains("Можно заправить ≈ 39 л"));
        assertFalse(card.getContentDescription().toString().contains("В баке"));
    }

    @Test
    public void longPressRemainsAvailableWithoutVehicleConnection() {
        FuelFlapWidgetView card =
                new FuelFlapWidgetView(
                        RuntimeEnvironment.getApplication(),
                        FuelFlapWidgetLayout.STANDARD,
                        () -> {});
        boolean[] moved = {false};
        card.setOnLongClickListener(
                view -> {
                    moved[0] = true;
                    return true;
                });
        assertTrue(card.isEnabled());
        assertTrue(card.performLongClick());
        assertTrue(moved[0]);
    }

    @Test
    public void missingFuelDoesNotBlockConfirmedOpeningAndDetachingClosesDialog() {
        try (var controller = Robolectric.buildActivity(Activity.class)) {
            Activity activity = controller.get();
            activity.setTheme(R.style.Theme_RestoreMode);
            controller.setup().visible();
            int[] requests = {0};
            FuelFlapWidgetView card =
                    new FuelFlapWidgetView(
                            activity, FuelFlapWidgetLayout.STANDARD, () -> requests[0]++);
            activity.setContentView(card);
            card.update(state(true, -1));
            assertTrue(card.getContentDescription().toString().contains("Нет данных о топливе"));
            card.performClick();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, requests[0]);
            card.performClick();
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            activity.setContentView(new TextView(activity));
            assertFalse(dialog.isShowing());
        }
    }
}
