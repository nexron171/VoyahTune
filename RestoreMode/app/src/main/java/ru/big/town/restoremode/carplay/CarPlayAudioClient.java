package ru.big.town.restoremode.carplay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.util.Arrays;
import java.util.function.Consumer;
import ru.big.town.common.CarPlayAudioProtocol;

/** Settings-only volume binding; independent of the CarPlay session/CAN subscription. */
public final class CarPlayAudioClient {
    public static final class Volumes {
        public final int[] levels, maxima;
        public Volumes(int[] levels, int[] maxima) {
            this.levels = new int[4]; this.maxima = new int[4];
            Arrays.fill(this.levels, -1); Arrays.fill(this.maxima, -1);
            if (levels == null || maxima == null || levels.length != 4 || maxima.length != 4) return;
            for (int i = 0; i < 4; i++) if (maxima[i] > 0 && levels[i] >= 0 && levels[i] <= maxima[i]) {
                this.levels[i] = levels[i]; this.maxima[i] = maxima[i];
            }
        }
    }
    private final Context context;
    private final Consumer<Volumes> listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Messenger service, replies;
    private ServiceConnection connection;
    private boolean running, bound;
    private long revision, lastMutation, lastReceived;
    private int generation;
    private final Runnable retry = this::bind;
    private final Runnable timeout = () -> { if (running && service == null) reconnect(); };

    public CarPlayAudioClient(Context context, Consumer<Volumes> listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }
    public void start() {
        if (running) return;
        running = true;
        bind();
    }
    public void stop() {
        running = false;
        generation++;
        handler.removeCallbacks(retry);
        handler.removeCallbacks(timeout);
        disconnect();
    }
    public void query() { send(CarPlayAudioProtocol.QUERY, -1, -1); }
    public void setVolume(int stream, int level) {
        if (CarPlayAudioProtocol.validStream(stream) && level >= 0) send(CarPlayAudioProtocol.SET, stream, level);
    }
    private void send(int type, int stream, int level) {
        if (!running || service == null) return;
        Message request = Message.obtain(null, type);
        request.replyTo = replies;
        Bundle values = new Bundle();
        values.putLong(CarPlayAudioProtocol.REQUEST, ++revision);
        if (type == CarPlayAudioProtocol.SET) {
            lastMutation = revision;
            values.putInt(CarPlayAudioProtocol.STREAM, stream);
            values.putInt(CarPlayAudioProtocol.LEVEL, level);
        }
        request.setData(values);
        try { service.send(request); } catch (RemoteException unavailable) { reconnect(); }
    }
    private void bind() {
        if (!running || bound) return;
        int epoch = ++generation;
        replies = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (!running || epoch != generation || message.what != CarPlayAudioProtocol.VALUES) return true;
            Bundle values = message.getData();
            long received = values.getLong(CarPlayAudioProtocol.REQUEST, -1);
            if (received < lastMutation || received <= lastReceived || received > revision) return true;
            lastReceived = received;
            listener.accept(new Volumes(values.getIntArray(CarPlayAudioProtocol.LEVELS), values.getIntArray(CarPlayAudioProtocol.MAXIMA)));
            return true;
        }));
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!running || epoch != generation) return;
                handler.removeCallbacks(timeout);
                service = new Messenger(binder);
                query();
            }
            @Override public void onServiceDisconnected(ComponentName name) { failed(); }
            @Override public void onBindingDied(ComponentName name) { failed(); }
            @Override public void onNullBinding(ComponentName name) { failed(); }
            private void failed() { if (running && epoch == generation) reconnect(); }
        };
        try {
            bound = context.bindService(new Intent().setClassName("ru.big.town.anative", "ru.big.town.anative.SetModesService"),
                    connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException unavailable) { bound = false; }
        if (bound) handler.postDelayed(timeout, 5000); else reconnect();
    }
    private void reconnect() {
        generation++;
        disconnect();
        listener.accept(new Volumes(null, null));
        handler.removeCallbacks(retry);
        handler.removeCallbacks(timeout);
        if (running) handler.postDelayed(retry, 5000);
    }
    private void disconnect() {
        service = null;
        if (bound) { try { context.unbindService(connection); } catch (IllegalArgumentException ignored) {} }
        bound = false;
        connection = null;
    }
}
