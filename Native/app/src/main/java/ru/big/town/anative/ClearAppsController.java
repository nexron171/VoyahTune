package ru.big.town.anative;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import ru.big.town.common.SystemWidgetProtocol;

/** Serial privileged cleanup, outside the service main thread. No metrics collection. */
final class ClearAppsController {
    private final HandlerThread thread = new HandlerThread("ClearApps");
    private final Handler worker;
    private final Supplier<Bundle> closeApps;
    private final AtomicBoolean clearing = new AtomicBoolean();

    ClearAppsController(Supplier<Bundle> closeApps) {
        this.closeApps = closeApps;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void handle(Message message) {
        if (message.what != SystemWidgetProtocol.CLEAR) return;
        Messenger reply = message.replyTo;
        int request = message.arg1;
        if (!clearing.compareAndSet(false, true)) {
            Bundle result = new Bundle();
            result.putString(SystemWidgetProtocol.ERROR, "Очистка уже выполняется");
            reply(reply, request, result);
            return;
        }
        worker.post(() -> {
            Bundle result;
            try { result = closeApps.get(); }
            catch (RuntimeException e) {
                result = new Bundle();
                result.putString(SystemWidgetProtocol.ERROR, "Не удалось закрыть приложения");
            } finally { clearing.set(false); }
            reply(reply, request, result);
        });
    }

    private static void reply(Messenger client, int request, Bundle data) {
        if (client == null) return;
        Message message = Message.obtain(null, SystemWidgetProtocol.CLEAR_RESULT, request, 0);
        Bundle result = new Bundle(data);
        result.putInt(SystemWidgetProtocol.SCHEMA, SystemWidgetProtocol.VERSION);
        message.setData(result);
        try { client.send(message); } catch (RemoteException ignored) { }
    }

    void close() { worker.removeCallbacksAndMessages(null); thread.quitSafely(); }
}
