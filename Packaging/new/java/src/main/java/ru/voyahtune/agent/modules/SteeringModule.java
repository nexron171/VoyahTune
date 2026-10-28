package ru.voyahtune.agent.modules;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.MediaKeyDelivery;
import ru.voyahtune.agent.policy.SteeringMedia;
import ru.voyahtune.agent.policy.SteeringPress;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class SteeringModule implements ModuleRuntime.Module {
    private static final String TAG = "vt_steeringwheelkeys";
    private static final String PROXY_ACTION = "ru.big.town.anative.MEDIA_KEY_PROXY";
    private static final Uri MEDIA_PROVIDER = Uri.parse("content://ru.big.town.anative.nowplaying");
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Context context;
    private final Map<Integer, SteeringPress> buttons = new LinkedHashMap<>();
    private final ThreadLocal<Boolean> synthetic = ThreadLocal.withInitial(() -> false);
    private Method onKeyEvent;
    private volatile Object reader;

    public SteeringModule(MethodAccess methods, HookRegistry hooks, Context context) {
        this.methods = methods;
        this.hooks = hooks;
        this.context = context;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        if (context == null) {
            throw new IllegalStateException("Application context required for steering");
        }
        onKeyEvent = methods.require("com.qinggan.keymanager.service.engine.KeyManagerReader",
                "onKeyEvent", "android.view.KeyEvent");
        if (onKeyEvent.getReturnType() != boolean.class) {
            throw new NoSuchMethodException("Unsupported KeyManagerReader result type");
        }
        onKeyEvent.setAccessible(true);
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        addButton(scope, 3090, "steerStar");
        addButton(scope, 173, "steerDvr");
        addButton(scope, 130, "steerVoice");
        addButton(scope, 128, "steerPhone");
        SteeringMedia media = new SteeringMedia(new SteeringMedia.Transport() {
            @Override
            public SteeringMedia.Decision decide(String command, int keyCode) {
                Bundle response = context.getContentResolver().call(MEDIA_PROVIDER, "media_command", command, null);
                return SteeringMedia.decision(keyCode, response == null ? null : response.getString("route"),
                        response == null ? keyCode : response.getInt("keyCode", keyCode));
            }

            @Override
            public boolean dispatch(int keyCode) {
                return dispatchMedia(keyCode);
            }
        });
        scope.own(() -> {
            media.clear();
            reader = null;
            buttons.clear();
        });
        scope.own(hooks.install("steering", onKeyEvent, scope.callback(invocation -> {
            if (synthetic.get()) {
                return invocation.proceed();
            }
            reader = invocation.receiver();
            KeyEvent event = (KeyEvent) invocation.arguments()[0];
            int code = event.getKeyCode();
            if (code == 3 || code == 4 || code == 6) {
                return media.consume(code, event.getAction(), event.getRepeatCount()) ? true : invocation.proceed();
            }
            SteeringPress press = buttons.get(code);
            if (press == null || press.passthrough()) {
                return invocation.proceed();
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                press.down(event.getRepeatCount());
            } else if (event.getAction() == KeyEvent.ACTION_UP && !press.up()) {
                KeyEvent down = KeyEvent.changeAction(KeyEvent.changeTimeRepeat(event, event.getDownTime(), 0), 0);
                try {
                    callOriginal(invocation.receiver(), down);
                    return invocation.proceed();
                } catch (Throwable failure) {
                    Log.e(TAG, "Native press replay failed", failure);
                }
            }
            return true;
        })));
        BroadcastReceiver proxy = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                scope.guard(() -> {
                    if (intent == null || !PROXY_ACTION.equals(intent.getAction())) {
                        return;
                    }
                    int keyCode = intent.getIntExtra("keyCode", -1);
                    boolean nativeQinggan = intent.getBooleanExtra("nativeQG", false);
                    if (!SteeringMedia.allowedProxyKey(keyCode, nativeQinggan)) {
                        return;
                    }
                    boolean handled = nativeQinggan ? syntheticPlayPause() : dispatchMedia(keyCode);
                    if (handled) {
                        setResultCode(-1);
                    }
                }).run();
            }
        };
        context.registerReceiver(proxy, new IntentFilter(PROXY_ACTION), "android.permission.WRITE_SECURE_SETTINGS", null);
        scope.own(() -> context.unregisterReceiver(proxy));
    }

    private void addButton(ModuleScope scope, int code, String setting) throws Exception {
        SteeringPress press = new SteeringPress(scope::schedule, () -> setting(setting + "Short"),
                () -> setting(setting + "Long"), this::dispatchAction);
        buttons.put(code, press);
        scope.own(press);
    }

    private String setting(String name) {
        try {
            String value = Settings.Global.getString(context.getContentResolver(), "voyahtune_" + name);
            return value == null ? "none" : value;
        } catch (RuntimeException failure) {
            return "none";
        }
    }

    private void dispatchAction(String action) {
        if (action.equals("none")) {
            return;
        }
        try {
            Intent intent = new Intent("ru.big.town.anative.STEER_ACTION");
            intent.setClassName("ru.big.town.anative", "ru.big.town.anative.SetModesReceiverDynamic");
            intent.putExtra("action", action);
            intent.addFlags(0x20);
            context.sendBroadcast(intent);
        } catch (RuntimeException failure) {
            Log.e(TAG, "Steering action delivery failed", failure);
        }
    }

    private boolean dispatchMedia(int keyCode) {
        return MediaKeyDelivery.dispatch(keyCode, (action, code) -> {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            audio.dispatchMediaKeyEvent(new KeyEvent(action, code));
        });
    }

    private boolean syntheticPlayPause() {
        Object current = reader;
        return current != null && MediaKeyDelivery.dispatch(6, (action, code) -> {
            try {
                callOriginal(current, new KeyEvent(action, code));
            } catch (Exception failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Native media delivery failed", failure);
            }
        });
    }

    private Object callOriginal(Object receiver, KeyEvent event) throws Throwable {
        boolean previous = synthetic.get();
        synthetic.set(true);
        try {
            return onKeyEvent.invoke(receiver, event);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } finally {
            synthetic.set(previous);
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        for (SteeringPress press : buttons.values()) {
            press.close();
        }
    }
}
