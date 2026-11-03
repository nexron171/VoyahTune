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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class FuelFlapWidgetTest {
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
            FuelFlapWidgetView card = new FuelFlapWidgetView(activity, () -> requests[0]++);
            activity.setContentView(card);
            card.update(state(true, 26));

            assertEquals("Можно заправить ≈ 26 л", ((TextView) card.getChildAt(1)).getText());
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
            assertEquals("Можно заправить ≈ 0 л", ((TextView) card.getChildAt(1)).getText());
        }
    }

    @Test
    public void cancelAndLossOfParkingWhileDialogIsOpenNeverSendCommands() {
        try (var controller = Robolectric.buildActivity(Activity.class)) {
            Activity activity = controller.get();
            activity.setTheme(R.style.Theme_RestoreMode);
            controller.setup().visible();
            int[] requests = {0};
            FuelFlapWidgetView card = new FuelFlapWidgetView(activity, () -> requests[0]++);
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
                new FuelFlapWidgetView(RuntimeEnvironment.getApplication(), () -> {});
        for (int liters : new int[] {-1, 201}) {
            Bundle state = state(false, liters);
            state.putString(FuelFlapWidgetProtocol.MESSAGE, "Переведите селектор в P");
            card.update(state);
            assertEquals("Нет данных о топливе", ((TextView) card.getChildAt(1)).getText());
            assertEquals("Переведите селектор в P", ((TextView) card.getChildAt(2)).getText());
        }
    }

    @Test
    public void longPressRemainsAvailableWithoutVehicleConnection() {
        FuelFlapWidgetView card =
                new FuelFlapWidgetView(RuntimeEnvironment.getApplication(), () -> {});
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
            FuelFlapWidgetView card = new FuelFlapWidgetView(activity, () -> requests[0]++);
            activity.setContentView(card);
            card.update(state(true, -1));
            assertEquals("Нет данных о топливе", ((TextView) card.getChildAt(1)).getText());
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
