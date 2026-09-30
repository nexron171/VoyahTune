"use strict";

/*
 * Frida-агент "keyboard-lock-en" для штатной клавиатуры com.qinggan.app.qgime.
 * Только ПИ-прошивка: QGIME-release-signed 1.0 (versionCode 1), Android 11.
 *
 *   1. блокирует раскладку на английской: попытка сохранить не-английский режим
 *      ввода подменяется на MODE_SKB_ENGLISH_FIRST;
 *   2. подменяет иконку клавиши смены языка картинкой из
 *      voyahtune_keyboard_en_config.json (base64), отдельно для тёмной и белой темы;
 *   3. выключает голосовой ввод (QGInputConfig.DISABLE_VOICE).
 *
 * Лог — android.util.Log (logcat -s vt_keyboard_en).
 */

const ICON_CONFIG_PATH = "/data/local/bin/voyahtune_keyboard_en_config.json";

const ICON_EN = "english_input_method";
const ICON_EN_WHITE = "english_input_method_white";

/** Код клавиши смены языка (USERDEF_KEYCODE_LANG_2). */
const KEY_SWITCH_LANGUAGE = -2;

/** Режимы InputModeSwitcher (public static final, значения из декомпиляции ПИ). */
const MODE_HKB_ENGLISH = 33554432;
const MODE_SKB_ENGLISH_FIRST = 36765696;
const MODE_SKB_ENGLISH_LOWER = 34668544;
const MODE_SKB_ENGLISH_UPPER = 35717120;
const MODE_SKB_SYMBOL1_EN = 33685504;
const MODE_SKB_SYMBOL2_EN = 33751040;

/** ThemeManager.DEFAULT_THEME_TITLE2 — белая тема. */
const WHITE_THEME_TITLE = "simple";

const TAG = "vt_keyboard_en";

Java.perform(() => {
    const Log = Java.use("android.util.Log");
    const InputModeSwitcher = Java.use("com.qinggan.app.qgime.InputModeSwitcher");
    const SoftKeyToggle = Java.use("com.qinggan.app.qgime.SoftKeyToggle");
    const KeyRow = Java.use("com.qinggan.app.qgime.SoftKeyboard$KeyRow");
    const XmlKeyboardLoader = Java.use("com.qinggan.app.qgime.XmlKeyboardLoader");
    const QGInputConfig = Java.use("com.qinggan.app.qgime.QGInputConfig");
    const SkbPool = Java.use("com.qinggan.app.qgime.SkbPool");
    const RXml = Java.use("com.qinggan.app.qgime.R$xml");
    const ThemeManager = Java.use("com.qinggan.theme.ThemeManager");
    const ActivityThread = Java.use("android.app.ActivityThread");

    const context = Java.retain(ActivityThread.currentApplication().getApplicationContext());
    const qwertyXmlIds = [RXml.skb_qwerty.value, RXml.skb_qwerty_no_voice.value];

    /** Читает текстовый файл средствами Java (у процесса IME свои права). */
    function readTextFile(path) {
        const BufferedReader = Java.use("java.io.BufferedReader");
        const FileReader = Java.use("java.io.FileReader");
        const reader = BufferedReader.$new(FileReader.$new(path));
        const lines = [];
        try {
            let line;
            while ((line = reader.readLine()) !== null) lines.push(line);
        } finally {
            reader.close();
        }
        return lines.join("\n");
    }

    /** Разворачивает base64-картинки из конфига в BitmapDrawable (retain — живут весь процесс). */
    function loadIcons() {
        const icons = {};
        let config;
        try {
            config = JSON.parse(readTextFile(ICON_CONFIG_PATH));
        } catch (e) {
            Log.e(TAG, `Config ${ICON_CONFIG_PATH} unavailable: ${e}`);
            return icons;
        }
        if (!config.drawable) return icons;
        const Base64 = Java.use("android.util.Base64");
        const BitmapFactory = Java.use("android.graphics.BitmapFactory");
        const BitmapDrawable = Java.use("android.graphics.drawable.BitmapDrawable");
        for (const name of [ICON_EN, ICON_EN_WHITE]) {
            const base64 = config.drawable[name];
            if (!base64) continue;
            try {
                const bytes = Base64.decode(base64, 0); // Base64.DEFAULT
                const bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                icons[name] = Java.retain(BitmapDrawable.$new(context.getResources(), bitmap));
            } catch (e) {
                Log.e(TAG, `Icon ${name} decode error: ${e}`);
            }
        }
        return icons;
    }

    const icons = loadIcons();

    // Не даёт сохранить не-английский режим ввода.
    const saveInputMode = InputModeSwitcher.saveInputMode.overload("int");
    saveInputMode.implementation = function (mode) {
        const isEnglish = mode === MODE_SKB_ENGLISH_LOWER ||
            mode === MODE_SKB_ENGLISH_UPPER ||
            mode === MODE_SKB_ENGLISH_FIRST ||
            mode === MODE_HKB_ENGLISH ||
            mode === MODE_SKB_SYMBOL1_EN ||
            mode === MODE_SKB_SYMBOL2_EN;
        return saveInputMode.call(this, isEnglish ? mode : MODE_SKB_ENGLISH_FIRST);
    };

    // Иконка клавиши смены языка во всех toggle-состояниях у skb_qwerty(_no_voice).
    if (icons[ICON_EN] || icons[ICON_EN_WHITE]) {
        const loadKeyboard = XmlKeyboardLoader.loadKeyboard.overload("int", "int", "int");
        loadKeyboard.implementation = function (resourceId, skbWidth, skbHeight) {
            const keyboard = loadKeyboard.call(this, resourceId, skbWidth, skbHeight);
            if (keyboard === null || qwertyXmlIds.indexOf(resourceId) < 0) return keyboard;
            try {
                const whiteTheme = "" + ThemeManager.getInstance(context).getCurrentThemeTitle() === WHITE_THEME_TITLE;
                const icon = whiteTheme ? icons[ICON_EN_WHITE] : icons[ICON_EN];
                if (!icon) return keyboard;
                const rows = keyboard.mKeyRows.value;
                for (let rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                    const keys = Java.cast(rows.get(rowIndex), KeyRow).mSoftKeys.value;
                    for (let keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
                        const key = keys.get(keyIndex);
                        if (!SoftKeyToggle.class.isInstance(key)) continue;
                        const toggleKey = Java.cast(key, SoftKeyToggle);
                        if (toggleKey.getKeyCode() !== KEY_SWITCH_LANGUAGE) continue;
                        let state = toggleKey.mToggleState.value;
                        while (state !== null) {
                            state.mKeyIcon.value = icon;
                            state = state.mNextState.value;
                        }
                    }
                }
            } catch (e) {
                Log.e(TAG, `Language key icon patch failed: ${e}`);
            }
            return keyboard;
        };
    }

    // Голосовой ввод выключен: SkbContainer выбирает раскладки *_no_voice.
    QGInputConfig.DISABLE_VOICE.value = true;

    // Сбросить кэш раскладок, иначе патчи применятся только к новым клавиатурам.
    SkbPool.getInstance().resetCachedSkb();

    Log.i(TAG, `Agent started: icons=${Object.keys(icons).join(",")}`);
});
