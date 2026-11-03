package ru.big.town.restoremode.widgets.fuel;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import androidx.appcompat.app.AlertDialog;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import ru.big.town.common.FuelFlapWidgetProtocol;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.integration.GlobalVars;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class FuelFlapWidgetDashboardTest {
    private final List<Message> requests = new ArrayList<>();

    @After
    public void disconnectService() {
        GlobalVars.isBound = false;
        GlobalVars.serviceMessenger = null;
    }

    private void connectService() {
        GlobalVars.isBound = true;
        GlobalVars.serviceMessenger =
                new Messenger(
                        new Handler(
                                Looper.getMainLooper(),
                                request -> {
                                    requests.add(Message.obtain(request));
                                    return true;
                                }));
    }

    private void reply(Message request, int liters) throws RemoteException {
        Message response = Message.obtain(null, FuelFlapWidgetProtocol.STATE, 0, request.arg2);
        Bundle state = new Bundle();
        state.putInt(FuelFlapWidgetProtocol.REFILL_LITERS, liters);
        state.putBoolean(FuelFlapWidgetProtocol.CAN_OPEN, true);
        response.setData(state);
        request.replyTo.send(response);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void sendsOpeningOnlyAfterConfirmationAndRejectsRepeatWhileWaiting() throws Exception {
        try (var controller = Robolectric.buildActivity(Activity.class)) {
            controller.get().setTheme(R.style.Theme_RestoreMode);
            Activity activity = controller.setup().visible().get();
            connectService();
            FuelFlapWidgetDashboard dashboard = new FuelFlapWidgetDashboard(activity);
            FuelFlapWidgetView card = dashboard.create();
            activity.setContentView(card);
            dashboard.watch(true);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(FuelFlapWidgetProtocol.WATCH, requests.get(0).what);
            reply(requests.get(0), 26);
            card.performClick();
            assertEquals(1, requests.size());
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, requests.size());
            assertEquals(FuelFlapWidgetProtocol.OPEN, requests.get(1).what);
            card.performClick();
            assertFalse(dialog.isShowing());
            assertEquals(2, requests.size());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
            assertTrue(card.getContentDescription().toString().contains("Обновите Native"));
            dashboard.close();
        }
    }

    @Test
    public void ignoresResponsesFromPreviousScreenSession() throws Exception {
        try (var controller = Robolectric.buildActivity(Activity.class)) {
            Activity activity = controller.setup().get();
            connectService();
            FuelFlapWidgetDashboard dashboard = new FuelFlapWidgetDashboard(activity);
            FuelFlapWidgetView card = dashboard.create();
            dashboard.watch(true);
            shadowOf(Looper.getMainLooper()).idle();
            Message previous = requests.get(0);
            reply(previous, 26);
            dashboard.watch(false);
            dashboard.watch(true);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(FuelFlapWidgetProtocol.UNWATCH, requests.get(1).what);
            assertEquals(FuelFlapWidgetProtocol.WATCH, requests.get(2).what);
            reply(requests.get(2), 10);
            reply(previous, 40);
            assertTrue(card.getContentDescription().toString().contains("≈ 10 л"));
            dashboard.disconnected();
            reply(requests.get(2), 30);
            assertTrue(card.getContentDescription().toString().contains("Нет связи с Native"));
            assertFalse(card.getContentDescription().toString().contains("30 л"));
            dashboard.close();
        }
    }
}
