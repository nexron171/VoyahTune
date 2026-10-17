package ru.big.town.anative;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;

import java.lang.reflect.Method;

/** Uses the installed SDK's Binder proxy so transaction numbers follow the vehicle firmware. */
final class OemScreenBrightnessTransport
        implements ScreenBrightnessController.Screens, ScreenBrightnessModeController.Modes {
    private static final Uri SYSTEM_SETTINGS = Uri.parse("content://qinggan.settings/system");
    private static final String BRIGHTNESS_SYNC_KEY = "sync_bright_switcher_value";

    private ContentResolver contentResolver;
    private IBinder binder;
    private Object service;
    private Method getter;
    private Method setter;
    private Method modeGetter;
    private Method modeSetter;

    void connect(Context context) throws Exception {
        contentResolver = context.getContentResolver();
        if (binder != null && binder.isBinderAlive()) return;
        Class<?> api = loadApi(context);
        ClassLoader loader = api.getClassLoader();
        IBinder candidate =
                (IBinder)
                        loader.loadClass("com.qinggan.os.ServiceManager")
                                .getMethod("getService", String.class)
                                .invoke(null, "qg.system_policy");
        if (candidate == null
                || !candidate.isBinderAlive()
                || !api.getName().equals(candidate.getInterfaceDescriptor())) {
            throw new IllegalStateException("System policy service unavailable");
        }
        service =
                loader.loadClass("com.qinggan.system.ISystemPolicy$Stub")
                        .getMethod("asInterface", IBinder.class)
                        .invoke(null, candidate);
        getter = api.getMethod("getBackLight", int.class);
        setter = api.getMethod("setBackLight", int.class, int.class, int.class, boolean.class);
        modeGetter = api.getMethod("getDimmer", int.class);
        modeSetter = api.getMethod("setDimmer", int.class, int.class, int.class, boolean.class);
        binder = candidate;
    }

    @Override
    public int read(int screen) throws Exception {
        return (Integer) getter.invoke(service, screen);
    }

    @Override
    public boolean write(int screen, int percent) throws Exception {
        // FLAG_DEFAULT notifies stock UI; background retries must not open warning dialogs.
        return Boolean.TRUE.equals(setter.invoke(service, percent, 0, screen, false));
    }

    @Override
    public int readMode(int screen) throws Exception {
        return (Integer) modeGetter.invoke(service, screen);
    }

    @Override
    public boolean writeMode(int screen, int mode) throws Exception {
        return Boolean.TRUE.equals(modeSetter.invoke(service, mode, 0, screen, false));
    }

    @Override
    public boolean isSynchronizationEnabled() {
        try (Cursor cursor =
                contentResolver.query(
                        SYSTEM_SETTINGS,
                        new String[] {"value"},
                        "name=?",
                        new String[] {BRIGHTNESS_SYNC_KEY},
                        null)) {
            if (cursor == null) {
                throw new IllegalStateException("OEM brightness synchronization unavailable");
            }
            return cursor.moveToFirst() && "1".equals(cursor.getString(0));
        }
    }

    @Override
    public void enableSynchronization() {
        ContentValues values = new ContentValues();
        values.put("name", BRIGHTNESS_SYNC_KEY);
        values.put("value", "1");
        contentResolver.insert(SYSTEM_SETTINGS, values);
    }

    private static Class<?> loadApi(Context context) throws Exception {
        String name = "com.qinggan.system.ISystemPolicy";
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            for (String packageName :
                    new String[] {
                        "com.qinggan.systempolicy",
                        "com.qinggan.app.setting",
                        "com.qinggan.app.vehiclesetting",
                        "com.qinggan.app.launcher"
                    }) {
                try {
                    ApplicationInfo application =
                            context.getPackageManager().getApplicationInfo(packageName, 0);
                    if ((application.flags
                                    & (ApplicationInfo.FLAG_SYSTEM
                                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                            == 0) continue;
                    return context.createPackageContext(
                                    packageName,
                                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY)
                            .getClassLoader()
                            .loadClass(name);
                } catch (PackageManager.NameNotFoundException | ClassNotFoundException ignored) {
                }
            }
            throw missing;
        }
    }
}
