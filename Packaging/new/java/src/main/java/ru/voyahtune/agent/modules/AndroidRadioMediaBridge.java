package ru.voyahtune.agent.modules;

import android.content.Intent;
import android.os.Bundle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.RadioStation;
import ru.voyahtune.agent.policy.RadioWidgetController;
import ru.voyahtune.agent.runtime.AndroidMainThread;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;

final class AndroidRadioMediaBridge {
    private static final String PLAYER = "com.pateo.midrds.media.RdsPlayer";
    private static final String SERVICE = "com.pateo.midrds.media.RdsMediaBrowserService";
    private static final String STATE = "android.support.v4.media.session.PlaybackStateCompat";

    private AndroidRadioMediaBridge() {}

    static void install(AndroidRdsPlatform platform, ModuleScope scope, HookRegistry hooks, RadioWidgetController widget) throws Exception {
        widget(platform, scope, hooks, widget);
        session(platform, scope, hooks);
    }

    private static void widget(AndroidRdsPlatform platform, ModuleScope scope, HookRegistry hooks, RadioWidgetController widget) throws Exception {
        Method command;
        try {
            command = platform.methods.require(PLAYER, "onCommand", "java.lang.String", "android.os.Bundle", "android.os.ResultReceiver");
        } catch (ReflectiveOperationException unsupported) { platform.error(unsupported); return; }
        ResourceGroup group = new ResourceGroup();
        scope.own(group);
        try {
            group.own(hooks.install("rds-widget", command, scope.callback(invocation -> {
                if (!"ru.big.town.voyahtune.RADIO_WIDGET".equals(invocation.arguments()[0])) return invocation.proceed();
                invocation.disableFallback();
                Object receiver = invocation.arguments()[2];
                if (receiver == null) return null;
                Bundle response = new Bundle();
                int code = 0;
                try {
                    Object caller = OemAccess.call(invocation.receiver(), "getCallingPackage");
                    if (!"ru.big.town.restoremode".equals(caller) && !"ru.big.town.anative".equals(caller)) throw new SecurityException("Untrusted radio controller");
                    Bundle arguments = (Bundle) invocation.arguments()[1];
                    if (arguments == null || arguments.getInt("schema", -1) != 1) throw new IllegalArgumentException("Unsupported widget protocol");
                    RadioStation station = new RadioStation(arguments.getBoolean("fm", true), arguments.getInt("freq", -1), false, 0, false, null);
                    response = response(widget.execute(arguments.getString("action", ""), station));
                    code = 1;
                } catch (Exception unavailable) { platform.error(unavailable); }
                try { OemAccess.call(receiver, "send", code, response); }
                catch (Exception unavailable) { platform.error(unavailable); }
                return null;
            })));
        } catch (HookRegistry.RejectedException unsupported) { group.close(); platform.error(unsupported); }
    }

    private static Bundle response(RadioWidgetController.Response result) {
        Bundle value = new Bundle();
        value.putInt("schema", 1);
        value.putBoolean("fm", result.fm);
        value.putInt("freq", result.frequency);
        value.putString("name", result.name);
        value.putBoolean("ready", result.ready);
        value.putBoolean("playing", result.playing);
        value.putBoolean("canSkip", Boolean.TRUE.equals(result.canSkip));
        value.putString("message", result.message);
        if (result.accepted != null) value.putBoolean("accepted", result.accepted);
        if (result.requested != null) {
            value.putInt("requestedFreq", result.requested.frequency);
            value.putBoolean("requestedFm", result.requested.fm);
        }
        return value;
    }

    private static void session(AndroidRdsPlatform platform, ModuleScope scope, HookRegistry hooks) throws Exception {
        Method create;
        Method destroy;
        Method notification;
        Class<?> builder;
        try {
            create = platform.methods.require(SERVICE, "onCreate");
            destroy = platform.methods.require("com.pateo.overSideRadio.base.media.BaseBrowserService", "onDestroy");
            notification = platform.methods.require("com.pateo.overSideRadio.base.media.MediaNotificationManager", "getNotification",
                    "android.support.v4.media.MediaMetadataCompat", STATE, "android.support.v4.media.session.MediaSessionCompat$Token");
            builder = platform.methods.type(STATE + "$Builder");
            builder.getDeclaredConstructor(platform.methods.type(STATE));
            platform.methods.type(PLAYER + "$1");
            OemAccess.field(platform.methods.type(PLAYER), "mMediaSession");
        } catch (ReflectiveOperationException unsupported) { platform.error(unsupported); return; }
        Sessions sessions = new Sessions(platform);
        ResourceGroup group = new ResourceGroup();
        scope.own(group);
        try {
            group.own(hooks.install("rds-media-session", notification, scope.callback(invocation -> {
                Object state = invocation.arguments()[1];
                Object complete = OemAccess.construct(builder, state);
                long actions = ((Number) OemAccess.call(state, "getActions")).longValue();
                OemAccess.call(complete, "setActions", actions | 16L | 32L);
                invocation.arguments()[1] = OemAccess.call(complete, "build");
                return invocation.proceed();
            })));
            group.own(hooks.install("rds-media-session", create, scope.callback(invocation -> {
                Object result = invocation.proceed();
                try { sessions.publish(OemAccess.read(invocation.receiver(), "rdsPlayer")); }
                catch (Exception unavailable) { platform.error(unavailable); }
                return result;
            })));
            group.own(hooks.install("rds-media-session", destroy, scope.callback(invocation -> {
                if (invocation.receiver().getClass().getName().equals(SERVICE)) {
                    try { OemAccess.call(OemAccess.read(OemAccess.read(invocation.receiver(), "rdsPlayer"), "mMediaSession"), "setActive", false); }
                    catch (Exception unavailable) { platform.error(unavailable); }
                }
                return invocation.proceed();
            })));
            group.own(() -> AndroidMainThread.run(sessions::restore));
        } catch (HookRegistry.RejectedException unsupported) { group.close(); platform.error(unsupported); return; }
        platform.onStartup(() -> {
            try {
                boolean found = false;
                Object listeners = OemAccess.read(platform.manager(), "infoListeners");
                if (listeners instanceof Iterable<?>) {
                    for (Object listener : (Iterable<?>) listeners) {
                        if (listener.getClass().getName().equals(PLAYER + "$1")) {
                            sessions.publish(OemAccess.read(listener, "this$0"));
                            found = true;
                        }
                    }
                }
                if (!found) platform.context.startForegroundService(new Intent().setClassName("com.pateo.rdsapp", SERVICE));
            } catch (Exception unavailable) { platform.error(unavailable); }
        });
    }

    private static final class Sessions {
        final AndroidRdsPlatform platform;
        final Map<Object, Boolean> previous = new WeakHashMap<>();
        Sessions(AndroidRdsPlatform platform) { this.platform = platform; }

        synchronized void publish(Object player) throws Exception {
            Object session = OemAccess.read(player, "mMediaSession");
            if (!previous.containsKey(session)) previous.put(session, Boolean.TRUE.equals(OemAccess.call(session, "isActive")));
            OemAccess.call(player, "setPlaybackState", OemAccess.call(player, "createPlaybackStateCompat", platform.playing()));
            OemAccess.call(session, "setActive", true);
        }

        synchronized void restore() throws Exception {
            Exception failure = null;
            for (Map.Entry<Object, Boolean> entry : new ArrayList<>(previous.entrySet())) {
                try { OemAccess.call(entry.getKey(), "setActive", entry.getValue()); previous.remove(entry.getKey()); }
                catch (Exception unavailable) { if (failure == null) failure = unavailable; else failure.addSuppressed(unavailable); }
            }
            if (failure != null) throw failure;
        }
    }
}
