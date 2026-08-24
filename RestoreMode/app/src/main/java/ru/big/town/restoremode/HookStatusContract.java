package ru.big.town.restoremode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned wire contract shared by the root hook loader and the diagnostics UI.
 *
 * <p>The root side publishes one bounded ASCII record through {@code content call} only when the
 * hook state changes. RestoreMode persists that record atomically in SharedPreferences. The UI
 * reads it on the existing five-second "Other" diagnostics tick; no background poll is created.</p>
 */
final class HookStatusContract {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_PAYLOAD_LENGTH = 2_048;
    static final String AUTHORITY = "ru.big.town.restoremode.restoremodecontentprovider";
    static final String METHOD_PUBLISH = "publishHookStatusV1";
    static final String PREFERENCES_NAME = "HookStatus";
    static final String PAYLOAD_KEY = "payload_v1";

    private static final String[] HOOK_IDS = {
            "vd-bypass", "steering-wheel", "launcher-dock", "multi-display",
            "apollo-tech", "keyboard-en", "keyboard-ru"
    };
    private static final String[] HOOK_LABELS = {
            "Окна / VirtualDisplay", "Кнопки руля", "Док лаунчера", "Перенос между экранами",
            "Apollo ADAS", "Клавиатура EN", "Клавиатура RU"
    };

    private HookStatusContract() {}

    static boolean isValidPayload(String payload) {
        return parse(payload) != null;
    }

    static String renderForUi(String payload, boolean fullFlavor) {
        if (!fullFlavor) {
            return "Hook-loader отсутствует в Light-версии.";
        }
        Snapshot snapshot = parse(payload);
        if (snapshot == null) {
            return "Состояние ещё не опубликовано. Проверьте boot-сервис voyahtune_load.";
        }

        StringBuilder out = new StringBuilder(320);
        out.append("Loader: ")
                .append("running".equals(snapshot.loaderState) ? "работает" : "остановлен");
        if (snapshot.loaderPid > 0) out.append(" (PID ").append(snapshot.loaderPid).append(')');
        out.append('\n').append("Manifest: ");
        if (snapshot.manifestSha.length() == 64) {
            out.append(snapshot.manifestSha, 0, 12).append("…");
        } else {
            out.append("недоступен");
        }

        for (int i = 0; i < HOOK_IDS.length; i++) {
            Entry entry = snapshot.hooks.get(HOOK_IDS[i]);
            out.append('\n').append(HOOK_LABELS[i]).append(": ");
            if (entry == null) {
                out.append("нет данных");
                continue;
            }
            out.append(localizedState(entry.state));
            if (entry.pid > 0) out.append(" (PID ").append(entry.pid).append(')');
        }
        return out.toString();
    }

    private static String localizedState(String state) {
        switch (state) {
            case "active": return "активен";
            case "waiting": return "ожидает процесс";
            case "injecting": return "устанавливается";
            case "failed": return "ошибка (до перезапуска процесса)";
            case "disabled": return "выключен";
            case "invalid": return "ошибка целостности";
            default: return "неизвестно";
        }
    }

    private static Snapshot parse(String payload) {
        if (payload == null || payload.isEmpty() || payload.length() > MAX_PAYLOAD_LENGTH
                || payload.indexOf('\n') >= 0 || payload.indexOf('\r') >= 0) {
            return null;
        }
        String[] parts = payload.split(";", -1);
        if (parts.length != 4 + HOOK_IDS.length) return null;
        if (!"v=1".equals(parts[0])) return null;

        String loaderState = exactValue(parts[1], "loader");
        if (!"running".equals(loaderState) && !"stopped".equals(loaderState)) return null;
        int loaderPid = parsePid(exactValue(parts[2], "pid"));
        if (loaderPid < 0) return null;
        if (("running".equals(loaderState) && loaderPid == 0)
                || ("stopped".equals(loaderState) && loaderPid != 0)) return null;
        String manifestSha = exactValue(parts[3], "manifest");
        if (manifestSha == null
                || !(manifestSha.matches("[0-9a-f]{64}") || "unavailable".equals(manifestSha))) {
            return null;
        }

        LinkedHashMap<String, Entry> hooks = new LinkedHashMap<>();
        for (int i = 0; i < HOOK_IDS.length; i++) {
            String value = exactValue(parts[4 + i], HOOK_IDS[i]);
            int separator = value == null ? -1 : value.indexOf(':');
            if (separator <= 0 || separator == value.length() - 1) return null;
            String state = value.substring(0, separator);
            if (!("active".equals(state) || "waiting".equals(state)
                    || "injecting".equals(state) || "failed".equals(state)
                    || "disabled".equals(state) || "invalid".equals(state)
                    || "unknown".equals(state))) {
                return null;
            }
            int pid = parsePid(value.substring(separator + 1));
            if (pid < 0) return null;
            hooks.put(HOOK_IDS[i], new Entry(state, pid));
        }
        return new Snapshot(loaderState, loaderPid, manifestSha, hooks);
    }

    private static String exactValue(String field, String expectedKey) {
        if (field == null) return null;
        String prefix = expectedKey + "=";
        return field.startsWith(prefix) ? field.substring(prefix.length()) : null;
    }

    private static int parsePid(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,10}")) return -1;
        try {
            long value = Long.parseLong(raw);
            return value <= Integer.MAX_VALUE ? (int) value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static final class Entry {
        final String state;
        final int pid;
        Entry(String state, int pid) {
            this.state = state;
            this.pid = pid;
        }
    }

    private static final class Snapshot {
        final String loaderState;
        final int loaderPid;
        final String manifestSha;
        final Map<String, Entry> hooks;

        Snapshot(String loaderState, int loaderPid, String manifestSha,
                 Map<String, Entry> hooks) {
            this.loaderState = loaderState;
            this.loaderPid = loaderPid;
            this.manifestSha = manifestSha;
            this.hooks = hooks;
        }
    }
}
