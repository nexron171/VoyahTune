package ru.voyahtune.contracts;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.KeyboardInputModeModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.modules.RussianKeyboardBuilder;
import ru.voyahtune.agent.policy.KeyboardPolicy;
import ru.voyahtune.agent.runtime.ConfigurationObject;

final class KeyboardContractDriver {
    static JsonObject execute(String contract, JsonObject input) throws Throwable {
        JsonObject result = new JsonObject();
        if (contract.equals("keyboard.input-mode")) {
            return inputMode(input);
        }
        if (contract.equals("keyboard.toggle-icon")) {
            KeyboardPolicy.ToggleIcon icon = KeyboardPolicy.toggleIcon(input.get("keyCode").getAsInt(),
                    input.get("state").getAsInt(), input.get("theme").getAsString().equals("simple"),
                    input.get("customIconAvailable").getAsBoolean());
            result.addProperty("icon", icon == KeyboardPolicy.ToggleIcon.RUSSIAN ? "russian_input_method"
                    : icon == KeyboardPolicy.ToggleIcon.RUSSIAN_WHITE ? "russian_input_method_white"
                    : icon == KeyboardPolicy.ToggleIcon.ENGLISH_WHITE ? "oem-english-white" : null);
            return result;
        }
        ConfigurationObject configuration = configuration();
        Pool pool = new Pool();
        try (RussianKeyboardBuilder builder = new RussianKeyboardBuilder(configuration, new Factory())) {
            Keyboard keyboard = (Keyboard) builder.get(pool, 101, 1280, 400);
            Object same = builder.get(pool, 101, 1920, 600);
            if (same != keyboard || keyboard.width != 1920 || keyboard.height != 600 || keyboard.newlyLoaded) {
                throw new AssertionError("Russian keyboard cache did not resize/reuse the existing keyboard");
            }
            RussianKeyboardBuilder.changeCase(keyboard, input.get("uppercase").getAsBoolean());
            JsonArray rows = new JsonArray();
            List<Integer> controls = new ArrayList<>();
            for (Row row : keyboard.mKeyRows) {
                StringBuilder letters = new StringBuilder();
                for (Key key : row.mSoftKeys) {
                    if (KeyboardPolicy.letter(key.code)) letters.append(key.label);
                    else controls.add(key.code);
                }
                if (letters.length() != 0) rows.add(letters.toString());
            }
            result.add("letterRows", rows);
            result.addProperty("stockControlKeysPreserved", controls.containsAll(List.of(-1, 67, -2, -3, 62, 66, -7)));
            result.addProperty("duplicateKeyboards", pool.mSoftKeyboards.size() - 1);
        }
        if (!pool.mSoftKeyboards.isEmpty()) throw new AssertionError("Owned Russian keyboard remained cached after cleanup");
        return result;
    }

    private static JsonObject inputMode(JsonObject input) throws Throwable {
        FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        MethodAccess methods = new MethodAccess(name -> name.equals("com.qinggan.app.qgime.InputModeSwitcher")
                ? ModeSwitcher.class : Class.forName(name));
        try (HookRegistry hooks = new HookRegistry(backend);
             ModuleRuntime runtime = new ModuleRuntime("code", new ManualScheduler(),
                     id -> new KeyboardInputModeModule(id, methods, hooks))) {
            runtime.apply("code", 1, List.of("keyboard-en"), Map.of());
            if (runtime.snapshot().phase != ModuleRuntime.Phase.READY) {
                throw new AssertionError("Keyboard input-mode hook failed", runtime.snapshot().failure);
            }
            ModeSwitcher receiver = new ModeSwitcher();
            backend.invoke(ModeSwitcher.class.getDeclaredMethod("saveInputMode", int.class), receiver,
                    input.get("requested").getAsInt());
            JsonObject result = new JsonObject();
            result.addProperty("savedMode", receiver.saved);
            result.addProperty("originalCalls", receiver.calls);
            return result;
        }
    }

    static final class ModeSwitcher {
        int saved;
        int calls;
        void saveInputMode(int mode) { saved = mode; calls++; }
    }

    @SuppressWarnings("unchecked")
    static ConfigurationObject configuration() throws Exception {
        String configured = System.getProperty("voyahtune.keyboard.layout", System.getenv("VOYAHTUNE_KEYBOARD_LAYOUT"));
        Path path = java.nio.file.Paths.get(configured == null ? "Packaging/new/config/voyahtune_skb_qwerty_ru.json" : configured);
        Map<String, Object> values = new Gson().fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), Map.class);
        return new ConfigurationObject(values);
    }

    static class Pool {
        final List<Keyboard> mSoftKeyboards = new ArrayList<>();
    }

    static final class Factory implements RussianKeyboardBuilder.Platform {
        @Override public Object template(String reference) { return new Template(); }
        @Override public Object keyboard(int resourceId, Object template, int width, int height) { return new Keyboard(); }
        @Override public Object key(boolean toggle) { return toggle ? new ToggleKey() : new Key(); }
        @Override public Object drawable(String reference) { return reference; }
        @Override public Object toggleIcon(int code, int state) { return null; }
    }

    static final class Template {
        Key getDefaultKey(int id) { return new Key(); }
        Object getKeyType(int type) { return type; }
        Object getDefaultKeyIcon(int code) { return "default:" + code; }
        Object getDefaultKeyIconPopup(int code) { return "popup:" + code; }
    }

    static class Keyboard {
        final List<Row> mKeyRows = new ArrayList<>();
        int cacheId;
        int width;
        int height;
        boolean newlyLoaded = true;
        boolean mIsQwertyUpperCase;
        void setFlags(boolean cache, boolean sticky, boolean qwerty, boolean upper) {}
        void setKeyMargins(float horizontal, float vertical) {}
        void beginNewRow(int id, float y) { mKeyRows.add(new Row()); }
        boolean addSoftKey(Key key) { mKeyRows.get(mKeyRows.size() - 1).mSoftKeys.add(key); return true; }
        int getCacheId() { return cacheId; }
        void setCacheId(int value) { cacheId = value; }
        void setSkbCoreSize(int width, int height) { this.width = width; this.height = height; }
        void setNewlyLoadedFlag(boolean value) { newlyLoaded = value; }
        void disableToggleState(int state, boolean reset) {}
        void enableToggleStates(KeyboardArtFixtures.ToggleStates states) {}
        void switchQwertyMode(int mode, boolean upper) { mIsQwertyUpperCase = upper; }
    }

    static final class Row {
        final List<Key> mSoftKeys = new ArrayList<>();
    }

    static class Key {
        int code;
        int mKeyCode;
        String label;
        float left;
        float right;
        int getKeyCode() { return code; }
        void setKeyAttribute(int code, String label, boolean repeat, boolean balloon) {
            this.code = code;
            mKeyCode = code;
            this.label = label;
        }
        void setPopupSkbId(int id) {}
        void setKeyType(Object type, Object icon, Object popup) {}
        void setKeyDimensions(float left, float top, float right, float bottom) { this.left = left; this.right = right; }
        void setSkbCoreSize(int width, int height) {}
        void changeCase(boolean upper) {
            if (label != null) label = upper ? label.toUpperCase(Locale.ROOT) : label.toLowerCase(Locale.ROOT);
        }
    }

    static final class ToggleKey extends Key {
        ToggleState mToggleState;
        ToggleState createToggleState() { return new ToggleState(); }
        void setToggleStates(ToggleState value) { mToggleState = value; }
        String getKeyLabel() { return "OEM-label"; }
        ToggleState getToggleState() { return mToggleState; }
    }

    static final class ToggleState {
        int mKeyCode;
        String mKeyLabel;
        Object mKeyIcon;
        Object mKeyIconPopup;
        Object mKeyType;
        ToggleState mNextState;
        int stateId;
        void setStateId(int value) { stateId = value; }
        void setStateFlags(boolean repeat, boolean balloon) {}
    }
}
