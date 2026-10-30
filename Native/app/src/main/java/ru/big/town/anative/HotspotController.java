package ru.big.town.anative;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Controls Android tethering without replacing the saved SoftAp configuration. */
final class HotspotController {
    interface Backend {
        Boolean enabled() throws Exception;

        void start() throws Exception;

        void stop() throws Exception;
    }

    static String execute(Context context, String action) {
        return execute(new PlatformBackend(context), action);
    }

    static String execute(Backend backend, String action) {
        try {
            Boolean current = backend.enabled();
            final boolean target;
            switch (action) {
                case "hotspot:on":
                    target = true;
                    break;
                case "hotspot:off":
                    target = false;
                    break;
                case "hotspot:toggle":
                    if (current == null) return "Состояние точки доступа неизвестно";
                    target = !current;
                    break;
                default:
                    return "Неизвестное действие";
            }
            if (current != null && current == target) return "";
            if (target) backend.start();
            else backend.stop();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            do {
                if (Boolean.valueOf(target).equals(backend.enabled())) return "";
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            return "Не удалось подтвердить состояние точки доступа";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "Управление точкой доступа прервано";
        } catch (Exception | LinkageError exception) {
            Log.w("VoyahHotspot", "Tethering failed", exception);
            return "Не удалось переключить точку доступа Android";
        }
    }

    private static final class PlatformBackend implements Backend {
        private final Context context;

        PlatformBackend(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public Boolean enabled() throws Exception {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return null;
            int state = (Integer) WifiManager.class.getMethod("getWifiApState").invoke(wifi);
            if (state == 13) return true;
            if (state == 11) return false;
            return null;
        }

        @Override
        public void start() throws Exception {
            Object manager = manager();
            Class<?> builderType =
                    Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
            Object builder = builderType.getConstructor(int.class).newInstance(0);
            Object request = builderType.getMethod("build").invoke(builder);
            Class<?> callbackType =
                    Class.forName("android.net.TetheringManager$StartTetheringCallback");
            CountDownLatch done = new CountDownLatch(1);
            AtomicInteger error = new AtomicInteger(-1);
            Object callback =
                    Proxy.newProxyInstance(
                            callbackType.getClassLoader(),
                            new Class<?>[] {callbackType},
                            (proxy, method, arguments) -> {
                                switch (method.getName()) {
                                    case "onTetheringStarted":
                                        error.set(0);
                                        done.countDown();
                                        return null;
                                    case "onTetheringFailed":
                                        error.set((Integer) arguments[0]);
                                        done.countDown();
                                        return null;
                                    case "hashCode":
                                        return System.identityHashCode(proxy);
                                    case "equals":
                                        return proxy == arguments[0];
                                    case "toString":
                                        return "VoyahTune tethering callback";
                                    default:
                                        return null;
                                }
                            });
            Class.forName("android.net.TetheringManager")
                    .getMethod("startTethering", request.getClass(), Executor.class, callbackType)
                    .invoke(manager, request, (Executor) Runnable::run, callback);
            if (!done.await(3, TimeUnit.SECONDS) || error.get() != 0) {
                throw new IllegalStateException("Tethering start result=" + error.get());
            }
        }

        @Override
        public void stop() throws Exception {
            Class.forName("android.net.TetheringManager")
                    .getMethod("stopTethering", int.class)
                    .invoke(manager(), 0);
        }

        private Object manager() {
            Object manager = context.getSystemService("tethering");
            if (manager == null) throw new IllegalStateException("Tethering service unavailable");
            return manager;
        }
    }

    private HotspotController() {}
}
