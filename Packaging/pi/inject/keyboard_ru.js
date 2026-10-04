"use strict";

/*
 * Frida-агент "keyboard-ru" для штатной клавиатуры com.qinggan.app.qgime.
 * Только ПИ-прошивка: QGIME-release-signed 1.0 (versionCode 1), Android 11.
 *
 * Добавляет в клавиатуру русскую раскладку, которой в прошивке нет:
 *   * собирает русскую клавиатуру целиком из JSON-описания
 *     (voyahtune_skb_qwerty_ru.json) и кэширует её в SkbPool;
 *   * вешает переключение en/ru на клавишу смены языка и подменяет её иконку
 *     картинками из voyahtune_keyboard_ru_config.json;
 *   * подставляет русские буквы вместо латинских при вводе (по таблице
 *     keyCode -> символ, построенной из того же JSON);
 *   * гасит голосовой ввод (QGInputConfig.DISABLE_VOICE → раскладки *_no_voice),
 *     на клавишу голоса показывает тост.
 *
 * Скрипт внедряет loaderFrida (injects.json) через frida-inject; stdout frida-inject
 * никуда не выводится, поэтому лог — android.util.Log (logcat -s vt_keyboard_ru).
 */

const LAYOUT_CONFIG_PATH = "/data/local/bin/voyahtune_skb_qwerty_ru.json";
const ICON_CONFIG_PATH = "/data/local/bin/voyahtune_keyboard_ru_config.json";

/** Метка "агент уже установлен в этом процессе" (java.lang.System property). */
const AGENT_MARK = "ru.big.town.keyboard_ru.agent";

/** Под этим cacheId собранная русская клавиатура лежит в SkbPool. */
const CUSTOM_SKB_CACHE_ID = 999999;

/** Имена иконок в секции "drawable" конфига. */
const ICON_EN = "english_input_method";
const ICON_EN_WHITE = "english_input_method_white";
const ICON_RU = "russian_input_method";
const ICON_RU_WHITE = "russian_input_method_white";
const ICON_NAMES = [ICON_EN, ICON_EN_WHITE, ICON_RU, ICON_RU_WHITE];

/** Диапазон псевдокодов, под которыми в JSON лежат русские буквы. */
const RU_KEYCODE_MIN = 10001;
const RU_KEYCODE_MAX = 10007;

/** KEYCODE_A .. KEYCODE_Z — латинские буквы основного ряда. */
const LETTER_KEYCODE_MIN = 29;
const LETTER_KEYCODE_MAX = 54;

const ENTER_KEYCODE = 66;

/** Пользовательские коды клавиш IME. */
const KEY_SHIFT = -1;
const KEY_SWITCH_LANGUAGE = -2;
const KEY_SMILEY = -3;
const KEY_HIDE_KEYBOARD = -7;
const KEY_VOICE = -10;

/** Состояния клавиши shift (state_id из JSON). */
const STATE_SHIFT_LOWER = 2;
const STATE_SHIFT_UPPER = 3;
const STATE_SHIFT_UPPER_TEMP = 16;

/** Режимы InputModeSwitcher (public static final, значения из декомпиляции ПИ). */
const MODE_HKB_ENGLISH = 33554432;
const MODE_SKB_ENGLISH_FIRST = 36765696;
const MODE_SKB_ENGLISH_LOWER = 34668544;
const MODE_SKB_ENGLISH_UPPER = 35717120;
const MODE_SKB_SYMBOL1_EN = 33685504;
const MODE_SKB_SYMBOL2_EN = 33751040;

/** ThemeManager.DEFAULT_THEME_TITLE2 — белая тема. */
const WHITE_THEME_TITLE = "simple";

const TAG = "vt_keyboard_ru";

Java.perform(() => {
    const Log = Java.use("android.util.Log");
    const JavaSystem = Java.use("java.lang.System");

    // Повторная инъекция в ЖИВОЙ агент навесила бы хуки поверх первых (метку снимает dispose).
    if ("" + JavaSystem.getProperty(AGENT_MARK) === "1") {
        Log.w(TAG, "agent already installed in this process — skipping second injection");
        return;
    }

    const InputModeSwitcher = Java.use("com.qinggan.app.qgime.InputModeSwitcher");
    const SoftKey = Java.use("com.qinggan.app.qgime.SoftKey");
    const SoftKeyToggle = Java.use("com.qinggan.app.qgime.SoftKeyToggle");
    const SkbPool = Java.use("com.qinggan.app.qgime.SkbPool");
    const SoftKeyboard = Java.use("com.qinggan.app.qgime.SoftKeyboard");
    const KeyRow = Java.use("com.qinggan.app.qgime.SoftKeyboard$KeyRow");
    const SkbContainer = Java.use("com.qinggan.app.qgime.SkbContainer");
    const QingganIME = Java.use("com.qinggan.app.qgime.QingganIME");
    const EnglishInputProcessor = Java.use("com.qinggan.app.qgime.EnglishInputProcessor");
    const XmlKeyboardLoader = Java.use("com.qinggan.app.qgime.XmlKeyboardLoader");
    const QGInputConfig = Java.use("com.qinggan.app.qgime.QGInputConfig");
    const RDrawable = Java.use("com.qinggan.app.qgime.R$drawable");
    const RXml = Java.use("com.qinggan.app.qgime.R$xml");
    const ThemeManager = Java.use("com.qinggan.theme.ThemeManager");
    const QGToast = Java.use("com.pateo.material.dialog.QGToast");
    const ActivityThread = Java.use("android.app.ActivityThread");
    const JavaString = Java.use("java.lang.String");

    const context = Java.retain(ActivityThread.currentApplication().getApplicationContext());
    const resources = context.getResources();
    const packageName = context.getPackageName();
    const skbPool = SkbPool.getInstance();
    const inputModeSwitcher = InputModeSwitcher.getInstance();

    const drawableId = {
        pinyin: RDrawable.ime_pinyin.value,
        en: RDrawable.ime_en.value,
        shiftLowerWhite: RDrawable.shift_lower_c53_white.value,
        shiftUpperWhite: RDrawable.shift_uppercase_c53_white.value,
        shiftUpperTempWhite: RDrawable.shift_uppercase_c53_temp_white.value,
        englishWhite: RDrawable.english_input_method_white.value,
        hideKeyboardWhite: RDrawable.hide_keyboard_white.value,
    };
    const qwertyXmlIds = [RXml.skb_qwerty.value, RXml.skb_qwerty_no_voice.value];

    /** Текущая раскладка: "en" или "ru". */
    let currentLayout = "en";
    /** Раскладку только что переключили — надо перерисовать клавиатуру. */
    let layoutChanged = false;

    // -----------------------------------------------------------------------
    // Конфиги
    // -----------------------------------------------------------------------

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

    function loadConfig(path) {
        try {
            return JSON.parse(readTextFile(path));
        } catch (e) {
            Log.e(TAG, `Config ${path} unavailable: ${e}`);
            return null;
        }
    }

    const layoutConfig = loadConfig(LAYOUT_CONFIG_PATH);
    if (layoutConfig === null) return;
    JavaSystem.setProperty(AGENT_MARK, "1");
    // Метка живёт ровно столько, сколько скрипт: при выгрузке (frida-inject умер, loaderFrida
    // перезапущен) хуки откатываются, и следующая инъекция должна поставить их заново.
    rpc.exports.dispose = () => {
        Java.performNow(() => JavaSystem.clearProperty(AGENT_MARK));
        Log.i(TAG, "Agent disposed");
    };

    /** Разворачивает base64-картинки из конфига в BitmapDrawable (retain — живут весь процесс). */
    function decodeIconDrawables(config) {
        const drawables = {};
        if (config === null || !config.drawable) return drawables;
        const Base64 = Java.use("android.util.Base64");
        const BitmapFactory = Java.use("android.graphics.BitmapFactory");
        const BitmapDrawable = Java.use("android.graphics.drawable.BitmapDrawable");
        for (const name of ICON_NAMES) {
            const base64 = config.drawable[name];
            if (!base64) continue;
            try {
                const bytes = Base64.decode(base64, 0); // Base64.DEFAULT
                const bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                drawables[name] = Java.retain(BitmapDrawable.$new(resources, bitmap));
            } catch (e) {
                Log.e(TAG, `Icon ${name} decode error: ${e}`);
            }
        }
        return drawables;
    }

    const iconDrawables = decodeIconDrawables(loadConfig(ICON_CONFIG_PATH));

    // -----------------------------------------------------------------------
    // Хелперы
    // -----------------------------------------------------------------------

    function isRussianKeyCode(keyCode) {
        return keyCode >= RU_KEYCODE_MIN && keyCode <= RU_KEYCODE_MAX;
    }

    /** Клавиша с буквой: латиница основного ряда или русский псевдокод. */
    function isLetterKeyCode(keyCode) {
        return (keyCode >= LETTER_KEYCODE_MIN && keyCode <= LETTER_KEYCODE_MAX) || isRussianKeyCode(keyCode);
    }

    /** Таблица keyCode -> символ по JSON-описанию раскладки. */
    const keyCharByKeyCode = new Map();
    for (const row of layoutConfig.keyboard.rows) {
        for (const key of row.keys) {
            if (key.code !== undefined && key.label && isLetterKeyCode(key.code)) {
                keyCharByKeyCode.set(key.code, key.label);
            }
        }
    }

    function currentThemeTitle() {
        return "" + ThemeManager.getInstance(context).getCurrentThemeTitle();
    }

    /** "@drawable/foo" → id ресурса; не-ссылки возвращаются как есть, ошибки дают 0. */
    function resolveResourceId(reference) {
        if (typeof reference !== "string" || !reference.startsWith("@")) return reference;
        const match = reference.match(/^@(\w+)\/(.+)$/);
        if (!match) return 0;
        return resources.getIdentifier(match[2], match[1], packageName);
    }

    function drawableFor(reference) {
        const id = resolveResourceId(reference);
        return id ? context.getDrawable(id) : null;
    }

    /** Переводит буквенные клавиши всех рядов в верхний/нижний регистр. */
    function changeCaseForRows(keyRows, upperCase) {
        if (keyRows === null) return;
        for (let rowIndex = 0; rowIndex < keyRows.size(); rowIndex++) {
            const keys = Java.cast(keyRows.get(rowIndex), KeyRow).mSoftKeys.value;
            for (let keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
                const key = Java.cast(keys.get(keyIndex), SoftKey);
                if (isLetterKeyCode(key.getKeyCode())) key.changeCase(upperCase);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Сборка русской клавиатуры из JSON
    // -----------------------------------------------------------------------

    function findCachedRussianKeyboard(pool) {
        const keyboards = pool.mSoftKeyboards.value;
        for (let i = 0; i < keyboards.size(); i++) {
            const keyboard = Java.cast(keyboards.elementAt(i), SoftKeyboard);
            if (keyboard.getCacheId() === CUSTOM_SKB_CACHE_ID) return keyboard;
        }
        return null;
    }

    /** Иконка одного toggle-состояния клавиши (белая тема / кастомные иконки языка). */
    function pickToggleStateIcon(keyCode, stateId, whiteTheme) {
        const isShiftState = stateId === STATE_SHIFT_LOWER ||
            stateId === STATE_SHIFT_UPPER ||
            stateId === STATE_SHIFT_UPPER_TEMP;

        if (whiteTheme) {
            if (keyCode === 0) {
                switch (stateId) {
                    case STATE_SHIFT_LOWER: return context.getDrawable(drawableId.shiftLowerWhite);
                    case STATE_SHIFT_UPPER: return context.getDrawable(drawableId.shiftUpperWhite);
                    case STATE_SHIFT_UPPER_TEMP: return context.getDrawable(drawableId.shiftUpperTempWhite);
                    default: return null;
                }
            }
            if (keyCode === KEY_SWITCH_LANGUAGE && isShiftState) {
                return iconDrawables[ICON_RU_WHITE] || context.getDrawable(drawableId.englishWhite);
            }
            return null;
        }
        if (keyCode === KEY_SWITCH_LANGUAGE && isShiftState) return iconDrawables[ICON_RU] || null;
        return null;
    }

    /** Клавиша-переключатель со всеми её состояниями (связаны списком через mNextState). */
    function buildToggleKey(keyDef, keyCode, attrs, template, whiteTheme) {
        const key = SoftKeyToggle.$new();
        let previousState = null;
        let firstState = null;

        for (const stateDef of keyDef.toggle_states) {
            const state = key.createToggleState();
            const stateId = stateDef.state_id === undefined ? 0 : stateDef.state_id;

            state.setStateId(stateId);
            state.mKeyCode.value = stateDef.code === undefined ? 0 : stateDef.code;
            state.mKeyLabel.value = stateDef.label === undefined ? null : stateDef.label;

            const icon = pickToggleStateIcon(keyCode, stateId, whiteTheme)
                || (stateDef.icon ? drawableFor(stateDef.icon) : null);
            if (icon !== null) state.mKeyIcon.value = icon;
            if (stateDef.icon_popup) {
                const popup = drawableFor(stateDef.icon_popup);
                if (popup !== null) state.mKeyIconPopup.value = popup;
            }
            if (stateDef.key_type !== undefined) {
                state.mKeyType.value = template.getKeyType(stateDef.key_type);
            }
            state.setStateFlags(
                stateDef.repeat !== undefined ? stateDef.repeat : attrs.repeat,
                stateDef.balloon !== undefined ? stateDef.balloon : attrs.balloon);

            if (previousState) previousState.mNextState.value = state;
            else firstState = state;
            previousState = state;
        }

        if (firstState) key.setToggleStates(firstState);
        return key;
    }

    /** Собирает SoftKeyboard целиком по JSON-описанию; при ошибке null. */
    function buildRussianKeyboard(skbXmlId, skbWidth, skbHeight) {
        try {
            const whiteTheme = currentThemeTitle() === WHITE_THEME_TITLE;
            const attrs = layoutConfig.keyboard.attrs;
            const templateId = resolveResourceId(attrs.skb_template);
            const template = skbPool.getSkbTemplate(templateId, context);
            if (template === null) {
                Log.e(TAG, `SkbTemplate not found for ${attrs.skb_template} (ID: ${templateId})`);
                return null;
            }

            const keyboard = SoftKeyboard.$new(skbXmlId, template, skbWidth, skbHeight);
            keyboard.setFlags(
                attrs.skb_cache_flag,
                attrs.skb_sticky_flag === undefined ? true : attrs.skb_sticky_flag,
                attrs.qwerty,
                attrs.qwerty_uppercase);
            keyboard.setKeyMargins(attrs.key_xmargin, attrs.key_ymargin);

            let y = 0;
            for (const rowDef of layoutConfig.keyboard.rows) {
                let x = rowDef.start_pos_x === undefined ? 0 : rowDef.start_pos_x;
                if (rowDef.start_pos_y !== undefined) y = rowDef.start_pos_y;
                keyboard.beginNewRow(rowDef.row_id === undefined ? -1 : rowDef.row_id, y);

                for (const keyDef of rowDef.keys) {
                    const keyCode = keyDef.code === undefined ? 0 : keyDef.code;
                    let key;
                    if (keyDef.id !== undefined) {
                        key = template.getDefaultKey(keyDef.id);
                        if (key === null) continue;
                    } else if (keyDef.toggle_states) {
                        key = buildToggleKey(keyDef, keyCode, attrs, template, whiteTheme);
                    } else {
                        key = SoftKey.$new();
                    }

                    const width = keyDef.width === undefined || keyDef.width === null ? attrs.width : keyDef.width;
                    if (width < 2 * attrs.key_xmargin || attrs.height < 2 * attrs.key_ymargin) continue;

                    const softKey = Java.cast(key, SoftKey);
                    softKey.setKeyAttribute(
                        keyCode,
                        keyDef.label === undefined ? null : keyDef.label,
                        keyDef.repeat === undefined ? attrs.repeat : keyDef.repeat,
                        keyDef.balloon === undefined ? attrs.balloon : keyDef.balloon);

                    let icon = keyCode === KEY_HIDE_KEYBOARD && whiteTheme
                        ? context.getDrawable(drawableId.hideKeyboardWhite) : null;
                    if (icon === null && keyDef.icon) icon = drawableFor(keyDef.icon);
                    if (icon === null) icon = template.getDefaultKeyIcon(keyCode);
                    let iconPopup = keyDef.icon_popup ? drawableFor(keyDef.icon_popup) : null;
                    if (iconPopup === null) iconPopup = template.getDefaultKeyIconPopup(keyCode);

                    softKey.setPopupSkbId(0);
                    softKey.setKeyType(
                        template.getKeyType(keyDef.key_type === undefined ? (attrs.key_type || 0) : keyDef.key_type),
                        icon, iconPopup);
                    softKey.setKeyDimensions(x, y, x + width, y + attrs.height);
                    softKey.setSkbCoreSize(skbWidth, skbHeight);
                    softKey.changeCase(false);
                    x += width;

                    if (!keyboard.addSoftKey(softKey)) {
                        Log.e(TAG, `Failed to add key: ${keyDef.label || keyDef.id || keyCode}`);
                    }
                }
                y += attrs.height;
            }

            keyboard.setSkbCoreSize(skbWidth, skbHeight);
            return keyboard;
        } catch (e) {
            Log.e(TAG, `Keyboard build error: ${e}`);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Хуки
    // -----------------------------------------------------------------------

    // Русская клавиатура вместо штатной, пока выбрана раскладка ru.
    const getSoftKeyboard = SkbPool.getSoftKeyboard.overload("int", "int", "int", "int", "android.content.Context");
    getSoftKeyboard.implementation = function (skbTemplateId, skbXmlId, skbWidth, skbHeight, ctx) {
        let keyboard = null;
        if (currentLayout === "ru") {
            keyboard = findCachedRussianKeyboard(this);
            if (keyboard !== null) {
                keyboard.setSkbCoreSize(skbWidth, skbHeight);
                keyboard.setNewlyLoadedFlag(false);
            } else {
                keyboard = buildRussianKeyboard(skbXmlId, skbWidth, skbHeight);
                if (keyboard !== null) {
                    keyboard.setCacheId(CUSTOM_SKB_CACHE_ID);
                    this.mSoftKeyboards.value.add(keyboard);
                }
            }
        }
        // При сбое сборки остаёмся на штатной клавиатуре, а не роняем IME null-ом.
        if (keyboard === null) {
            keyboard = getSoftKeyboard.call(this, skbTemplateId, skbXmlId, skbWidth, skbHeight, ctx);
        }
        keyboard.disableToggleState(inputModeSwitcher.getTooggleStateForCnCand(), false);
        keyboard.enableToggleStates(inputModeSwitcher.getToggleStates());
        return keyboard;
    };

    // Клавиша смены языка переключает en/ru, на клавишу голоса — тост.
    const makeToast = QGToast.makeText.overload("android.content.Context", "java.lang.CharSequence", "int");
    const switchModeForUserKey = InputModeSwitcher.switchModeForUserKey.overload("int", "boolean");
    switchModeForUserKey.implementation = function (userKey, resetToIdle) {
        const previousLayout = currentLayout;

        if (userKey === KEY_SWITCH_LANGUAGE) {
            const inputMode = this.mInputMode.value;
            // из режима символов всегда возвращаемся в английский
            if (inputMode === MODE_SKB_SYMBOL1_EN || inputMode === MODE_SKB_SYMBOL2_EN) {
                currentLayout = "en";
                layoutChanged = previousLayout !== currentLayout;
                return switchModeForUserKey.call(this, userKey, resetToIdle);
            }
            currentLayout = currentLayout === "en" ? "ru" : "en";
            const icon = currentLayout === "ru" ? drawableId.pinyin : drawableId.en;
            this.mInputIcon.value = icon;
            layoutChanged = true;
            return icon;
        }

        if (userKey === KEY_SMILEY) {
            currentLayout = "en";
        } else if (userKey === KEY_VOICE) {
            const message = currentLayout === "ru"
                ? "Голосовой ввод недоступен для русского языка."
                : "Voice input is not available for English.";
            makeToast.call(QGToast, this.mImeService.value, JavaString.$new(message), 2).show();
            return this.mInputIcon.value;
        }

        layoutChanged = previousLayout !== currentLayout;
        return switchModeForUserKey.call(this, userKey, resetToIdle);
    };

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

    // После нажатия перерисовывает клавиатуру при смене раскладки и снимает
    // временный shift после русской буквы.
    const responseSoftKeyEvent = QingganIME.responseSoftKeyEvent.overload("com.qinggan.app.qgime.SoftKey");
    responseSoftKeyEvent.implementation = function (softKey) {
        responseSoftKeyEvent.call(this, softKey);
        if (layoutChanged) {
            this.mSkbContainer.value.updateInputMode();
            return;
        }
        if (currentLayout !== "ru" || !isRussianKeyCode(softKey.getKeyCode())) return;
        const switcher = this.mInputModeSwitcher.value;
        if (!switcher.isQwertyFirstMode()) return;
        switcher.switchModeForUserKey(KEY_SHIFT, true);
        this.resetToIdleState(false);
        this.mSkbContainer.value.updateInputMode();
    };

    // Перестраивает layout контейнера после смены раскладки.
    const updateInputMode = SkbContainer.updateInputMode.overload();
    updateInputMode.implementation = function () {
        updateInputMode.call(this);
        if (layoutChanged) {
            this.updateSkbLayout();
            layoutChanged = false;
        }
    };

    // Вместо латинской буквы коммитит русскую по таблице.
    const processKey = EnglishInputProcessor.processKey.overload(
        "android.view.inputmethod.InputConnection", "android.view.KeyEvent", "boolean", "boolean");
    processKey.implementation = function (inputConnection, keyEvent, upperCase, realAction) {
        if (currentLayout === "ru") {
            const keyCode = keyEvent.getKeyCode();
            const char = keyCharByKeyCode.get(keyCode);
            if (char !== undefined) {
                if (realAction) {
                    inputConnection.commitText(JavaString.$new(upperCase ? char.toUpperCase() : char.toLowerCase()), 1);
                }
                this.mLastKeyCode.value = keyCode;
                return true;
            }
        }
        return processKey.call(this, inputConnection, keyEvent, upperCase, realAction);
    };

    // Регистр русских клавиш штатный код не знает — обновляем сами.
    const switchQwertyMode = SoftKeyboard.switchQwertyMode.overload("int", "boolean");
    switchQwertyMode.implementation = function (qwerty, upperCase) {
        switchQwertyMode.call(this, qwerty, upperCase);
        if (currentLayout === "ru") changeCaseForRows(this.mKeyRows.value, upperCase);
    };
    const enableToggleStates = SoftKeyboard.enableToggleStates.overload(
        "com.qinggan.app.qgime.InputModeSwitcher$ToggleStates");
    enableToggleStates.implementation = function (toggleStates) {
        enableToggleStates.call(this, toggleStates);
        if (currentLayout === "ru") changeCaseForRows(this.mKeyRows.value, this.mIsQwertyUpperCase.value);
    };

    // Иконка клавиши смены языка во всех toggle-состояниях у skb_qwerty(_no_voice).
    const loadKeyboard = XmlKeyboardLoader.loadKeyboard.overload("int", "int", "int");
    loadKeyboard.implementation = function (resourceId, skbWidth, skbHeight) {
        const keyboard = loadKeyboard.call(this, resourceId, skbWidth, skbHeight);
        if (keyboard === null || qwertyXmlIds.indexOf(resourceId) < 0) return keyboard;
        try {
            const icon = currentThemeTitle() === WHITE_THEME_TITLE
                ? iconDrawables[ICON_EN_WHITE] : iconDrawables[ICON_EN];
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

    // На русской раскладке Enter показывает подпись из своего toggle-состояния
    // (штатный getKeyLabel для keyCode 66 подставляет свои строки по mIdAndFlags).
    const getKeyLabel = SoftKeyToggle.getKeyLabel.overload();
    getKeyLabel.implementation = function () {
        if (currentLayout === "ru" && this.mKeyCode.value === ENTER_KEYCODE) {
            const state = this.getToggleState();
            if (state !== null) return state.mKeyLabel.value;
        }
        return getKeyLabel.call(this);
    };

    // Голосовой ввод выключен: SkbContainer выбирает раскладки *_no_voice.
    QGInputConfig.DISABLE_VOICE.value = true;

    // Сбросить кэш раскладок, иначе патчи применятся только к новым клавиатурам.
    skbPool.resetCachedSkb();

    Log.i(TAG, `Agent started: ${keyCharByKeyCode.size} ru keys, icons=${Object.keys(iconDrawables).join(",")}`);
});
