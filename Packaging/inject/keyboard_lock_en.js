"use strict";

/*
 * Frida-агент "keyboard-lock-en" для штатной клавиатуры com.qinggan.app.qgime.
 *
 * Делает три вещи:
 *   1. блокирует раскладку на английской: любая попытка сохранить не-английский
 *      режим ввода подменяется на MODE_SKB_ENGLISH_FIRST;
 *   2. подменяет иконку клавиши переключения раскладки на картинку из конфига
 *      (base64 в voyahtune_keyboard_en_config.json), отдельно для тёмной и
 *      белой темы;
 *   3. выключает голосовой ввод (QGInputConfig.DISABLE_VOICE).
 *
 * Это развёрнутая, читаемая версия keyboard_lock_en.js: поведение то же самое,
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


const ICON_CONFIG_PATH = "/data/local/bin/voyahtune_keyboard_en_config.json";

/** Маркер готовности: по нему run.sh понимает, что хуки встали. */
const READY_MARKER_PATH = "/data/local/bin/.keyboard_lock_en.ready";

/** Метка "агент уже установлен в этом процессе" (system property). */

/** Задержка перед установкой хуков — чтобы init() успел вернуть управление. */
const AGENT_START_DELAY_MS = 300;

/** Если init() так и не позвали, запускаемся сами через это время. */
const RPC_INIT_TIMEOUT_MS = 2000;

/** Имена иконок в секции "drawable" конфига. */
const ICON_EN = "english_input_method";
const ICON_EN_WHITE = "english_input_method_white";
const ICON_NAMES = [ICON_EN, ICON_EN_WHITE];

/** Код клавиши переключения раскладки (USERDEF_KEYCODE_LANG_2). */
const KEY_SWITCH_LANGUAGE = -2;

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
const log = new Logger("keyboard-lock-en-mod");

// ---------------------------------------------------------------------------
// Состояние агента
// ---------------------------------------------------------------------------

/** Параметры, переданные через rpc.exports.init() (frida-inject -P). */
let rpcParameters = null;

/** android.app.ActivityThread — через него достаём Context приложения. */
let ActivityThread = null;

/** Иконки из конфига: имя -> BitmapDrawable. */
let iconDrawables = null;

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
// Загрузка конфига
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

/** Относится ли режим ввода к английской раскладке. */
function isEnglishMode(mode, englishModes) {
    if (mode === null || mode === undefined) return false;
    if (englishModes === null || englishModes === undefined) return false;
    if (typeof mode !== "number") return false;
    return mode === englishModes.lower ||
        mode === englishModes.upper ||
        mode === englishModes.first ||
        mode === englishModes.hkb ||
        mode === englishModes.symbol1 ||
        mode === englishModes.symbol2;
}

function hasIcon(name) {
    return Object.prototype.hasOwnProperty.call(iconDrawables, name);
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
// Патчи
// ---------------------------------------------------------------------------

/** Читает конфиг с иконками и декодирует их. */
function loadIconConfig() {
    ActivityThread = Java.use("android.app.ActivityThread");
    const config = loadConfig(ICON_CONFIG_PATH, log);
    if (config) {
        iconDrawables = decodeIconDrawables(JSON.stringify(config));
    }
}

/** Не даёт сохранить не-английский режим ввода. */
function lockInputModeToEnglish() {
    const InputModeSwitcher = Java.use("com.qinggan.app.qgime.InputModeSwitcher");
    const englishModes = {
        lower: getField(InputModeSwitcher, "MODE_SKB_ENGLISH_LOWER"),
        upper: getField(InputModeSwitcher, "MODE_SKB_ENGLISH_UPPER"),
        first: getField(InputModeSwitcher, "MODE_SKB_ENGLISH_FIRST"),
        hkb: getField(InputModeSwitcher, "MODE_HKB_ENGLISH"),
        symbol1: getField(InputModeSwitcher, "MODE_SKB_SYMBOL1_EN"),
        symbol2: getField(InputModeSwitcher, "MODE_SKB_SYMBOL2_EN"),
    };

    InputModeSwitcher.saveInputMode.implementation = function (mode) {
        if (!isEnglishMode(mode, englishModes)) {
            mode = englishModes.first;
        }
        return this.saveInputMode.call(this, mode);
    };
}

/**
 * Подменяет иконку клавиши переключения раскладки во всех её toggle-состояниях
 * у раскладок skb_qwerty и skb_qwerty_no_voice.
 */
function patchLayoutToggleIcon() {
    const XmlKeyboardLoader = Java.use("com.qinggan.app.qgime.XmlKeyboardLoader");
    const ToggleState = Java.use("com.qinggan.app.qgime.SoftKeyToggle$ToggleState");
    const KeyRow = Java.use("com.qinggan.app.qgime.SoftKeyboard$KeyRow");
    const ThemeManager = Java.use("com.qinggan.theme.ThemeManager");
    const SoftKeyboard = Java.use("com.qinggan.app.qgime.SoftKeyboard");
    const RXml = Java.use("com.qinggan.app.qgime.R$xml");
    const SoftKeyToggle = Java.use("com.qinggan.app.qgime.SoftKeyToggle");
    const List = Java.use("java.util.List");

    const whiteThemeTitle = getField(ThemeManager, "DEFAULT_THEME_TITLE2");
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

/** Выключает голосовой ввод. */
function disableVoiceInput() {
    const QGInputConfig = Java.use("com.qinggan.app.qgime.QGInputConfig");
    setField(QGInputConfig, "DISABLE_VOICE", true);
    log.debug(`Voice disabled: ${getField(QGInputConfig, "DISABLE_VOICE")}`);
}

// ---------------------------------------------------------------------------
// Точка входа
// ---------------------------------------------------------------------------

function main() {
    log.info("Agent starting");

    loadIconConfig();
    lockInputModeToEnglish();

    try {
        patchLayoutToggleIcon();
    } catch (e) {
        log.error(`loadKeyboardHook failed: ${e.message}`);
    }

    disableVoiceInput();

    // сбросить кэш раскладок, иначе патчи применятся только к новым клавиатурам
    Java.use("com.qinggan.app.qgime.SkbPool").getInstance().resetCachedSkb();

    log.info("Agent started");
}

Java.perform(() => {
                const System = Java.use("java.lang.System");
                main();
});
