"use strict";

const ICON_CONFIG_PATH = "/data/local/bin/voyahtune-new/voyahtune_keyboard_en_config.json";

const ICON_EN = "english_input_method";
const ICON_EN_WHITE = "english_input_method_white";

const KEY_SWITCH_LANGUAGE = -2;

const MODE_HKB_ENGLISH = 33554432;
const MODE_SKB_ENGLISH_FIRST = 36765696;
const MODE_SKB_ENGLISH_LOWER = 34668544;
const MODE_SKB_ENGLISH_UPPER = 35717120;
const MODE_SKB_SYMBOL1_EN = 33685504;
const MODE_SKB_SYMBOL2_EN = 33751040;

const WHITE_THEME_TITLE = "simple";

const TAG = "vt_keyboard_en";

Java.perform(() => {
    const firmwareAdapter = VoyahAdapters.keyboard("en");
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

    const saveInputMode = firmwareAdapter.methods.save;
    saveInputMode.implementation = function (mode) {
        const isEnglish = mode === MODE_SKB_ENGLISH_LOWER ||
            mode === MODE_SKB_ENGLISH_UPPER ||
            mode === MODE_SKB_ENGLISH_FIRST ||
            mode === MODE_HKB_ENGLISH ||
            mode === MODE_SKB_SYMBOL1_EN ||
            mode === MODE_SKB_SYMBOL2_EN;
        return saveInputMode.call(this, isEnglish ? mode : MODE_SKB_ENGLISH_FIRST);
    };

    if (icons[ICON_EN] || icons[ICON_EN_WHITE]) {
        const loadKeyboard = firmwareAdapter.methods.load;
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

    QGInputConfig.DISABLE_VOICE.value = true;

    SkbPool.getInstance().resetCachedSkb();

    Log.i(TAG, `Agent started: icons=${Object.keys(icons).join(",")}`);
    console.log("[keyboard-en] hook ready v1");
});
