package ru.big.town.anative;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import ru.big.town.common.CarPlayAudioProtocol;

/** One OEM worker; reading or setting volume does not acquire playback focus. */
final class CarPlayAudioController {
    private static final String TAG = "CarPlayAudio";
    private static final String OWNER_PACKAGE = "ru.big.town.restoremode";
    private static final String CLIENT = "VoyahTune_CarPlay_Audio";
    private final Context context;
    private final HandlerThread thread = new HandlerThread(TAG);
    private final Handler worker;
    private volatile int wanted = CarPlayAudioProtocol.NONE;
    private volatile boolean closing;
    private OemAudio api;
    private int applied = CarPlayAudioProtocol.NONE;
    private boolean ownsRequest;
    private final Runnable retry = this::applyFocus;

    CarPlayAudioController(Context context) {
        this.context = context.getApplicationContext();
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void setFocus(int stream) {
        if (closing || (stream != CarPlayAudioProtocol.NONE && !CarPlayAudioProtocol.validStream(stream))) return;
        wanted = stream;
        worker.post(this::applyFocus);
    }

    void volumes(Message message) {
        if (closing || message.replyTo == null) return;
        Messenger reply = message.replyTo;
        Bundle data = new Bundle(message.getData());
        boolean write = message.what == CarPlayAudioProtocol.SET;
        worker.post(() -> {
            int[] streams = CarPlayAudioProtocol.streams();
            int[] levels = new int[streams.length], maxima = new int[streams.length];
            Arrays.fill(levels, -1);
            Arrays.fill(maxima, -1);
            try {
                if (closing) return;
                OemAudio target = api();
                if (write) {
                    int stream = data.getInt(CarPlayAudioProtocol.STREAM, -1);
                    if (!CarPlayAudioProtocol.validStream(stream)) throw new IllegalArgumentException("Invalid stream");
                    int level = CarPlayAudioPolicy.clampVolume(data.getInt(CarPlayAudioProtocol.LEVEL, -1), target.maximum(stream));
                    target.setVolume(stream, level);
                }
                for (int i = 0; i < streams.length; i++) {
                    int max = target.maximum(streams[i]), level = target.volume(streams[i]);
                    if (max > 0 && level >= 0 && level <= max) { maxima[i] = max; levels[i] = level; }
                }
            } catch (Exception | LinkageError error) {
                Log.w(TAG, "OEM volume unavailable", error);
                if (api != null && !api.alive()) api = null;
            }
            Message result = Message.obtain(null, CarPlayAudioProtocol.VALUES);
            Bundle values = new Bundle();
            values.putLong(CarPlayAudioProtocol.REQUEST, data.getLong(CarPlayAudioProtocol.REQUEST));
            values.putIntArray(CarPlayAudioProtocol.LEVELS, levels);
            values.putIntArray(CarPlayAudioProtocol.MAXIMA, maxima);
            result.setData(values);
            try { reply.send(result); } catch (RemoteException ignored) {}
        });
    }

    private OemAudio api() throws Exception {
        if (api == null || !api.alive()) {
            api = new OemAudio(context);
            applied = CarPlayAudioProtocol.NONE;
        }
        return api;
    }

    private void applyFocus() {
        worker.removeCallbacks(retry);
        int next = wanted;
        if (next == applied && (api == null || api.alive()) &&
                (next != CarPlayAudioProtocol.NONE || !ownsRequest)) return;
        try {
            if (ownsRequest) {
                api().abandon();
                ownsRequest = false;
            }
            applied = CarPlayAudioProtocol.NONE;
            if (next == CarPlayAudioProtocol.NONE || closing) return;
            // Android media focus already resolves to OEM stream 3. Keep its MediaSession
            // owner, including play/pause while the iPhone is paused.
            if (next == CarPlayAudioProtocol.MEDIA) { applied = next; return; }
            OemAudio target = api();
            ownsRequest = true;
            int result = target.request(CarPlayAudioPolicy.forStream(next));
            if (result == 1) applied = next;
            Log.i(TAG, "OEM focus stream=" + next + " granted=" + result);
        } catch (Exception | LinkageError error) {
            Log.w(TAG, "OEM audio focus unavailable", error);
            api = null;
            applied = CarPlayAudioProtocol.NONE;
            if (!closing && (wanted != CarPlayAudioProtocol.NONE || ownsRequest)) worker.postDelayed(retry, 5000);
        }
        if (wanted != next && !closing) worker.post(this::applyFocus);
    }

    void destroy() {
        closing = true;
        wanted = CarPlayAudioProtocol.NONE;
        worker.removeCallbacks(retry);
        worker.post(() -> { applyFocus(); thread.quitSafely(); });
    }

    /** Load the installed OEM SDK and its Binder proxy; never copy transaction numbers. */
    private static final class OemAudio {
        final IBinder binder;
        final Object service, manager, listener;
        final Method getVolume, getMaximum, setVolume, request, abandon;

        OemAudio(Context context) throws Exception {
            Class<?> managerClass = sdkClass(context);
            ClassLoader loader = managerClass.getClassLoader();
            binder = (IBinder) loader.loadClass("com.qinggan.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "qg.audio_policy");
            if (binder == null || !binder.isBinderAlive() ||
                    !"com.qinggan.audiopolicy.IAudioPolicyService".equals(binder.getInterfaceDescriptor()))
                throw new IllegalStateException("OEM audio service unavailable");
            Class<?> serviceClass = loader.loadClass("com.qinggan.audiopolicy.IAudioPolicyService");
            service = loader.loadClass("com.qinggan.audiopolicy.IAudioPolicyService$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            getVolume = serviceClass.getMethod("getStreamVolume", int.class, String.class);
            getMaximum = serviceClass.getMethod("getStreamMaxVolume", int.class);
            setVolume = serviceClass.getMethod("setStreamVolume", int.class, int.class, int.class, String.class);
            Class<?> init = loader.loadClass("com.qinggan.audiopolicy.AudioPolicyManager$OnInitListener");
            manager = managerClass.getMethod("getInstance", Context.class, init).invoke(null, context, null);
            if (manager == null) throw new IllegalStateException("OEM audio manager unavailable");
            Class<?> policy = loader.loadClass("com.qinggan.audiopolicy.AudioPolicyManager$OnAudioPolicyListener");
            listener = Proxy.newProxyInstance(loader, new Class<?>[] {policy}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return CLIENT;
                }
                Log.d(TAG, "OEM focus callback=" + method.getName());
                return false; // Playback is controlled by the sink's Android focus listener.
            });
            request = managerClass.getMethod("requestAudioFocus", String.class, policy,
                    int.class, int.class, String.class, int.class, int.class);
            abandon = managerClass.getMethod("abandonAudioFocus", policy, String.class, String.class, boolean.class);
        }
        boolean alive() { return binder.isBinderAlive(); }
        int maximum(int stream) throws Exception { return (Integer) getMaximum.invoke(service, stream); }
        int volume(int stream) throws Exception { return (Integer) getVolume.invoke(service, stream, OWNER_PACKAGE); }
        void setVolume(int stream, int level) throws Exception { setVolume.invoke(service, stream, level, 0, OWNER_PACKAGE); }
        int request(CarPlayAudioPolicy attributes) throws Exception {
            return (Integer) request.invoke(manager, CLIENT, listener, attributes.usage, attributes.content,
                    OWNER_PACKAGE, attributes.gain, 0);
        }
        void abandon() throws Exception { abandon.invoke(manager, listener, CLIENT, OWNER_PACKAGE, true); }
        private static Class<?> sdkClass(Context context) throws Exception {
            String name = "com.qinggan.audiopolicy.AudioPolicyManager";
            try { return Class.forName(name); } catch (ClassNotFoundException absent) {
                for (String pkg : new String[] {"com.qinggan.audiopolicy.service", "com.qinggan.app.setting"}) {
                    ApplicationInfo app = context.getPackageManager().getApplicationInfo(pkg, 0);
                    if ((app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0)
                        throw new IllegalStateException("OEM SDK must be a system package");
                    try {
                        return context.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY)
                                .getClassLoader().loadClass(name);
                    } catch (ClassNotFoundException ignored) {}
                }
                throw absent;
            }
        }
    }
}
