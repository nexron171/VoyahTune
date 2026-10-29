package ru.voyahtune.agent.modules;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import ru.voyahtune.agent.policy.AirQualityPublisher;
import ru.voyahtune.agent.runtime.AndroidMainThread;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

public final class AndroidStatusBarPlatform implements StatusBarModule.Platform {
    private static final String HIDE = "voyahtune_carplay_hide_system_bars";
    private static final String REVEAL = "voyahtune_carplay_chrome_reveal_until_";
    private static final String AIR = "voyahtune_carplay_air_quality";
    private final Context context;
    private final MethodAccess methods;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AirQualityPublisher.Storage airQuality = new AirQualityPublisher.Storage() {
        @Override public long bootCount() { return Settings.Global.getInt(context.getContentResolver(), "boot_count", -1); }
        @Override public String read() { return Settings.Global.getString(context.getContentResolver(), AIR); }
        @Override public void write(String value) {
            if (!Settings.Global.putString(context.getContentResolver(), AIR, value)) throw new IllegalStateException("Air-quality settings write rejected");
        }
    };

    public AndroidStatusBarPlatform(Context context, MethodAccess methods) throws Exception {
        if (context == null) throw new IllegalArgumentException("SystemUI application context required");
        this.context = context;
        this.methods = methods;
        methods.require("com.qinggan.bus.QGBus", "subscribe", "com.qinggan.bus.QGBusEventFilter", "com.qinggan.bus.QGBusEventHandler");
        methods.require("com.qinggan.bus.QGBus", "unsubscribe", "com.qinggan.bus.QGBusEventFilter");
    }

    @Override public String top(int display) throws Exception {
        Object activity = OemAccess.call(methods.type("com.qinggan.os.ServiceManager"), "getDpyTopAppInfo", context, display, 2);
        return activity == null ? "" : activity.toString();
    }

    @Override public boolean hideSystemBars() { return Settings.Global.getInt(context.getContentResolver(), HIDE, 0) == 1; }
    @Override public long revealRemaining(int display) {
        long remaining = Settings.Global.getLong(context.getContentResolver(), REVEAL + display, 0) - SystemClock.elapsedRealtime();
        return remaining > 0 && remaining <= 10000 ? remaining : 0;
    }
    @Override public void reportHidden(int display, boolean hidden) {
        String key = "voyahtune_carplay_statusbar_hidden_" + display;
        int value = hidden ? 1 : 0;
        if (Settings.Global.getInt(context.getContentResolver(), key, -1) != value && !Settings.Global.putInt(context.getContentResolver(), key, value)) {
            throw new IllegalStateException("Status-bar visibility settings write rejected");
        }
    }

    @Override public Object statusBar() throws Exception {
        Object services = OemAccess.call(context, "getServices");
        Class<?> systemBars = methods.type("com.qinggan.systemui.statusbar.SystemBars");
        Class<?> phoneStatusBar = methods.type(StatusBarModule.BAR);
        for (int index = 0; index < Array.getLength(services); index++) {
            Object service = Array.get(services, index);
            if (service != null && systemBars.isInstance(service)) {
                Object bar = OemAccess.read(service, "mStatusBar");
                if (phoneStatusBar.isInstance(bar)) return bar;
            }
        }
        throw new IllegalStateException("PhoneStatusBar is not initialized");
    }

    @Override public void postMain(Runnable action) {
        if (!main.post(action)) throw new IllegalStateException("SystemUI main looper rejected work");
    }
    @Override public void drainMain(NavigationSourceModule.MainAction action) throws Exception { AndroidMainThread.run(action::run); }

    @Override public void watch(ModuleScope scope, Runnable changed) throws Exception {
        ContentObserver observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) { changed.run(); }
        };
        scope.own(() -> context.getContentResolver().unregisterContentObserver(observer));
        for (String setting : List.of(HIDE, REVEAL + 0, REVEAL + 1)) {
            context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(setting), false, observer);
        }
        Class<?> handlerType = methods.type("com.qinggan.bus.QGBusEventHandler");
        Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[] {handlerType}, (proxy, method, arguments) -> {
            switch (method.getName()) {
                case "equals": return proxy == arguments[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "VoyahTuneStatusBarEvents";
                case "onHandleEvent":
                    if (arguments != null && arguments.length == 1 && arguments[0] != null
                            && "com.qinggan.app.launcher".equals(OemAccess.call(arguments[0], "getSource"))) changed.run();
                    return null;
                default: return null;
            }
        });
        Object filter = OemAccess.construct(methods.type("com.qinggan.bus.QGBusEventFilter"));
        OemAccess.call(filter, "addEventType", "VOYAHTUNE_CARPLAY_STATUS_BAR_SYNC");
        Object bus = OemAccess.construct(methods.type("com.qinggan.bus.QGBus"), context);
        scope.own(new BusSubscription(bus, filter, handler));
        OemAccess.call(bus, "subscribe", filter, handler);
    }

    @Override public AirQualityPublisher.Storage airQuality() { return airQuality; }

    @Override public void seedAirQuality() throws Exception {
        Class<?> pm25;
        try { pm25 = methods.type("com.qinggan.systemui.statusbar.views.StatusBarPm25View"); }
        catch (ClassNotFoundException absent) { return; }
        Object windows = OemAccess.call(methods.type("android.view.WindowManagerGlobal"), "getInstance");
        List<?> roots = (List<?>) OemAccess.call(windows, "getWindowViews");
        ArrayDeque<View> pending = new ArrayDeque<>();
        for (Object root : roots) if (root instanceof View) pending.add((View) root);
        while (!pending.isEmpty()) {
            View view = pending.remove();
            if (pm25.isInstance(view) && view.getVisibility() == View.VISIBLE && view.getParent() instanceof View
                    && ((View) view.getParent()).getVisibility() == View.VISIBLE) {
                Object label = OemAccess.read(view, "pm25Value");
                if (label instanceof TextView) {
                    String value = ((TextView) label).getText().toString();
                    if (value.matches("[0-9]+")) AirQualityPublisher.publish(airQuality, Double.parseDouble(value));
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) pending.add(group.getChildAt(index));
            }
        }
    }

    @Override public void error(Exception failure) { Log.e("vt_statusbar", "OEM status-bar integration unavailable", failure); }

    private static final class BusSubscription implements AutoCloseable {
        final Object bus;
        final Object filter;
        final Object handler;
        BusSubscription(Object bus, Object filter, Object handler) { this.bus = bus; this.filter = filter; this.handler = handler; }
        @Override public void close() throws Exception { OemAccess.call(bus, "unsubscribe", filter); }
    }
}
