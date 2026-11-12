package ru.big.town.anative;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import dalvik.system.PathClassLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Publishes the OEM toast event; Launcher owns the themed system window. */
final class OemToastNotifier {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Object bus;
    private static Class<?> eventClass;
    private static final ActionNoticeGate GATE = new ActionNoticeGate();
    private static Toast fallback;

    static void show(Context context, String text, boolean error) {
        Context application = context.getApplicationContext();
        MAIN.post(() -> publish(application, text, error, true));
    }

    static void showFuelFlap(Context context, String text) {
        Context application = context.getApplicationContext();
        MAIN.post(() -> publish(application, text, false, false));
    }

    private static void publish(Context context, String text, boolean error, boolean configurable) {
        boolean enabled =
                context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                        .getBoolean(
                                ru.big.town.common.ActionNotificationSettings.ENABLED,
                                ru.big.town.common.ActionNotificationSettings.DEFAULT_ENABLED);
        if (!GATE.accept(
                enabled, configurable, text, SystemClock.elapsedRealtime(), error ? 5000 : 3000))
            return;
        try {
            if (bus == null) connect(context);
            Object event = eventClass.getConstructor().newInstance();
            eventClass.getMethod("setEventType", String.class).invoke(event, "showToast");
            eventClass
                    .getMethod("setDestination", String.class)
                    .invoke(event, "com.qinggan.app.launcher");
            Bundle data = new Bundle();
            data.putString("package", context.getPackageName());
            data.putCharSequence("content", text);
            data.putInt("screenId", 1);
            data.putLong("duration", error ? 5000L : 3000L);
            eventClass.getMethod("setData", Bundle.class).invoke(event, data);
            bus.getClass().getMethod("publish", eventClass).invoke(bus, event);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            Log.w("VoyahToast", "OEM toast unavailable", exception);
            if (fallback != null) fallback.cancel();
            fallback =
                    Toast.makeText(context, text, error ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
            fallback.show();
        }
    }

    private static void connect(Context context) throws ReflectiveOperationException {
        List<ClassLoader> loaders = new ArrayList<>();
        loaders.add(context.getClassLoader());
        try {
            ApplicationInfo info =
                    context.getPackageManager()
                            .getApplicationInfo(
                                    "com.qinggan.app.launcher",
                                    PackageManager.GET_SHARED_LIBRARY_FILES);
            List<String> paths = new ArrayList<>();
            if (info.sharedLibraryFiles != null) Collections.addAll(paths, info.sharedLibraryFiles);
            paths.add(info.sourceDir);
            if (info.splitSourceDirs != null) Collections.addAll(paths, info.splitSourceDirs);
            loaders.add(
                    new PathClassLoader(
                            String.join(File.pathSeparator, paths), context.getClassLoader()));
        } catch (PackageManager.NameNotFoundException ignored) {
            // Non-OEM devices retain Android's toast fallback.
        }
        for (ClassLoader loader : loaders) {
            try {
                Class<?> busClass = Class.forName("com.qinggan.bus.QGBus", true, loader);
                Class<?> candidateEvent =
                        Class.forName(
                                "com.qinggan.bus.QGBusEvent", true, busClass.getClassLoader());
                busClass.getMethod("publish", candidateEvent);
                Object candidate = busClass.getConstructor(Context.class).newInstance(context);
                eventClass = candidateEvent;
                bus = candidate;
                return;
            } catch (ReflectiveOperationException | LinkageError exception) {
                Log.d("VoyahToast", "OEM bus not available in class loader", exception);
            }
        }
        throw new ClassNotFoundException("QGBus");
    }

    private OemToastNotifier() {}
}
