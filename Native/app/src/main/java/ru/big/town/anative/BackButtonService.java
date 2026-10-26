package ru.big.town.anative;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import ru.big.town.common.CarPlayTarget;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** System Back/Home actions and movable navigation on the two physical application displays. */
public class BackButtonService extends AccessibilityService {
    static final String TAG = "BackButtonService";
    static final int SIDE_LEFT = 0;
    static final int SIDE_TOP = 1;
    static final int SIDE_RIGHT = 2;
    private static final String COMPONENT =
            "ru.big.town.anative/ru.big.town.anative.BackButtonService";
    private static final String PREF_FLOATING = "floatingBack";
    private static final String PREF_STEERING = "steeringBack";
    private static final String PREF_FULLSCREEN = "fullscreenApps";

    private static BackButtonService instance;

    private final Handler overlayHandler = new Handler(Looper.getMainLooper());
    private final Map<Integer, FloatingNavigationOverlay> overlays = new LinkedHashMap<>();
    private String pendingEventPackage = "";
    private final Runnable overlayReevaluate = () -> reevaluateOverlayNow(pendingEventPackage);
    private int forcedCarPlayDisplays;
    private ContentObserver panelObserver;

    private SharedPreferences prefs() {
        return prefs(this);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        panelObserver =
                new ContentObserver(overlayHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        if (instance != BackButtonService.this) return;
                        overlayHandler.removeCallbacks(overlayReevaluate);
                        reevaluateOverlayNow(null);
                        scheduleOverlayEvaluation(null);
                    }
                };
        for (int displayId = 0; displayId <= 1; displayId++) {
            for (String setting :
                    new String[] {
                        CarPlayNavigationPolicy.DOCK_HIDDEN,
                        CarPlayNavigationPolicy.STATUS_BAR_HIDDEN
                    }) {
                getContentResolver()
                        .registerContentObserver(
                                Settings.Global.getUriFor(setting + displayId),
                                false,
                                panelObserver);
            }
        }

        reevaluateOverlayNow(null);
    }

    static void setFloatingButtonEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_FLOATING, enabled).apply();
        refreshAccessibility(context);
    }

    static void setSteeringBackEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_STEERING, enabled).apply();
        refreshAccessibility(context);
    }

    static void setFullscreenPackages(Context context, String packagesCsv) {
        prefs(context)
                .edit()
                .putString(PREF_FULLSCREEN, FullscreenPackagePolicy.normalizeCsv(packagesCsv))
                .apply();
        refreshAccessibility(context);
    }

    static void refreshCarPlayNavigation(Context context) {

        refreshAccessibility(context);
    }

    static void refreshAccessibility(Context context) {
        SharedPreferences preferences = prefs(context);
        boolean needed =
                FullscreenPackagePolicy.requiresAccessibilityService(
                                preferences.getBoolean(PREF_FLOATING, false),
                                preferences.getBoolean(PREF_STEERING, false),
                                preferences.getString(PREF_FULLSCREEN, ""))
                        || carPlayNavigationEnabled(context);
        writeAccessibility(context, needed);
        BackButtonService live = instance;
        if (live != null) live.scheduleOverlayEvaluation(null);
    }

    static boolean carPlayNavigationEnabled(Context context) {
        return CarPlayTarget.isEnabled(context)
                && Settings.Global.getInt(
                                context.getContentResolver(), CarPlayTarget.FULLSCREEN_SETTING, 0)
                        == 1;
    }

    static void disableForReconnect(Context context) {
        writeAccessibility(context, false);
    }

    private static void writeAccessibility(Context context, boolean present) {
        try {
            String current =
                    Settings.Secure.getString(
                            context.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            LinkedHashSet<String> services = new LinkedHashSet<>();
            if (current != null) {
                for (String service : current.split(":")) {
                    if (!service.isEmpty()) services.add(service);
                }
            }
            if (present) services.add(COMPONENT);
            else services.remove(COMPONENT);
            Settings.Secure.putString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    String.join(":", services));
            Settings.Secure.putInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    services.isEmpty() ? 0 : 1);
        } catch (RuntimeException error) {
            Log.e(TAG, "Cannot update navigation accessibility service", error);
        }
    }

    static void performBack(Context context) {
        BackButtonService live = instance;
        if (live != null) {
            live.performNavigationAction(0, GLOBAL_ACTION_BACK);
            return;
        }
        disableForReconnect(context);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> refreshAccessibility(context), 150);
        handler.postDelayed(
                () -> {
                    BackButtonService retry = instance;
                    if (retry != null) retry.performNavigationAction(0, GLOBAL_ACTION_BACK);
                    else Log.w(TAG, "Back skipped: accessibility service is not connected");
                },
                700);
    }

    private void performNavigationAction(int displayId, int action) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            boolean accepted = performGlobalAction(action);
            Log.i(TAG, "Navigation action=" + action + " accepted=" + accepted);
            return;
        }
        // A global action may target the driver even when the overlay was tapped by the passenger.
        try {
            Object inputManager = getSystemService(Context.INPUT_SERVICE);
            Method inject =
                    inputManager
                            .getClass()
                            .getMethod("injectInputEvent", InputEvent.class, int.class);
            Method setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
            long time = SystemClock.uptimeMillis();
            int keyCode =
                    action == GLOBAL_ACTION_HOME ? KeyEvent.KEYCODE_HOME : KeyEvent.KEYCODE_BACK;
            for (int keyAction : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                KeyEvent key =
                        new KeyEvent(
                                time,
                                time,
                                keyAction,
                                keyCode,
                                0,
                                0,
                                KeyCharacterMap.VIRTUAL_KEYBOARD,
                                0,
                                KeyEvent.FLAG_FROM_SYSTEM,
                                InputDevice.SOURCE_KEYBOARD);
                setDisplayId.invoke(key, displayId);
                if (!Boolean.TRUE.equals(inject.invoke(inputManager, key, 0))) {
                    Log.w(TAG, "Navigation input rejected on display " + displayId);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "Cannot send navigation action to display " + displayId, error);
        }
    }

    static void updatePosition() {
        BackButtonService live = instance;
        if (live != null) live.overlays.values().forEach(FloatingNavigationOverlay::updatePosition);
    }

    static boolean reshow() {
        BackButtonService live = instance;
        if (live == null) return false;
        live.reevaluateOverlayNow(null);
        live.overlays.values().forEach(FloatingNavigationOverlay::reshow);
        return true;
    }

    private void scheduleOverlayEvaluation(String eventPackage) {
        if (eventPackage != null) pendingEventPackage = eventPackage;
        overlayHandler.removeCallbacks(overlayReevaluate);
        overlayHandler.postDelayed(overlayReevaluate, 120L);
    }

    private void reevaluateOverlayNow(String fallbackPackage) {
        if (instance != this) return;
        Map<Integer, ComponentName> foreground = physicalForegroundActivities();
        String fullscreenCsv = prefs().getString(PREF_FULLSCREEN, "");
        ComponentName driver = foreground.get(Display.DEFAULT_DISPLAY);
        String driverPackage = driver == null ? "" : driver.getPackageName();
        if (FullscreenPackagePolicy.contains(fullscreenCsv, fallbackPackage)
                || driverPackage.isEmpty()) {
            driverPackage = fallbackPackage;
        }
        boolean persistent = prefs().getBoolean(PREF_FLOATING, false);

        int forcedDisplays = 0;
        for (int displayId = 0; displayId <= 1; displayId++) {
            ComponentName activity = foreground.get(displayId);
            boolean forced =
                    CarPlayNavigationPolicy.shouldForceNavigation(
                            displayId,
                            activity == null ? "" : activity.getClassName(),
                            Settings.Global.getInt(
                                    getContentResolver(),
                                    CarPlayNavigationPolicy.DOCK_HIDDEN + displayId,
                                    -1),
                            Settings.Global.getInt(
                                    getContentResolver(),
                                    CarPlayNavigationPolicy.STATUS_BAR_HIDDEN + displayId,
                                    -1));
            if (forced) forcedDisplays |= 1 << displayId;
            boolean show =
                    forced
                            || (displayId == Display.DEFAULT_DISPLAY
                                    && FullscreenPackagePolicy.shouldShowOverlay(
                                            persistent, fullscreenCsv, driverPackage));
            if (show) {
                FloatingNavigationOverlay overlay = overlays.get(displayId);
                if (overlay == null) {
                    DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
                    Display display = manager == null ? null : manager.getDisplay(displayId);
                    if (display == null) continue;
                    int targetDisplay = displayId;
                    overlay =
                            new FloatingNavigationOverlay(
                                    createDisplayContext(display),
                                    prefs(),
                                    action -> performNavigationAction(targetDisplay, action));
                    overlays.put(displayId, overlay);
                }
                overlay.show();
            } else {
                FloatingNavigationOverlay overlay = overlays.remove(displayId);
                if (overlay != null) overlay.hide();
            }
        }
        if (forcedDisplays != forcedCarPlayDisplays) {
            forcedCarPlayDisplays = forcedDisplays;
            Log.i(TAG, "Forced CarPlay navigation displays=" + forcedDisplays);
        }
        overlayHandler.removeCallbacks(overlayReevaluate);
        if (forcedDisplays != 0) overlayHandler.postDelayed(overlayReevaluate, 1000L);
    }

    @SuppressWarnings("deprecation")
    private Map<Integer, ComponentName> physicalForegroundActivities() {
        Map<Integer, ComponentName> foreground = new LinkedHashMap<>();
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager == null) return foreground;
            for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(40)) {
                if (task.topActivity == null) continue;
                int displayId = runningTaskDisplayId(task);
                if ((displayId == 0 || displayId == 1) && !foreground.containsKey(displayId)) {
                    foreground.put(displayId, task.topActivity);
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Physical foreground tasks unavailable", error);
        }
        return foreground;
    }

    private static int runningTaskDisplayId(ActivityManager.RunningTaskInfo task) {
        Class<?> type = task.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("displayId");
                field.setAccessible(true);
                return field.getInt(task);
            } catch (NoSuchFieldException error) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException error) {
                return -1;
            }
        }
        return -1;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence packageName = event == null ? null : event.getPackageName();
        scheduleOverlayEvaluation(packageName == null ? null : packageName.toString());
    }

    @Override
    public void onInterrupt() {}

    private void releaseOverlays() {
        overlayHandler.removeCallbacks(overlayReevaluate);
        if (panelObserver != null) {
            getContentResolver().unregisterContentObserver(panelObserver);
            panelObserver = null;
        }
        overlays.values().forEach(FloatingNavigationOverlay::hide);
        overlays.clear();
        if (instance == this) instance = null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        releaseOverlays();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        releaseOverlays();
        super.onDestroy();
    }
}
