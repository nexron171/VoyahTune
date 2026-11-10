package ru.big.town.anative;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class NavigationOemAudioFocus implements NavigationAudioDucking.Focus {
    private static final String CLIENT = "VoyahTune_Application_Navigation";
    private final Context context;
    private Object manager;
    private Object listener;
    private Method request;
    private Method abandon;
    private IBinder binder;
    private volatile boolean requestActive;

    NavigationOemAudioFocus(Context context) {
        this.context = context;
    }

    private void connect() throws Exception {
        if (binder != null && binder.isBinderAlive()) return;
        Class<?> managerClass = sdkClass();
        ClassLoader loader = managerClass.getClassLoader();
        IBinder service =
                (IBinder)
                        loader.loadClass("com.qinggan.os.ServiceManager")
                                .getMethod("getService", String.class)
                                .invoke(null, "qg.audio_policy");
        if (service == null
                || !service.isBinderAlive()
                || !"com.qinggan.audiopolicy.IAudioPolicyService"
                        .equals(service.getInterfaceDescriptor())) {
            throw new IllegalStateException("OEM audio service unavailable");
        }
        Class<?> initialized =
                loader.loadClass("com.qinggan.audiopolicy.AudioPolicyManager$OnInitListener");
        manager =
                managerClass
                        .getMethod("getInstance", Context.class, initialized)
                        .invoke(null, context, null);
        if (manager == null) throw new IllegalStateException("OEM audio manager unavailable");
        Class<?> policy =
                loader.loadClass(
                        "com.qinggan.audiopolicy.AudioPolicyManager$OnAudioPolicyListener");
        listener =
                Proxy.newProxyInstance(
                        loader,
                        new Class<?>[] {policy},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                if ("hashCode".equals(method.getName()))
                                    return System.identityHashCode(proxy);
                                if ("equals".equals(method.getName())) return proxy == args[0];
                                return CLIENT;
                            }
                            if ("onStop".equals(method.getName())) requestActive = false;
                            return false;
                        });
        request =
                managerClass.getMethod(
                        "requestAudioFocus",
                        String.class,
                        policy,
                        int.class,
                        int.class,
                        String.class,
                        int.class,
                        int.class);
        abandon =
                managerClass.getMethod(
                        "abandonAudioFocus", policy, String.class, String.class, boolean.class);
        binder = service;
    }

    @Override
    public boolean request() throws Exception {
        connect();
        requestActive = true;
        int result =
                (Integer)
                        request.invoke(
                                manager, CLIENT, listener, 12, 1, context.getPackageName(), 3, 0);
        Log.i("NavigationAudio", "OEM navigation focus granted=" + result);
        if (result != 1) requestActive = false;
        return result == 1 && requestActive;
    }

    @Override
    public boolean alive() {
        return requestActive && binder != null && binder.isBinderAlive();
    }

    @Override
    public void release() throws Exception {
        requestActive = false;
        if (binder == null || !binder.isBinderAlive()) return;
        abandon.invoke(manager, listener, CLIENT, context.getPackageName(), true);
        Log.i("NavigationAudio", "OEM navigation focus released");
    }

    private Class<?> sdkClass() throws Exception {
        String className = "com.qinggan.audiopolicy.AudioPolicyManager";
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException absent) {
            for (String packageName :
                    new String[] {"com.qinggan.audiopolicy.service", "com.qinggan.app.setting"}) {
                try {
                    ApplicationInfo info =
                            context.getPackageManager().getApplicationInfo(packageName, 0);
                    if ((info.flags
                                    & (ApplicationInfo.FLAG_SYSTEM
                                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                            == 0) continue;
                    return context.createPackageContext(
                                    packageName,
                                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY)
                            .getClassLoader()
                            .loadClass(className);
                } catch (ClassNotFoundException
                        | android.content.pm.PackageManager.NameNotFoundException missing) {
                    absent.addSuppressed(missing);
                }
            }
            throw absent;
        }
    }
}
