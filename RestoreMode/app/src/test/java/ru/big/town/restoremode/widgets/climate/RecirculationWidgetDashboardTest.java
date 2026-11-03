package ru.big.town.restoremode.widgets.climate;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import ru.big.town.common.RecirculationWidgetProtocol;
import ru.big.town.restoremode.integration.GlobalVars;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class RecirculationWidgetDashboardTest {
    private final List<Message> requests = new ArrayList<>();
    private RecirculationWidgetDashboard dashboard;
    private RecirculationWidgetView card;

    @Before
    public void connectService() {
        GlobalVars.isBound = true;
        GlobalVars.serviceMessenger =
                new Messenger(
                        new Handler(
                                Looper.getMainLooper(),
                                request -> {
                                    requests.add(Message.obtain(request));
                                    return true;
                                }));
        dashboard = new RecirculationWidgetDashboard(RuntimeEnvironment.getApplication());
        card = dashboard.create();
        dashboard.watch(true);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After
    public void disconnectService() {
        dashboard.close();
        GlobalVars.isBound = false;
        GlobalVars.serviceMessenger = null;
    }

    private void reply(Message request, int mode) throws RemoteException {
        Message response = Message.obtain(null, RecirculationWidgetProtocol.STATE, 0, request.arg2);
        Bundle state = new Bundle();
        state.putInt(RecirculationWidgetProtocol.MODE, mode);
        state.putBoolean(RecirculationWidgetProtocol.AVAILABLE, true);
        response.setData(state);
        request.replyTo.send(response);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void oneTapSendsOneCycleAndOemChangesUpdateCardWithoutExtraCommands() throws Exception {
        Message subscription = requests.get(0);
        assertEquals(RecirculationWidgetProtocol.WATCH, subscription.what);
        reply(subscription, RecirculationWidgetProtocol.ON);
        card.performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, requests.size());
        assertEquals(RecirculationWidgetProtocol.CYCLE, requests.get(1).what);
        assertTrue(((TextView) card.getChildAt(2)).getText().toString().startsWith("Вкл"));
        card.performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, requests.size());
        reply(subscription, RecirculationWidgetProtocol.OFF);
        assertEquals("Выкл", ((TextView) card.getChildAt(2)).getText());
        reply(subscription, RecirculationWidgetProtocol.AUTO);
        assertEquals("Авто", ((TextView) card.getChildAt(2)).getText());
        assertEquals(2, requests.size());
        card.performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(3, requests.size());
        assertEquals(RecirculationWidgetProtocol.CYCLE, requests.get(2).what);
    }

    @Test
    public void hidesStaleModeOnDisconnectAndIgnoresOldScreenResponses() throws Exception {
        Message previous = requests.get(0);
        reply(previous, RecirculationWidgetProtocol.ON);
        dashboard.watch(false);
        dashboard.watch(true);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(RecirculationWidgetProtocol.UNWATCH, requests.get(1).what);
        Message current = requests.get(2);
        reply(current, RecirculationWidgetProtocol.AUTO);
        reply(previous, RecirculationWidgetProtocol.OFF);
        assertEquals("Авто", ((TextView) card.getChildAt(2)).getText());
        dashboard.disconnected();
        reply(current, RecirculationWidgetProtocol.ON);
        assertEquals("—", ((TextView) card.getChildAt(2)).getText());
        assertTrue(card.getContentDescription().toString().contains("Нет связи с Native"));
        int sent = requests.size();
        card.performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(sent, requests.size());
    }

    @Test
    public void missingReplyTimesOutAndRebuiltCardKeepsLatestOemMode() throws Exception {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertEquals("—", ((TextView) card.getChildAt(2)).getText());
        assertTrue(card.getContentDescription().toString().contains("Обновите Native"));
        reply(requests.get(0), RecirculationWidgetProtocol.ON);
        dashboard.resetView();
        card = dashboard.create();
        assertEquals("Вкл", ((TextView) card.getChildAt(2)).getText());
    }
}
