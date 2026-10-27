package ru.big.town.restoremode.carplay;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import ru.big.town.common.CarPlayVehicleProtocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CarPlayProjectionClientTest {
    private final Application application = RuntimeEnvironment.getApplication();
    private final List<Message> requests = new ArrayList<>();

    @Before
    public void connectNativeMessenger() {
        Messenger service =
                new Messenger(
                        new Handler(
                                Looper.getMainLooper(),
                                message -> {
                                    requests.add(Message.obtain(message));
                                    return true;
                                }));
        shadowOf(application)
                .setComponentNameAndServiceForBindService(
                        new ComponentName(
                                "ru.big.town.anative", "ru.big.town.anative.SetModesService"),
                        service.getBinder());
    }

    @Test
    public void closeCarriesTheVirtualDisplayAndUnbindsOnlyAfterItsReply() throws Exception {
        CarPlayProjectionClient.close(application, 42);
        idle();
        assertEquals(1, requests.size());
        Message request = requests.get(0);
        assertEquals(CarPlayVehicleProtocol.CLOSE_PROJECTION, request.what);
        assertEquals(42, request.getData().getInt(CarPlayVehicleProtocol.DISPLAY_ID));
        assertEquals(1, shadowOf(application).getBoundServiceConnections().size());

        reply(request, 43, true);
        idle();
        assertEquals(1, shadowOf(application).getBoundServiceConnections().size());
        reply(request, 42, true);
        idle();
        assertTrue(shadowOf(application).getBoundServiceConnections().isEmpty());
    }

    @Test
    public void closeNeverRequestsPhysicalOrUnknownDisplays() {
        for (int displayId : new int[] {-1, 0, 1}) {
            CarPlayProjectionClient.close(application, displayId);
        }
        idle();
        assertTrue(requests.isEmpty());
        assertTrue(shadowOf(application).getBoundServiceConnections().isEmpty());
    }

    @Test
    public void closeReleasesBindingWhenNativeNeverReplies() {
        CarPlayProjectionClient.close(application, 42);
        idle();
        assertEquals(1, shadowOf(application).getBoundServiceConnections().size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertTrue(shadowOf(application).getBoundServiceConnections().isEmpty());
        assertEquals(1, requests.size());
    }

    @Test
    public void openReportsNativeResultOnceAndIgnoresLateReply() throws Exception {
        var lifecycle = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = lifecycle.get();
        List<Boolean> results = new ArrayList<>();
        try {
            int displayId = activity.getWindowManager().getDefaultDisplay().getDisplayId();
            new CarPlayProjectionClient(activity, displayId, results::add).start();
            idle();
            Message request = requests.get(0);
            assertEquals(CarPlayVehicleProtocol.OPEN_PROJECTION, request.what);
            reply(request, displayId, true);
            idle();
            reply(request, displayId, false);
            idle();
            assertEquals(List.of(true), results);
            assertTrue(shadowOf(application).getBoundServiceConnections().isEmpty());
        } finally {
            lifecycle.pause().stop().destroy();
        }
    }

    @Test
    public void openingAfterOwnerHasFinishedDoesNotRequestAProjection() {
        var lifecycle = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = lifecycle.get();
        List<Boolean> results = new ArrayList<>();
        try {
            int displayId = activity.getWindowManager().getDefaultDisplay().getDisplayId();
            new CarPlayProjectionClient(activity, displayId, results::add).start();
            activity.finish();
            idle();
            assertTrue(requests.isEmpty());
            assertEquals(List.of(false), results);
            assertTrue(shadowOf(application).getBoundServiceConnections().isEmpty());
        } finally {
            lifecycle.pause().stop().destroy();
        }
    }

    private void reply(Message request, int displayId, boolean success) throws RemoteException {
        Message response = Message.obtain(null, CarPlayVehicleProtocol.PROJECTION_RESULT);
        response.getData().putInt(CarPlayVehicleProtocol.DISPLAY_ID, displayId);
        response.getData().putBoolean(CarPlayVehicleProtocol.LAUNCHED, success);
        request.replyTo.send(response);
    }

    private void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }
}
