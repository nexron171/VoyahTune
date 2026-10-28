package ru.voyahtune.contracts;

import android.content.Context;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.KeyboardModule;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.OemAccess;

public final class KeyboardModuleArtTest {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("Expected native library and owned configuration directory");
        java.nio.file.Files.write(Paths.get(arguments[1], "owned-target.json"),
                ("{\"pid\":" + android.os.Process.myPid() + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        NativeTestBootstrap.load(arguments[0]);
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Object activityThread = OemAccess.call(Class.forName("android.app.ActivityThread"), "systemMain");
        Context context = (Context) OemAccess.call(activityThread, "getSystemContext");
        new Thread(() -> {
            try {
                verify(context, Paths.get(arguments[1]));
                System.out.println("{\"status\":\"passed\",\"languages\":2,\"nativeHooks\":true}");
                System.exit(0);
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
            }
        }, "KeyboardTestWorker").start();
        Looper.loop();
    }

    private static void verify(Context context, Path directory) throws Exception {
        for (String language : List.of("keyboard-en", "keyboard-ru")) {
            KeyboardArtFixtures.InputConfig.DISABLE_VOICE = false;
            KeyboardArtFixtures.switcher.mImeService = context;
            KeyboardArtFixtures.switcher.mInputMode = 36765696;
            KeyboardArtFixtures.switcher.switches = 0;
            FixtureHookBackend backend = new FixtureHookBackend(true);
            try (HookRegistry hooks = new HookRegistry(backend);
                 ModuleRuntime runtime = new ModuleRuntime("code", new ManualScheduler(),
                         id -> new KeyboardModule(id, KeyboardArtFixtures.methods(), hooks, context, directory))) {
                runtime.apply("code", 1, List.of(language), Map.of());
                require(runtime.snapshot().phase == ModuleRuntime.Phase.READY, "Keyboard was not ready", runtime.snapshot().failure);
                require(KeyboardArtFixtures.InputConfig.DISABLE_VOICE, "Voice was not disabled", null);
                KeyboardArtFixtures.switcher.saveInputMode(1);
                require(KeyboardArtFixtures.switcher.saved == 36765696, "English mode was not saved", null);
                KeyboardArtFixtures.Keyboard english = new KeyboardArtFixtures.Loader().loadKeyboard(101, 1280, 400);
                KeyboardContractDriver.ToggleKey languageKey = (KeyboardContractDriver.ToggleKey) english.mKeyRows.get(0).mSoftKeys.get(0);
                require(!"stock-language-icon".equals(languageKey.mToggleState.mKeyIcon), "English language icon was not replaced", null);
                runtime.apply("code", 1, List.of(language), Map.of());
                require(backend.installed.size() == (language.equals("keyboard-en") ? 2 : 10), "Repeated plan duplicated hooks", null);
                if (language.equals("keyboard-ru")) verifyRussian(context);
                runtime.close();
                require(backend.installed.isEmpty() && !KeyboardArtFixtures.InputConfig.DISABLE_VOICE,
                        "Keyboard cleanup left hooks or voice mutation", null);
                require("stock-language-icon".equals(languageKey.mToggleState.mKeyIcon), "OEM icon was not restored", null);
                require(KeyboardArtFixtures.pool.mSoftKeyboards.isEmpty(), "Russian keyboard remained cached", null);
                KeyboardArtFixtures.switcher.saveInputMode(1);
                require(KeyboardArtFixtures.switcher.saved == 1, "OEM input mode hook was not removed", null);
            }
        }
        FixtureHookBackend failing = new FixtureHookBackend(true);
        failing.accept = method -> !method.getName().equals("getKeyLabel");
        try (HookRegistry hooks = new HookRegistry(failing);
             ModuleRuntime runtime = new ModuleRuntime("code", new ManualScheduler(),
                     id -> new KeyboardModule(id, KeyboardArtFixtures.methods(), hooks, context, directory))) {
            runtime.apply("code", 1, List.of("keyboard-ru"), Map.of());
            require(runtime.snapshot().phase == ModuleRuntime.Phase.FAILED && failing.installed.isEmpty(),
                    "Partial keyboard installation did not roll back", runtime.snapshot().failure);
            require(!KeyboardArtFixtures.InputConfig.DISABLE_VOICE, "Failed installation changed voice configuration", null);
        }
    }

    private static void verifyRussian(Context context) throws Exception {
        KeyboardArtFixtures.Switcher switcher = KeyboardArtFixtures.switcher;
        require(switcher.switchModeForUserKey(-2, true) == 10 && switcher.switches == 0,
                "Language key did not select Russian without OEM language switch", null);
        KeyboardArtFixtures.Keyboard first = KeyboardArtFixtures.pool.getSoftKeyboard(0, 101, 1280, 400, context);
        KeyboardArtFixtures.Keyboard second = KeyboardArtFixtures.pool.getSoftKeyboard(0, 101, 1920, 600, context);
        require(first == second && first.width == 1920 && first.height == 600 && !first.newlyLoaded
                && KeyboardArtFixtures.pool.mSoftKeyboards.size() == 1, "Russian cache did not reuse/resize", null);
        KeyboardArtFixtures.Processor processor = new KeyboardArtFixtures.Processor();
        List<String> commits = new ArrayList<>();
        android.view.View[] inputView = new android.view.View[1];
        ru.voyahtune.agent.runtime.AndroidMainThread.run(() -> inputView[0] = new android.view.View(context));
        BaseInputConnection connection = new BaseInputConnection(inputView[0], false) {
            @Override
            public boolean commitText(CharSequence text, int position) {
                commits.add(text.toString());
                return true;
            }
        };
        require(processor.processKey(connection, new KeyEvent(0, 45), false, true), "Russian input was not handled", null);
        require(processor.processKey(connection, new KeyEvent(0, 45), true, false), "Preview was not handled", null);
        require(processor.processKey(connection, new KeyEvent(0, 45), true, true), "Uppercase was not handled", null);
        require(commits.equals(List.of("й", "Й")) && processor.calls == 0 && processor.mLastKeyCode == 45,
                "Russian commit or preview behavior changed", null);
        require(!processor.processKey(connection, new KeyEvent(0, 7), false, true) && processor.calls == 1,
                "Unmapped input did not call OEM processor once", null);
        KeyboardArtFixtures.Ime ime = new KeyboardArtFixtures.Ime();
        KeyboardContractDriver.Key language = new KeyboardContractDriver.Key();
        language.setKeyAttribute(-2, null, false, false);
        ime.responseSoftKeyEvent(language);
        require(ime.inputs == 1 && ime.mSkbContainer.layoutUpdates == 1, "Layout refresh did not follow the original key handler", null);
        KeyboardContractDriver.Key russian = new KeyboardContractDriver.Key();
        russian.setKeyAttribute(10001, "х", false, false);
        ime.responseSoftKeyEvent(russian);
        require(ime.inputs == 2 && ime.resets == 1 && switcher.switches == 1, "Temporary uppercase reset changed", null);
        KeyboardContractDriver.ToggleKey enter = new KeyboardContractDriver.ToggleKey();
        enter.setKeyAttribute(66, null, false, false);
        KeyboardContractDriver.ToggleState state = enter.createToggleState();
        state.mKeyLabel = "Ввод";
        enter.setToggleStates(state);
        require("Ввод".equals(enter.getKeyLabel()), "Russian enter label was not preserved", null);
        switcher.switchModeForUserKey(-10, true);
        require(KeyboardArtFixtures.Toast.shown == 1 && KeyboardArtFixtures.Toast.message.contains("русского"), "Russian voice message changed", null);
        switcher.switchModeForUserKey(-3, true);
        require(!processor.processKey(connection, new KeyEvent(0, 45), false, true) && processor.calls == 2,
                "Smiley did not restore the English processor path", null);
        switcher.mInputMode = 33685504;
        int before = switcher.switches;
        require(switcher.switchModeForUserKey(-2, true) == 50 && switcher.switches == before + 1,
                "Symbol language switch did not use OEM behavior", null);
    }

    private static void require(boolean condition, String message, Throwable failure) {
        if (!condition) throw new AssertionError(message, failure);
    }
}
