package ru.big.town.restoremode;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.ResultReceiver;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoiceCommandMessageTest {
    @Test public void replyIsReadableWithoutTheClientClassLoader() {
        AtomicInteger received = new AtomicInteger();
        ResultReceiver callback = new ResultReceiver(null) {
            @Override protected void onReceiveResult(int code, Bundle data) { received.set(code); }
        };
        Bundle wire = roundTrip(VoiceCommandMessage.create("s", "execute", "drive:SPORT", callback));
        ResultReceiver reply = wire.getParcelable("reply");
        assertEquals(ResultReceiver.class, reply.getClass());
        assertEquals("drive:SPORT", wire.getString("action"));
        reply.send(17, Bundle.EMPTY);
        assertEquals(17, received.get());
    }

    @Test public void beginAndCancelDoNotNeedAReply() {
        for (String op : new String[]{"begin", "cancel"}) {
            Bundle wire = roundTrip(VoiceCommandMessage.create("s", op, null, null));
            assertEquals("s", wire.getString("session"));
            assertEquals(op, wire.getString("op"));
            assertNull(wire.getParcelable("reply"));
        }
    }

    @Test public void remoteProcessReceivesCommandAndReturnsResult() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        CountDownLatch connected = new CountDownLatch(1), answered = new CountDownLatch(1);
        AtomicReference<Messenger> remote = new AtomicReference<>();
        AtomicReference<Bundle> response = new AtomicReference<>();
        AtomicInteger responseCode = new AtomicInteger();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote.set(new Messenger(binder)); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        Intent intent = new Intent().setComponent(new ComponentName(context.getPackageName(),
                "ru.big.town.restoremode.VoiceReplyTestService"));
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE));
        try {
            assertTrue("Remote test service did not connect", connected.await(10, TimeUnit.SECONDS));
            ResultReceiver callback = new ResultReceiver(null) {
                @Override protected void onReceiveResult(int code, Bundle data) {
                    responseCode.set(code); response.set(data); answered.countDown();
                }
            };
            remote.get().send(VoiceCommandMessage.create("test-session", "execute", "test:echo", callback));
            assertTrue("Remote callback was lost", answered.await(10, TimeUnit.SECONDS));
            assertEquals(36, responseCode.get());
            assertEquals("test-session", response.get().getString("session"));
            assertEquals("execute", response.get().getString("op"));
            assertEquals("test:echo", response.get().getString("action"));
            assertEquals(3000, response.get().getInt("displayMs"));
            assertNotEquals(android.os.Process.myPid(), response.get().getInt("pid"));
        } finally {
            context.unbindService(connection);
        }
    }

    private static Bundle roundTrip(Message message) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(message.getData());
            parcel.setDataPosition(0);
            return parcel.readBundle(ResultReceiver.class.getClassLoader());
        } finally {
            parcel.recycle();
            message.recycle();
        }
    }
}
