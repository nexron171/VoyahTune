package ru.voyahtune.contracts;

import android.content.Context;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import java.util.List;
import ru.voyahtune.agent.runtime.MethodAccess;

final class KeyboardArtFixtures {
    static final Pool pool = new Pool();
    static final Switcher switcher = new Switcher();

    static MethodAccess methods() {
        return new MethodAccess(name -> {
            switch (name) {
                case "com.qinggan.app.qgime.InputModeSwitcher": return Switcher.class;
                case "com.qinggan.app.qgime.InputModeSwitcher$ToggleStates": return ToggleStates.class;
                case "com.qinggan.app.qgime.XmlKeyboardLoader": return Loader.class;
                case "com.qinggan.app.qgime.SkbPool": return Pool.class;
                case "com.qinggan.app.qgime.SoftKeyboard": return Keyboard.class;
                case "com.qinggan.app.qgime.SoftKey": return KeyboardContractDriver.Key.class;
                case "com.qinggan.app.qgime.SoftKeyToggle": return KeyboardContractDriver.ToggleKey.class;
                case "com.qinggan.app.qgime.QGInputConfig": return InputConfig.class;
                case "com.qinggan.app.qgime.R$xml": return XmlResources.class;
                case "com.qinggan.app.qgime.R$drawable": return DrawableResources.class;
                case "com.qinggan.theme.ThemeManager": return Theme.class;
                case "com.qinggan.app.qgime.QingganIME": return Ime.class;
                case "com.qinggan.app.qgime.SkbContainer": return Container.class;
                case "com.qinggan.app.qgime.EnglishInputProcessor": return Processor.class;
                case "com.pateo.material.dialog.QGToast": return Toast.class;
                default: return Class.forName(name);
            }
        });
    }

    static final class ToggleStates {}

    static final class Switcher {
        int mInputMode = 36765696;
        int mInputIcon = 10;
        Context mImeService;
        int saves;
        int switches;
        int saved;
        static Switcher getInstance() { return switcher; }
        void saveInputMode(int mode) { saved = mode; saves++; }
        int switchModeForUserKey(int code, boolean reset) { switches++; return 50; }
        int getTooggleStateForCnCand() { return 0; }
        ToggleStates getToggleStates() { return new ToggleStates(); }
        boolean isQwertyFirstMode() { return true; }
    }

    static final class Pool extends KeyboardContractDriver.Pool {
        int resets;
        static Pool getInstance() { return pool; }
        void resetCachedSkb() { mSoftKeyboards.clear(); resets++; }
        KeyboardContractDriver.Template getSkbTemplate(int id, Context context) { return new KeyboardContractDriver.Template(); }
        Keyboard getSoftKeyboard(int template, int xml, int width, int height, Context context) {
            return new Loader().loadKeyboard(xml, width, height);
        }
    }

    static final class Loader {
        Keyboard loadKeyboard(int xml, int width, int height) {
            Keyboard keyboard = new Keyboard();
            keyboard.setSkbCoreSize(width, height);
            keyboard.beginNewRow(0, 0);
            KeyboardContractDriver.ToggleKey language = new KeyboardContractDriver.ToggleKey();
            language.setKeyAttribute(-2, null, false, false);
            KeyboardContractDriver.ToggleState state = language.createToggleState();
            state.setStateId(2);
            state.mKeyIcon = "stock-language-icon";
            language.setToggleStates(state);
            keyboard.addSoftKey(language);
            return keyboard;
        }
    }

    static final class Keyboard extends KeyboardContractDriver.Keyboard {
        Keyboard() {}
        Keyboard(int resourceId, KeyboardContractDriver.Template template, int width, int height) {
            setSkbCoreSize(width, height);
        }
    }

    static final class InputConfig {
        static boolean DISABLE_VOICE;
    }

    static final class XmlResources {
        static int skb_qwerty = 101;
        static int skb_qwerty_no_voice = 102;
    }

    static final class DrawableResources {
        static int ime_pinyin = 10;
        static int ime_en = 11;
    }

    static final class Theme {
        static Theme getInstance(Context context) { return new Theme(); }
        String getCurrentThemeTitle() { return "dark"; }
    }

    static final class Ime {
        final Container mSkbContainer = new Container();
        final Switcher mInputModeSwitcher = switcher;
        int inputs;
        int resets;
        void responseSoftKeyEvent(KeyboardContractDriver.Key key) { inputs++; }
        void resetToIdleState(boolean reset) { resets++; }
    }

    static final class Container {
        int updates;
        int layoutUpdates;
        void updateInputMode() { updates++; }
        void updateSkbLayout() { layoutUpdates++; }
    }

    static final class Processor {
        int mLastKeyCode;
        int calls;
        boolean processKey(InputConnection connection, KeyEvent event, boolean upper, boolean real) { calls++; return false; }
    }

    static final class Toast {
        static String message;
        static int shown;
        static Toast makeText(Context context, CharSequence text, int duration) { message = text.toString(); return new Toast(); }
        void show() { shown++; }
    }
}
