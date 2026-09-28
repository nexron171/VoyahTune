"use strict";

/*
 * Frida-агент "keyboard-ru" для штатной клавиатуры com.qinggan.app.qgime.
 *
 * Добавляет в клавиатуру русскую раскладку, которой в прошивке нет:
 *   * собирает русскую клавиатуру целиком из JSON-описания
 *     (voyahtune_skb_qwerty_ru.json) и кэширует её в SkbPool;
 *   * вешает переключение en/ru на клавишу смены языка и подменяет её иконку
 *     картинками из voyahtune_keyboard_ru_config.json;
 *   * подставляет русские буквы вместо латинских при вводе (по таблице
 *     keyCode -> символ, построенной из того же JSON);
 *   * гасит голосовой ввод, показывая вместо него тост.
 *
 * Это развёрнутая, читаемая версия keyboard_ru.js: поведение то же самое,
 * отличаются только имена и форматирование. Файл самодостаточен, его можно
 * скармливать frida-inject напрямую.
 *
 * Про запуск: frida-inject ждёт возврата из rpc.exports.init() прежде чем
 * сделать eternalize скрипта, поэтому init() только планирует запуск и сразу
 * отдаёт управление, а хуки ставятся отдельным тиком. Подробности в run.sh.
 */


// ---------------------------------------------------------------------------
// Константы
// ---------------------------------------------------------------------------

/** Информационные метаданные агента (используются внешним загрузчиком). */
const AGENT_META = {
    id: "keyboard-ru",
    process: "com.qinggan.app.qgime",
    boot: false,
};

const LAYOUT_CONFIG_PATH = "/data/local/bin/voyahtune_skb_qwerty_ru.json";
const ICON_CONFIG_PATH = "/data/local/bin/voyahtune_keyboard_ru_config.json";

/** Маркер готовности: по нему run.sh понимает, что хуки встали. */
const READY_MARKER_PATH = "/data/local/bin/.keyboard_ru.ready";

/** Метка "агент уже установлен в этом процессе" (system property). */
const INSTALL_SENTINEL = "frida.agent." + AGENT_META.id;

/** Задержка перед установкой хуков — чтобы init() успел вернуть управление. */
const AGENT_START_DELAY_MS = 300;

/** Если init() так и не позвали, запускаемся сами через это время. */
const RPC_INIT_TIMEOUT_MS = 2000;

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

/** KEYCODE_ENTER. */
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

// ---------------------------------------------------------------------------
// Логирование
// ---------------------------------------------------------------------------

class Logger {
    constructor(source) {
        this.source = source;
    }

    getTimestamp() {
        const now = new Date();
        const pad = (value, width) => String(value).padStart(width, "0");
        return `${now.getFullYear()}-${pad(now.getMonth() + 1, 2)}-${pad(now.getDate(), 2)} ` +
            `${pad(now.getHours(), 2)}:${pad(now.getMinutes(), 2)}:${pad(now.getSeconds(), 2)}.` +
            `${pad(now.getMilliseconds(), 3)}`;
    }

    error(message) {
        // console.log после detach ничего не делает, но кидать не должен
        try {
            console.log(`${this.getTimestamp()} [-] ${this.source}: ${message}`);
        } catch (ignored) {}
    }

    info(message) {
        try {
            console.log(`${this.getTimestamp()} [+] ${this.source}: ${message}`);
        } catch (ignored) {}
    }

    debug(message) {
        // отладочный вывод выключен
    }
}

const utilsLog = new Logger("utils");
const bootLog = new Logger("runAgent");
const log = new Logger("keyboard-ru-mod");

// ---------------------------------------------------------------------------
// Состояние агента
// ---------------------------------------------------------------------------

/** Параметры, переданные через rpc.exports.init() (frida-inject -P). */
let rpcParameters = null;

// Java-классы, которые нужны в нескольких местах
let InputModeSwitcher = null;
let SoftKey = null;
let SoftKeyToggle = null;
let SkbPool = null;
let SoftKeyboard = null;
let QingganIME = null;
let ActivityThread = null;
let KeyRow = null;
let RDrawable = null;
let RXml = null;
let ThemeManager = null;

// Синглтоны приложения
let skbPool = null;
let inputModeSwitcher = null;

/** Идентификаторы английских режимов ввода. */
let englishModes = null;

/** Заголовок белой темы и иконка "ime_pinyin", которую переиспользуем под ru. */
let whiteThemeTitle = null;
let pinyinIconId = null;

/** JSON-описание русской раскладки. */
let layoutConfig = null;

/** Таблица keyCode -> символ для русской раскладки. */
let keyCharByKeyCode = null;

/** Иконки из конфига: имя -> BitmapDrawable. */
let iconDrawables = null;

/** Текущая раскладка: "en" или "ru". */
let currentLayout = null;

/** Раскладку только что переключили — надо перерисовать клавиатуру. */
let layoutChanged = false;

// ---------------------------------------------------------------------------
// Работа с полями Java-классов
// ---------------------------------------------------------------------------

/**
 * Читает статическое или обычное поле: frida оборачивает поля в объект с
 * .value, но не всегда, поэтому проверяем оба варианта.
 */
function getField(target, fieldName) {
    try {
        if (target[fieldName] && target[fieldName].value !== undefined) {
            return target[fieldName].value;
        }
        return target[fieldName];
    } catch (e) {
        utilsLog.error(`Unable to get field value: ${fieldName}`);
        return undefined;
    }
}

/** Пишет поле с той же оговоркой про обёртку .value. */
function setField(target, fieldName, value) {
    try {
        if (target[fieldName] && target[fieldName].value !== undefined) {
            target[fieldName].value = value;
            return;
        }
        target[fieldName] = value;
    } catch (e) {
        utilsLog.error(`Unable to set field value: ${fieldName} ${value}`);
    }
}

// ---------------------------------------------------------------------------
// Загрузка конфигов
// ---------------------------------------------------------------------------

/** Читает текстовый файл средствами Java (у процесса IME свои права). */
function readTextFile(path) {
    const FileInputStream = Java.use("java.io.FileInputStream");
    const InputStreamReader = Java.use("java.io.InputStreamReader");
    const BufferedReader = Java.use("java.io.BufferedReader");

    const reader = BufferedReader.$new(InputStreamReader.$new(FileInputStream.$new(path)));
    let text = "";
    let line;
    while ((line = reader.readLine()) !== null) {
        text += line + "\n";
    }
    reader.close();
    return text;
}

/**
 * Достаёт текст конфига: сначала из параметров rpc (config или configPath),
 * иначе из файла по умолчанию.
 */
function readConfigText(defaultPath) {
    const params = rpcParameters;
    if (params) {
        utilsLog.debug("Found params from rpc.exports.init()");
        utilsLog.debug(`Params found: ${JSON.stringify(params)}`);

        if (params.config !== undefined) {
            const config = params.config;
            utilsLog.debug("Config loaded from parameter");
            utilsLog.debug(`Config type: ${typeof config}, value: ${JSON.stringify(config)}`);
            if (typeof config === "object" && config !== null) {
                return JSON.stringify(config);
            }
            if (typeof config === "string") {
                return config;
            }
        }

        if (params.configPath) {
            utilsLog.debug(`Config loading from custom path: ${params.configPath}`);
            return readTextFile(params.configPath);
        }
    }

    if (!defaultPath) return null;

    try {
        utilsLog.debug(`Config loading from default path: ${defaultPath}`);
        return readTextFile(defaultPath);
    } catch (e) {
        utilsLog.debug(`Default config not available: ${defaultPath}`);
        return null;
    }
}

/** Читает и разбирает JSON-конфиг; при любой ошибке возвращает null. */
function loadConfig(defaultPath, logger = null) {
    const out = logger || utilsLog;
    try {
        const text = readConfigText(defaultPath);
        if (text === null) {
            out.debug("No config available");
            return null;
        }
        try {
            const config = JSON.parse(text);
            out.info("Config loaded");
            return config;
        } catch (e) {
            out.error(`Error loading config: ${e.message}`);
            return null;
        }
    } catch (e) {
        out.debug(`Config loading failed: ${e.message}`);
        return null;
    }
}

// ---------------------------------------------------------------------------
// Хелперы предметной области
// ---------------------------------------------------------------------------

/** Клавиша с буквой: латиница основного ряда или русский псевдокод. */
function isLetterKeyCode(keyCode) {
    if (keyCode >= LETTER_KEYCODE_MIN && keyCode <= LETTER_KEYCODE_MAX) return true;
    return keyCode >= RU_KEYCODE_MIN && keyCode <= RU_KEYCODE_MAX;
}

/** Псевдокод русской буквы. */
function isRussianKeyCode(keyCode) {
    return typeof keyCode === "number" && keyCode >= RU_KEYCODE_MIN && keyCode <= RU_KEYCODE_MAX;
}

/** Строит таблицу keyCode -> символ по JSON-описанию раскладки. */
function createQwertyToJcuken(config) {
    const map = {};
    for (const row of config.keyboard.rows) {
        for (const key of row.keys) {
            if (key.code === undefined) continue;
            if (key.label === undefined || key.label === null) continue;
            if (key.label === "") continue;
            if (!isLetterKeyCode(key.code)) continue;
            map[key.code] = key.label;
        }
    }
    return map;
}

/** Символ русской раскладки для keyCode, либо null. */
function resolveKeyChar(keyCode, map) {
    if (!map || typeof map !== "object") return null;
    if (typeof keyCode !== "number") return null;
    return Object.prototype.hasOwnProperty.call(map, keyCode) ? map[keyCode] : null;
}

function hasIcon(name) {
    return Object.prototype.hasOwnProperty.call(iconDrawables, name);
}

/**
 * Превращает ссылку вида "@drawable/foo" в числовой id ресурса.
 * Не-строки и строки без "@" возвращаются как есть, ошибки дают 0.
 */
function resolveResourceId(reference, context) {
    const resources = context.getResources();
    const packageName = context.getPackageName();

    if (typeof reference !== "string" || !reference.startsWith("@")) return reference;

    try {
        const match = reference.match(/^@(\w+)\/(.+)$/);
        if (!match) {
            log.debug(`Invalid resource reference: ${reference}`);
            return 0;
        }
        const [, type, name] = match;
        const id = resources.getIdentifier(name, type, packageName);
        if (id === 0) {
            log.debug(`Resource not found: ${reference} (${type}/${name}) in package ${packageName}`);
        }
        return id;
    } catch (e) {
        log.error(`Error resolving resource ${reference}: ${e}`);
        return 0;
    }
}

/** Переводит буквенные клавиши всех рядов в верхний/нижний регистр. */
function changeCaseForRows(keyRows, upperCase) {
    for (let rowIndex = 0; rowIndex < keyRows.size(); rowIndex++) {
        const row = Java.cast(keyRows.get(rowIndex), KeyRow);
        const keys = getField(row, "mSoftKeys");
        for (let keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
            const key = Java.cast(getField(row, "mSoftKeys").get(keyIndex), SoftKey);
            if (isLetterKeyCode(key.getKeyCode())) {
                key.changeCase(upperCase);
            }
        }
    }
}

/** Разворачивает base64-картинки из конфига в BitmapDrawable. */
function decodeIconDrawables(configJson) {
    const Base64 = Java.use("android.util.Base64");
    const BitmapFactory = Java.use("android.graphics.BitmapFactory");
    const BitmapDrawable = Java.use("android.graphics.drawable.BitmapDrawable");
    const context = ActivityThread.currentApplication().getApplicationContext();
    const drawables = {};

    try {
        const encodedIcons = JSON.parse(configJson).drawable;
        for (const name of ICON_NAMES) {
            if (!Object.prototype.hasOwnProperty.call(encodedIcons, name)) continue;
            const base64 = encodedIcons[name];
            if (base64 === "") continue;

            const bytes = Base64.decode(base64, getField(Base64, "DEFAULT"));
            const bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            drawables[name] = BitmapDrawable.$new(context.getResources(), bitmap);
        }
    } catch (e) {
        log.error(`Error loading icon config: ${e.message}`);
        return null;
    }
    return drawables;
}

// ---------------------------------------------------------------------------
// Инициализация
// ---------------------------------------------------------------------------

/**
 * Резолвит классы и читает конфиги.
 * Возвращает false, если без описания раскладки работать бессмысленно.
 */
function initialize() {
    InputModeSwitcher = Java.use("com.qinggan.app.qgime.InputModeSwitcher");
    SoftKey = Java.use("com.qinggan.app.qgime.SoftKey");
    SoftKeyToggle = Java.use("com.qinggan.app.qgime.SoftKeyToggle");
    SkbPool = Java.use("com.qinggan.app.qgime.SkbPool");
    SoftKeyboard = Java.use("com.qinggan.app.qgime.SoftKeyboard");
    QingganIME = Java.use("com.qinggan.app.qgime.QingganIME");
    ThemeManager = Java.use("com.qinggan.theme.ThemeManager");
    ActivityThread = Java.use("android.app.ActivityThread");
    KeyRow = Java.use("com.qinggan.app.qgime.SoftKeyboard$KeyRow");
    RDrawable = Java.use("com.qinggan.app.qgime.R$drawable");
    RXml = Java.use("com.qinggan.app.qgime.R$xml");

    skbPool = SkbPool.getInstance();
    inputModeSwitcher = InputModeSwitcher.getInstance();

    englishModes = {
        lower: getField(InputModeSwitcher, "MODE_SKB_ENGLISH_LOWER"),
        upper: getField(InputModeSwitcher, "MODE_SKB_ENGLISH_UPPER"),
        first: getField(InputModeSwitcher, "MODE_SKB_ENGLISH_FIRST"),
        hkb: getField(InputModeSwitcher, "MODE_HKB_ENGLISH"),
        symbol1: getField(InputModeSwitcher, "MODE_SKB_SYMBOL1_EN"),
        symbol2: getField(InputModeSwitcher, "MODE_SKB_SYMBOL2_EN"),
    };

    whiteThemeTitle = getField(ThemeManager, "DEFAULT_THEME_TITLE2");
    pinyinIconId = getField(RDrawable, "ime_pinyin");

    layoutConfig = loadConfig(LAYOUT_CONFIG_PATH, log);
    if (!layoutConfig) {
        log.error("Keyboard config not available");
        return false;
    }
    keyCharByKeyCode = createQwertyToJcuken(layoutConfig);

    const iconConfig = loadConfig(ICON_CONFIG_PATH, log);
    if (iconConfig) {
        iconDrawables = decodeIconDrawables(JSON.stringify(iconConfig));
    }

    currentLayout = "en";
    return true;
}

// ---------------------------------------------------------------------------
// Сборка русской клавиатуры из JSON
// ---------------------------------------------------------------------------

/** Ищет уже собранную русскую клавиатуру в кэше SkbPool. */
function findCachedRussianKeyboard() {
    const keyboards = getField(skbPool, "mSoftKeyboards");
    for (let i = 0; i < keyboards.size(); i++) {
        const keyboard = Java.cast(keyboards.elementAt(i), SoftKeyboard);
        if (keyboard.getCacheId() === CUSTOM_SKB_CACHE_ID) return keyboard;
    }
    return null;
}

/** Выбирает иконку для одного toggle-состояния клавиши. */
function pickToggleStateIcon(keyCode, stateId, context, themeTitle) {
    const isShiftState = stateId === STATE_SHIFT_LOWER ||
        stateId === STATE_SHIFT_UPPER ||
        stateId === STATE_SHIFT_UPPER_TEMP;

    if (whiteThemeTitle === themeTitle) {
        if (keyCode === 0) {
            switch (stateId) {
                case STATE_SHIFT_LOWER:
                    return context.getDrawable(getField(RDrawable, "shift_lower_c53_white"));
                case STATE_SHIFT_UPPER:
                    return context.getDrawable(getField(RDrawable, "shift_uppercase_c53_white"));
                case STATE_SHIFT_UPPER_TEMP:
                    return context.getDrawable(getField(RDrawable, "shift_uppercase_c53_temp_white"));
                default:
                    return null;
            }
        }
        if (keyCode === KEY_SWITCH_LANGUAGE && isShiftState) {
            return hasIcon(ICON_RU_WHITE)
                ? iconDrawables[ICON_RU_WHITE]
                : context.getDrawable(getField(RDrawable, "english_input_method_white"));
        }
        return null;
    }

    if (keyCode === KEY_SWITCH_LANGUAGE && isShiftState && hasIcon(ICON_RU)) {
        return iconDrawables[ICON_RU];
    }
    return null;
}

/** Создаёт клавишу-переключатель со всеми её состояниями. */
function buildToggleKey(keyDef, keyCode, attrs, template, context, themeTitle) {
    const key = SoftKeyToggle.$new();
    let previousState = null;
    let firstState = null;

    for (const stateDef of keyDef.toggle_states) {
        const state = key.createToggleState();
        const stateId = stateDef.state_id === undefined ? 0 : stateDef.state_id;

        state.setStateId(stateId);
        setField(state, "mKeyCode", stateDef.code === undefined ? 0 : stateDef.code);
        setField(state, "mKeyLabel", stateDef.label === undefined ? null : stateDef.label);

        const icon = pickToggleStateIcon(keyCode, stateId, context, themeTitle);
        if (icon !== null) {
            setField(state, "mKeyIcon", icon);
        } else if (stateDef.icon) {
            const iconId = resolveResourceId(stateDef.icon, context);
            if (iconId) setField(state, "mKeyIcon", context.getDrawable(iconId));
        }

        if (stateDef.icon_popup) {
            const popupId = resolveResourceId(stateDef.icon_popup, context);
            if (popupId) setField(state, "mKeyIconPopup", context.getDrawable(popupId));
        }

        if (stateDef.key_type !== undefined) {
            setField(state, "mKeyType", template.getKeyType(stateDef.key_type));
        }

        const repeat = stateDef.repeat !== undefined ? stateDef.repeat : attrs.repeat;
        const balloon = stateDef.balloon !== undefined ? stateDef.balloon : attrs.balloon;
        state.setStateFlags(repeat, balloon);

        // состояния связываются в список через mNextState
        if (previousState) {
            setField(previousState, "mNextState", state);
        } else {
            firstState = state;
        }
        previousState = state;
    }

    if (firstState) key.setToggleStates(firstState);
    return key;
}

/** Собирает SoftKeyboard целиком по JSON-описанию. */
function buildRussianKeyboard(skbXmlId, context, skbWidth, skbHeight, config) {
    const themeTitle = ThemeManager.getInstance(context).getCurrentThemeTitle();

    try {
        const attrs = config.keyboard.attrs;
        const rows = config.keyboard.rows;

        const templateId = resolveResourceId(attrs.skb_template, context);
        const template = SkbPool.getInstance().getSkbTemplate(templateId, context);
        if (!template) {
            log.error(`SkbTemplate not found for ${attrs.skb_template} (ID: ${templateId})`);
            return null;
        }

        const keyboard = SoftKeyboard.$new(skbXmlId, template, skbWidth, skbHeight);
        keyboard.setFlags(
            attrs.skb_cache_flag,
            attrs.skb_sticky_flag === undefined ? true : attrs.skb_sticky_flag,
            attrs.qwerty,
            attrs.qwerty_uppercase);
        keyboard.setKeyMargins(attrs.key_xmargin, attrs.key_ymargin);

        let x = 0;
        let y = 0;

        for (const rowDef of rows) {
            const rowId = rowDef.row_id === undefined ? -1 : rowDef.row_id;
            x = rowDef.start_pos_x === undefined ? 0 : rowDef.start_pos_x;
            y = rowDef.start_pos_y === undefined ? y : rowDef.start_pos_y;
            keyboard.beginNewRow(rowId, y);

            for (const keyDef of rowDef.keys) {
                const keyCode = keyDef.code === undefined ? 0 : keyDef.code;
                let key = null;

                if (keyDef.id !== undefined) {
                    key = template.getDefaultKey(keyDef.id);
                    if (!key) {
                        log.debug(`getDefaultKey returned null for id: ${keyDef.id}`);
                        continue;
                    }
                } else if (keyDef.toggle_states) {
                    key = buildToggleKey(keyDef, keyCode, attrs, template, context, themeTitle);
                } else {
                    key = SoftKey.$new();
                }

                const softKey = Java.cast(key, SoftKey);
                softKey.setKeyAttribute(
                    keyCode,
                    keyDef.label === undefined ? null : keyDef.label,
                    keyDef.repeat === undefined ? attrs.repeat : keyDef.repeat,
                    keyDef.balloon === undefined ? attrs.balloon : keyDef.balloon);

                if (keyDef.popup_skb) {
                    softKey.setPopupSkbId(resolveResourceId(keyDef.popup_skb, context));
                }

                const keyTypeId = keyDef.key_type === undefined
                    ? (attrs.key_type || 0)
                    : keyDef.key_type;
                const keyType = template.getKeyType(keyTypeId);

                let icon = null;
                let iconPopup = null;

                if (keyCode === KEY_HIDE_KEYBOARD && whiteThemeTitle === themeTitle) {
                    icon = context.getDrawable(getField(RDrawable, "hide_keyboard_white"));
                }
                if (icon === null && keyDef.icon) {
                    const iconId = resolveResourceId(keyDef.icon, context);
                    if (iconId) icon = context.getDrawable(iconId);
                }
                if (keyDef.icon_popup) {
                    const popupId = resolveResourceId(keyDef.icon_popup, context);
                    if (popupId) iconPopup = context.getDrawable(popupId);
                }
                if (icon === null) icon = template.getDefaultKeyIcon(keyCode);
                if (iconPopup === null) iconPopup = template.getDefaultKeyIconPopup(keyCode);

                softKey.setPopupSkbId(0);
                softKey.setKeyType(keyType, icon, iconPopup);

                const width = keyDef.width === undefined || keyDef.width === null
                    ? attrs.width
                    : keyDef.width;
                const height = attrs.height;
                const right = x + width;
                const bottom = y + height;

                const tooSmall = right - x < 2 * attrs.key_xmargin ||
                    bottom - y < 2 * attrs.key_ymargin;
                if (tooSmall) {
                    log.debug(`Key too small: ${keyDef.label || keyDef.id || "unknown"}`);
                    continue;
                }

                softKey.setKeyDimensions(x, y, right, bottom);
                softKey.setSkbCoreSize(skbWidth, skbHeight);
                softKey.changeCase(false);
                x = right;

                if (!keyboard.addSoftKey(softKey)) {
                    log.error(`Failed to add key: ${keyDef.label || keyDef.id || "unknown"}`);
                }
            }

            y += attrs.height;
        }

        keyboard.disableToggleState(inputModeSwitcher.getTooggleStateForCnCand(), false);
        keyboard.enableToggleStates(inputModeSwitcher.getToggleStates());
        keyboard.setSkbCoreSize(skbWidth, skbHeight);
        return keyboard;
    } catch (e) {
        log.error(`Keyboard build error: ${e}`);
        return null;
    }
}

// ---------------------------------------------------------------------------
// Патчи
// ---------------------------------------------------------------------------

/** Отдаёт русскую клавиатуру вместо штатной, пока выбрана раскладка ru. */
function hookGetSoftKeyboard() {
    const overload = SkbPool.getSoftKeyboard.overload(
        "int", "int", "int", "int", "android.content.Context");

    overload.implementation = function (skbTemplateId, skbXmlId, skbWidth, skbHeight, context) {
        let keyboard = null;

        if (currentLayout !== "ru") {
            keyboard = this.getSoftKeyboard
                .overload("int", "int", "int", "int", "android.content.Context")
                .call(this, skbTemplateId, skbXmlId, skbWidth, skbHeight, context);
        } else {
            try {
                keyboard = findCachedRussianKeyboard();
                if (keyboard === null) {
                    keyboard = buildRussianKeyboard(skbXmlId, context, skbWidth, skbHeight, layoutConfig);
                    keyboard.setCacheId(CUSTOM_SKB_CACHE_ID);
                    getField(this, "mSoftKeyboards").add(keyboard);
                } else {
                    keyboard.setSkbCoreSize(skbWidth, skbHeight);
                    keyboard.setNewlyLoadedFlag(false);
                }
            } catch (e) {
                log.error(`Keyboard build error: ${e}`);
                log.error(e.stack);
            }
        }

        keyboard.disableToggleState(inputModeSwitcher.getTooggleStateForCnCand(), false);
        keyboard.enableToggleStates(inputModeSwitcher.getToggleStates());
        return keyboard;
    };
}

/** Клавиша смены языка переключает en/ru, голосовой ввод заменён тостом. */
function hookSwitchModeForUserKey() {
    const QGToast = Java.use("com.pateo.material.dialog.QGToast");

    InputModeSwitcher.switchModeForUserKey.implementation = function (userKey, resetToIdle) {
        const previousLayout = currentLayout;

        if (userKey === KEY_SWITCH_LANGUAGE) {
            const inputMode = getField(this, "mInputMode");

            // из режима символов всегда возвращаемся в английский
            if (inputMode === englishModes.symbol1 || inputMode === englishModes.symbol2) {
                currentLayout = "en";
                layoutChanged = previousLayout !== currentLayout;
                return this.switchModeForUserKey.call(this, userKey, resetToIdle);
            }

            currentLayout = currentLayout === "en" ? "ru" : "en";
            const icon = currentLayout === "ru" ? pinyinIconId : getField(RDrawable, "ime_en");
            setField(this, "mInputIcon", icon);
            layoutChanged = previousLayout !== currentLayout;
            return icon;
        }

        if (userKey === KEY_SMILEY) {
            currentLayout = "en";
        } else if (userKey === KEY_VOICE) {
            const message = currentLayout === "ru"
                ? "Голосовой ввод недоступен для русского языка."
                : "Voice input is not available for English.";
            QGToast.makeText
                .overload("android.content.Context", "java.lang.CharSequence", "int")
                .call(QGToast, getField(this, "mImeService"), message, 2)
                .show();
            return getField(this, "mInputIcon");
        }

        layoutChanged = previousLayout !== currentLayout;
        return this.switchModeForUserKey.call(this, userKey, resetToIdle);
    };
}

/** Не даёт сохранить не-английский режим ввода. */
function hookSaveInputMode() {
    InputModeSwitcher.saveInputMode.implementation = function (mode) {
        const isEnglish = mode === englishModes.lower ||
            mode === englishModes.upper ||
            mode === englishModes.first ||
            mode === englishModes.hkb ||
            mode === englishModes.symbol1 ||
            mode === englishModes.symbol2;
        if (!isEnglish) {
            mode = englishModes.first;
        }
        return this.saveInputMode.call(this, mode);
    };
}

/**
 * После нажатия клавиши перерисовывает клавиатуру, если сменилась раскладка,
 * и снимает временный shift после русской буквы.
 */
function hookResponseSoftKeyEvent() {
    QingganIME.responseSoftKeyEvent.implementation = function (softKey) {
        this.responseSoftKeyEvent.call(this, softKey);

        if (layoutChanged) {
            getField(this, "mSkbContainer").updateInputMode();
            return;
        }
        if (currentLayout !== "ru") return;

        const keyCode = softKey.getKeyCode();
        if (!getField(this, "mInputModeSwitcher").isQwertyFirstMode()) return;
        if (!isRussianKeyCode(keyCode)) return;

        getField(this, "mInputModeSwitcher").switchModeForUserKey(KEY_SHIFT, true);
        this.resetToIdleState(false);
        getField(this, "mSkbContainer").updateInputMode();
    };
}

/** Перестраивает layout контейнера после смены раскладки. */
function hookUpdateInputMode() {
    const SkbContainer = Java.use("com.qinggan.app.qgime.SkbContainer");

    SkbContainer.updateInputMode.implementation = function () {
        this.updateInputMode.call(this);
        if (layoutChanged) {
            this.updateSkbLayout();
            layoutChanged = false;
        }
    };
}

/** Вместо латинской буквы коммитит русскую по таблице. */
function hookProcessKey() {
    const EnglishInputProcessor = Java.use("com.qinggan.app.qgime.EnglishInputProcessor");
    const JavaString = Java.use("java.lang.String");

    EnglishInputProcessor.processKey.implementation =
        function (inputConnection, keyEvent, upperCase, realAction) {
            if (currentLayout !== "ru") {
                return this.processKey.call(this, inputConnection, keyEvent, upperCase, realAction);
            }

            const keyCode = keyEvent.getKeyCode();
            const char = resolveKeyChar(keyCode, keyCharByKeyCode);
            if (char === null) {
                return this.processKey.call(this, inputConnection, keyEvent, upperCase, realAction);
            }

            const text = upperCase ? char.toUpperCase() : char.toLowerCase();
            if (realAction) {
                inputConnection.commitText(JavaString.$new(text), 1);
            }
            setField(this, "mLastKeyCode", keyCode);
            return true;
        };
}

/** Регистр русских клавиш штатный код не знает — обновляем сами. */
function hookQwertyCaseChanges() {
    SoftKeyboard.switchQwertyMode.implementation = function (qwerty, upperCase) {
        this.switchQwertyMode.call(this, qwerty, upperCase);
        if (currentLayout === "ru") {
            changeCaseForRows(getField(this, "mKeyRows"), upperCase);
        }
    };

    SoftKeyboard.enableToggleStates.implementation = function (toggleStates) {
        this.enableToggleStates.call(this, toggleStates);
        if (currentLayout !== "ru") return;
        changeCaseForRows(getField(this, "mKeyRows"), getField(this, "mIsQwertyUpperCase"));
    };
}

/**
 * Подменяет иконку клавиши переключения раскладки во всех её toggle-состояниях
 * у раскладок skb_qwerty и skb_qwerty_no_voice.
 */
function patchLayoutToggleIcon() {
    const XmlKeyboardLoader = Java.use("com.qinggan.app.qgime.XmlKeyboardLoader");
    const ToggleState = Java.use("com.qinggan.app.qgime.SoftKeyToggle$ToggleState");
    const List = Java.use("java.util.List");

    const context = ActivityThread.currentApplication().getApplicationContext();
    const currentThemeTitle = ThemeManager.getInstance(context).getCurrentThemeTitle();

    // нужные поля приватные, поэтому через рефлексию
    const keyRowsField = SoftKeyboard.class.getDeclaredField("mKeyRows");
    keyRowsField.setAccessible(true);
    const softKeysField = KeyRow.class.getDeclaredField("mSoftKeys");
    softKeysField.setAccessible(true);
    const toggleStateField = SoftKeyToggle.class.getDeclaredField("mToggleState");
    toggleStateField.setAccessible(true);
    const keyIconField = ToggleState.class.getDeclaredField("mKeyIcon");
    keyIconField.setAccessible(true);
    const nextStateField = ToggleState.class.getDeclaredField("mNextState");
    nextStateField.setAccessible(true);

    XmlKeyboardLoader.loadKeyboard.implementation = function (resourceId, skbWidth, skbHeight) {
        const keyboard = this.loadKeyboard.call(this, resourceId, skbWidth, skbHeight);

        const isQwerty = resourceId === getField(RXml, "skb_qwerty") ||
            resourceId === getField(RXml, "skb_qwerty_no_voice");
        if (!isQwerty) return keyboard;

        const rows = Java.cast(keyRowsField.get(keyboard), List);
        for (let rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            const keys = Java.cast(softKeysField.get(rows.get(rowIndex)), List);

            for (let keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
                const key = keys.get(keyIndex);
                if (key.getClass().getName() !== "com.qinggan.app.qgime.SoftKeyToggle") continue;

                const toggleKey = Java.cast(key, SoftKeyToggle);
                if (toggleKey.getKeyCode() !== KEY_SWITCH_LANGUAGE) continue;

                let icon = null;
                if (currentThemeTitle === whiteThemeTitle) {
                    if (hasIcon(ICON_EN_WHITE)) icon = iconDrawables[ICON_EN_WHITE];
                } else {
                    if (hasIcon(ICON_EN)) icon = iconDrawables[ICON_EN];
                }
                if (!icon) continue;

                // состояния клавиши связаны в список через mNextState
                let state = Java.cast(toggleStateField.get(toggleKey), ToggleState);
                while (state !== null) {
                    keyIconField.set(state, icon);
                    const nextState = nextStateField.get(state);
                    if (nextState === null) break;
                    state = Java.cast(nextState, ToggleState);
                }
            }
        }
        return keyboard;
    };
}

/** На русской раскладке Enter показывает подпись из своего toggle-состояния. */
function hookEnterKeyLabel() {
    SoftKeyToggle.getKeyLabel.implementation = function () {
        if (currentLayout !== "ru") return this.getKeyLabel.call(this);
        if (getField(this, "mKeyCode") !== ENTER_KEYCODE) return this.getKeyLabel.call(this);

        const state = this.getToggleState();
        if (state === null) return this.getKeyLabel.call(this);
        return getField(state, "mKeyLabel");
    };
}

/** Выключает голосовой ввод. */
function disableVoiceInput() {
    setField(Java.use("com.qinggan.app.qgime.QGInputConfig"), "DISABLE_VOICE", true);
}

// ---------------------------------------------------------------------------
// Точка входа
// ---------------------------------------------------------------------------

function main() {
    log.info("Agent starting");

    if (!initialize()) return;

    hookGetSoftKeyboard();
    hookSwitchModeForUserKey();
    hookSaveInputMode();
    hookResponseSoftKeyEvent();
    hookUpdateInputMode();
    hookProcessKey();
    hookQwertyCaseChanges();

    try {
        patchLayoutToggleIcon();
    } catch (e) {
        log.error(`loadKeyboardHook failed: ${e.message}`);
    }

    hookEnterKeyLabel();
    disableVoiceInput();

    // сбросить кэш раскладок, иначе патчи применятся только к новым клавиатурам
    skbPool.resetCachedSkb();

    log.info("Agent started");
}
Java.perform(() => {
                const System = Java.use("java.lang.System");
                main();
});
// ---------------------------------------------------------------------------
// Запуск
// ---------------------------------------------------------------------------

