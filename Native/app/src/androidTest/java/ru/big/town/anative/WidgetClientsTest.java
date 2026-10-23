package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class WidgetClientsTest {
    @Test
    public void oneDashboardCanUnwatchWhileTheOtherKeepsReceivingUpdates() {
        WidgetClients clients = new WidgetClients();
        List<Integer> driverUpdates = new ArrayList<>();
        List<Integer> passengerUpdates = new ArrayList<>();
        Messenger driver = receiver(driverUpdates);
        clients.watch(driver, 3);
        clients.watch(receiver(passengerUpdates), 8);

        assertTrue(clients.publish(133, state(10)));
        idle();
        assertTrue(clients.unwatch(driver, 3));
        assertTrue(clients.hasClients());
        assertTrue(clients.publish(133, state(20)));
        idle();
        assertEquals(Collections.singletonList(10), driverUpdates);
        assertEquals(Arrays.asList(10, 20), passengerUpdates);
    }

    @Test
    public void aStaleSessionCannotUnwatchTheNewSessionOrTheOtherDashboard() {
        WidgetClients clients = new WidgetClients();
        List<Integer> sessions = new ArrayList<>();
        Messenger dashboard =
                new Messenger(
                        new Handler(
                                Looper.getMainLooper(),
                                message -> {
                                    sessions.add(message.arg2);
                                    return true;
                                }));
        clients.watch(dashboard, 1);
        clients.watch(dashboard, 2);
        assertFalse(clients.unwatch(dashboard, 1));
        assertTrue(clients.isWatching(dashboard, 2));
        clients.publish(133, state(10));
        idle();
        assertEquals(Collections.singletonList(2), sessions);
    }

    @Test
    public void failureToSendToOneDashboardDoesNotDropTheOther() {
        WidgetClients clients = new WidgetClients();
        Messenger dead =
                new Messenger(
                        new Binder() {
                            @Override
                            protected boolean onTransact(
                                    int code, Parcel data, Parcel reply, int flags)
                                    throws RemoteException {
                                throw new RemoteException("Disconnected dashboard");
                            }
                        });
        List<Integer> updates = new ArrayList<>();
        clients.watch(dead);
        clients.watch(receiver(updates));

        assertTrue(clients.publish(133, state(10)));
        idle();
        assertFalse(clients.isWatching(dead, 0));
        assertEquals(Collections.singletonList(10), updates);
    }

    private static Messenger receiver(List<Integer> updates) {
        return new Messenger(
                new Handler(
                        Looper.getMainLooper(),
                        message -> {
                            updates.add(message.getData().getInt("value"));
                            return true;
                        }));
    }

    private static Bundle state(int value) {
        Bundle data = new Bundle();
        data.putInt("value", value);
        return data;
    }

    private static void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
