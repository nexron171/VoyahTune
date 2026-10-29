package ru.voyahtune.agent.modules;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.KeyboardPolicy;
import ru.voyahtune.agent.runtime.AndroidConfiguration;
import ru.voyahtune.agent.runtime.AndroidMainThread;
import ru.voyahtune.agent.runtime.ConfigurationObject;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

public final class KeyboardModule implements ModuleRuntime.Module {
    private static final String PACKAGE = "com.qinggan.app.qgime.";
    private static final String DIRECTORY = "/data/local/bin/voyahtune-new/";
    private static final String TAG = "vt_keyboard";
    private final String owner;
    private final boolean russian;
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Context context;
    private final Path configurationDirectory;
    private final KeyboardInputModeModule inputMode;
    private final Map<String, Method> targets = new HashMap<>();
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Map<Integer, String> letters = new HashMap<>();
    private final Map<Object, Object> originalIcons = new IdentityHashMap<>();
    private Object pool;
    private Object switcher;
    private Object lastContainer;
    private Boolean originalVoiceDisabled;
    private RussianKeyboardBuilder builder;
    private ConfigurationObject layout;
    private volatile String currentLayout = "en";
    private volatile boolean layoutChanged;
    private boolean prepared;
    private int qwerty;
    private int qwertyWithoutVoice;

    public KeyboardModule(String owner, MethodAccess methods, HookRegistry hooks, Context context) {
        this(owner, methods, hooks, context, Paths.get(DIRECTORY));
    }

    public KeyboardModule(String owner, MethodAccess methods, HookRegistry hooks, Context context, Path configurationDirectory) {
        this.owner = owner;
        russian = owner.equals("keyboard-ru");
        this.methods = methods;
        this.hooks = hooks;
        this.context = context;
        this.configurationDirectory = configurationDirectory;
        inputMode = new KeyboardInputModeModule(owner, methods, hooks);
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        if (prepared) return;
        if (context == null) throw new IllegalStateException("Application context required for keyboard");
        if (Looper.getMainLooper() == null) throw new IllegalStateException("Keyboard main looper is unavailable");
        inputMode.prepare(configuration);
        target("load", "XmlKeyboardLoader", "loadKeyboard", "int", "int", "int");
        OemAccess.field(methods.type(PACKAGE + "QGInputConfig"), "DISABLE_VOICE");
        qwerty = resource("R$xml", "skb_qwerty");
        qwertyWithoutVoice = resource("R$xml", "skb_qwerty_no_voice");
        pool = OemAccess.call(methods.type(PACKAGE + "SkbPool"), "getInstance");
        switcher = OemAccess.call(methods.type(PACKAGE + "InputModeSwitcher"), "getInstance");
        methods.require(PACKAGE + "SkbPool", "resetCachedSkb");
        methods.require("com.qinggan.theme.ThemeManager", "getInstance", "android.content.Context");
        methods.require("com.qinggan.theme.ThemeManager", "getCurrentThemeTitle");
        methods.type(PACKAGE + "SoftKeyToggle");
        if (russian) prepareRussian();
        loadIcons();
        prepared = true;
    }

    private void prepareRussian() throws Exception {
        layout = AndroidConfiguration.read(configurationDirectory.resolve("voyahtune_skb_qwerty_ru.json"), 1024 * 1024);
        for (ConfigurationObject row : layout.object("keyboard").objects("rows")) {
            for (ConfigurationObject key : row.objects("keys")) {
                int code = key.integer("code", 0);
                String label = key.string("label", null);
                if (KeyboardPolicy.letter(code) && label != null && !label.isEmpty()) letters.put(code, label);
            }
        }
        target("input", "QingganIME", "responseSoftKeyEvent", PACKAGE + "SoftKey");
        target("label", "SoftKeyToggle", "getKeyLabel");
        target("mode", "InputModeSwitcher", "switchModeForUserKey", "int", "boolean");
        target("pool", "SkbPool", "getSoftKeyboard", "int", "int", "int", "int", "android.content.Context");
        target("update", "SkbContainer", "updateInputMode");
        target("process", "EnglishInputProcessor", "processKey", "android.view.inputmethod.InputConnection",
                "android.view.KeyEvent", "boolean", "boolean");
        target("qwerty", "SoftKeyboard", "switchQwertyMode", "int", "boolean");
        target("states", "SoftKeyboard", "enableToggleStates", PACKAGE + "InputModeSwitcher$ToggleStates");
        methods.require("com.pateo.material.dialog.QGToast", "makeText", "android.content.Context", "java.lang.CharSequence", "int");
        builder = new RussianKeyboardBuilder(layout, new KeyboardPlatform());
    }

    private void target(String key, String owner, String name, String... parameters) throws Exception {
        targets.put(key, methods.require(PACKAGE + owner, name, parameters));
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        scope.own(() -> AndroidMainThread.run(() -> {
            OemAccess.call(pool, "resetCachedSkb");
            if (lastContainer != null) OemAccess.call(lastContainer, "updateInputMode");
            lastContainer = null;
        }));
        scope.own(() -> AndroidMainThread.run(this::restoreVoice));
        if (builder != null) scope.own(() -> AndroidMainThread.run(builder::close));
        scope.own(() -> AndroidMainThread.run(this::restoreIcons));
        inputMode.install(scope, configuration);
        install(scope, "load", invocation -> {
            Object keyboard = invocation.proceed();
            int id = (Integer) invocation.arguments()[0];
            if (keyboard != null && (id == qwerty || id == qwertyWithoutVoice)) patchEnglishIcons(keyboard);
            return keyboard;
        });
        if (russian) installRussian(scope);
        AndroidMainThread.run(() -> {
            Class<?> inputConfiguration = methods.type(PACKAGE + "QGInputConfig");
            originalVoiceDisabled = (Boolean) OemAccess.read(inputConfiguration, "DISABLE_VOICE");
            OemAccess.write(inputConfiguration, "DISABLE_VOICE", true);
            OemAccess.call(pool, "resetCachedSkb");
        });
    }

    private void install(ModuleScope scope, String key, HookRegistry.Callback callback) throws Exception {
        scope.own(hooks.install(owner, targets.get(key), scope.callback(callback)));
    }

    private void installRussian(ModuleScope scope) throws Exception {
        install(scope, "pool", invocation -> {
            Object[] arguments = invocation.arguments();
            Object keyboard = null;
            if (currentLayout.equals("ru")) {
                try {
                    keyboard = builder.get(invocation.receiver(), (Integer) arguments[1], (Integer) arguments[2], (Integer) arguments[3]);
                } catch (Exception failure) {
                    Log.e(TAG, "Russian keyboard build failed", failure);
                }
            }
            if (keyboard == null) keyboard = invocation.proceed();
            if (keyboard != null) {
                OemAccess.call(keyboard, "disableToggleState", OemAccess.call(switcher, "getTooggleStateForCnCand"), false);
                OemAccess.call(keyboard, "enableToggleStates", OemAccess.call(switcher, "getToggleStates"));
            }
            return keyboard;
        });
        install(scope, "mode", this::switchMode);
        install(scope, "input", invocation -> {
            Object result = invocation.proceed();
            Object ime = invocation.receiver();
            Object key = invocation.arguments()[0];
            Object container = OemAccess.read(ime, "mSkbContainer");
            if (layoutChanged) {
                OemAccess.call(container, "updateInputMode");
            } else if (currentLayout.equals("ru") && KeyboardPolicy.russianLetter((Integer) OemAccess.call(key, "getKeyCode"))) {
                Object currentSwitcher = OemAccess.read(ime, "mInputModeSwitcher");
                if ((Boolean) OemAccess.call(currentSwitcher, "isQwertyFirstMode")) {
                    OemAccess.call(currentSwitcher, "switchModeForUserKey", -1, true);
                    OemAccess.call(ime, "resetToIdleState", false);
                    OemAccess.call(container, "updateInputMode");
                }
            }
            return result;
        });
        install(scope, "update", invocation -> {
            lastContainer = invocation.receiver();
            Object result = invocation.proceed();
            if (layoutChanged) {
                OemAccess.call(invocation.receiver(), "updateSkbLayout");
                layoutChanged = false;
            }
            return result;
        });
        install(scope, "process", invocation -> {
            Object[] arguments = invocation.arguments();
            int code = ((KeyEvent) arguments[1]).getKeyCode();
            String letter = currentLayout.equals("ru") ? letters.get(code) : null;
            if (letter == null) return invocation.proceed();
            if ((Boolean) arguments[3]) {
                ((InputConnection) arguments[0]).commitText(KeyboardPolicy.letterCase(letter, (Boolean) arguments[2]), 1);
            }
            OemAccess.write(invocation.receiver(), "mLastKeyCode", code);
            return true;
        });
        install(scope, "qwerty", invocation -> {
            Object result = invocation.proceed();
            if (currentLayout.equals("ru")) RussianKeyboardBuilder.changeCase(invocation.receiver(), (Boolean) invocation.arguments()[1]);
            return result;
        });
        install(scope, "states", invocation -> {
            Object result = invocation.proceed();
            if (currentLayout.equals("ru")) {
                RussianKeyboardBuilder.changeCase(invocation.receiver(), (Boolean) OemAccess.read(invocation.receiver(), "mIsQwertyUpperCase"));
            }
            return result;
        });
        install(scope, "label", invocation -> {
            Object key = invocation.receiver();
            if (currentLayout.equals("ru") && ((Integer) OemAccess.read(key, "mKeyCode")) == 66) {
                Object state = OemAccess.call(key, "getToggleState");
                if (state != null) return OemAccess.read(state, "mKeyLabel");
            }
            return invocation.proceed();
        });
    }

    private Object switchMode(HookRegistry.Invocation invocation) throws Throwable {
        int key = (Integer) invocation.arguments()[0];
        String previous = currentLayout;
        Object receiver = invocation.receiver();
        if (key == -2) {
            int mode = (Integer) OemAccess.read(receiver, "mInputMode");
            if (mode == 33685504 || mode == 33751040) {
                currentLayout = "en";
                layoutChanged = !previous.equals(currentLayout);
                return invocation.proceed();
            }
            currentLayout = currentLayout.equals("en") ? "ru" : "en";
            int icon = resource("R$drawable", currentLayout.equals("ru") ? "ime_pinyin" : "ime_en");
            OemAccess.write(receiver, "mInputIcon", icon);
            layoutChanged = true;
            return icon;
        }
        if (key == -3) currentLayout = "en";
        else if (key == -10) {
            String message = currentLayout.equals("ru") ? "Голосовой ввод недоступен для русского языка."
                    : "Voice input is not available for English.";
            Object toast = OemAccess.call(methods.type("com.pateo.material.dialog.QGToast"), "makeText",
                    OemAccess.read(receiver, "mImeService"), message, 2);
            OemAccess.call(toast, "show");
            return OemAccess.read(receiver, "mInputIcon");
        }
        layoutChanged = !previous.equals(currentLayout);
        return invocation.proceed();
    }

    private void patchEnglishIcons(Object keyboard) throws Exception {
        Drawable icon = icons.get(whiteTheme() ? "english_input_method_white" : "english_input_method");
        if (icon == null) return;
        Class<?> toggleClass = methods.type(PACKAGE + "SoftKeyToggle");
        for (Object row : (List<?>) OemAccess.read(keyboard, "mKeyRows")) {
            for (Object key : (List<?>) OemAccess.read(row, "mSoftKeys")) {
                if (!toggleClass.isInstance(key) || ((Integer) OemAccess.call(key, "getKeyCode")) != -2) continue;
                Object state = OemAccess.read(key, "mToggleState");
                Map<Object, Boolean> visited = new IdentityHashMap<>();
                while (state != null && visited.put(state, true) == null) {
                    synchronized (originalIcons) {
                        if (!originalIcons.containsKey(state)) originalIcons.put(state, OemAccess.read(state, "mKeyIcon"));
                    }
                    OemAccess.write(state, "mKeyIcon", icon);
                    state = OemAccess.read(state, "mNextState");
                }
            }
        }
    }

    private boolean whiteTheme() throws Exception {
        Object theme = OemAccess.call(methods.type("com.qinggan.theme.ThemeManager"), "getInstance", context);
        return "simple".equals(OemAccess.call(theme, "getCurrentThemeTitle"));
    }

    private int resource(String owner, String name) throws Exception {
        return (Integer) OemAccess.read(methods.type(PACKAGE + owner), name);
    }

    private Drawable drawable(String reference) {
        if (reference == null || !reference.startsWith("@")) return null;
        int slash = reference.indexOf('/');
        if (slash < 2 || slash == reference.length() - 1) return null;
        int id = context.getResources().getIdentifier(reference.substring(slash + 1), reference.substring(1, slash), context.getPackageName());
        return id == 0 ? null : context.getDrawable(id);
    }

    private void loadIcons() {
        try {
            ConfigurationObject configuration = AndroidConfiguration.read(configurationDirectory.resolve("voyahtune_keyboard_"
                    + (russian ? "ru" : "en") + "_config.json"), 4 * 1024 * 1024).object("drawable");
            for (String name : List.of("english_input_method", "english_input_method_white", "russian_input_method", "russian_input_method_white")) {
                String encoded = configuration.string(name, null);
                if (encoded == null) continue;
                byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) icons.put(name, new BitmapDrawable(context.getResources(), bitmap));
            }
        } catch (Exception failure) {
            Log.e(TAG, "Optional keyboard icons are unavailable", failure);
        }
    }

    private void restoreIcons() throws Exception {
        synchronized (originalIcons) {
            for (Map.Entry<Object, Object> entry : originalIcons.entrySet()) OemAccess.write(entry.getKey(), "mKeyIcon", entry.getValue());
            originalIcons.clear();
        }
    }

    private void restoreVoice() throws Exception {
        if (originalVoiceDisabled != null) {
            Class<?> inputConfiguration = methods.type(PACKAGE + "QGInputConfig");
            if (Boolean.TRUE.equals(OemAccess.read(inputConfiguration, "DISABLE_VOICE"))) {
                OemAccess.write(inputConfiguration, "DISABLE_VOICE", originalVoiceDisabled);
            }
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) {}

    private final class KeyboardPlatform implements RussianKeyboardBuilder.Platform {
        @Override
        public Object template(String reference) throws Exception {
            if (reference == null) return null;
            int slash = reference.indexOf('/');
            int id = slash < 2 ? 0 : context.getResources().getIdentifier(reference.substring(slash + 1), reference.substring(1, slash), context.getPackageName());
            return OemAccess.call(pool, "getSkbTemplate", id, context);
        }

        @Override
        public Object keyboard(int resourceId, Object template, int width, int height) throws Exception {
            return OemAccess.construct(methods.type(PACKAGE + "SoftKeyboard"), resourceId, template, width, height);
        }

        @Override
        public Object key(boolean toggle) throws Exception {
            return OemAccess.construct(methods.type(PACKAGE + (toggle ? "SoftKeyToggle" : "SoftKey")));
        }

        @Override
        public Object drawable(String reference) {
            return KeyboardModule.this.drawable(reference);
        }

        @Override
        public Object toggleIcon(int code, int state) throws Exception {
            boolean white = whiteTheme();
            if (code == -7 && white) return drawable("@drawable/hide_keyboard_white");
            String custom = white ? "russian_input_method_white" : "russian_input_method";
            switch (KeyboardPolicy.toggleIcon(code, state, white, icons.containsKey(custom))) {
                case RUSSIAN:
                case RUSSIAN_WHITE:
                    return icons.get(custom);
                case ENGLISH_WHITE:
                    return drawable("@drawable/english_input_method_white");
                case SHIFT_LOWER_WHITE:
                    return drawable("@drawable/shift_lower_c53_white");
                case SHIFT_UPPER_WHITE:
                    return drawable("@drawable/shift_uppercase_c53_white");
                case SHIFT_TEMP_WHITE:
                    return drawable("@drawable/shift_uppercase_c53_temp_white");
                default:
                    return null;
            }
        }
    }
}
