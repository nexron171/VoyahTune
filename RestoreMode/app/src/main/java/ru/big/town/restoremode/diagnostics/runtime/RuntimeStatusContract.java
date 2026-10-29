package ru.big.town.restoremode.diagnostics.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Converts authenticated runtime observations into diagnostic states. */
public final class RuntimeStatusContract {
    public static final String METHOD_PUBLISH = "publishRuntimeStatusV2";
    public static final String PREFERENCES_NAME = "RuntimeDiagnostics";
    public static final String PAYLOAD_KEY = "payload_v2";
    public static final String BOOT_COUNT_KEY = "boot_count";
    public static final long MAX_AGE_SECONDS = 20;

    public enum Level { GREEN, YELLOW, RED }

    public static final class Row {
        public final String id;
        public final String title;
        public final String detail;
        public final Level level;
        public final boolean agent;

        public Row(String id, String title, String detail, Level level, boolean agent) {
            this.id = id;
            this.title = title;
            this.detail = detail;
            this.level = level;
            this.agent = agent;
        }
    }

    public static final class Snapshot {
        public final List<Row> rows;
        public final String freshness;

        private Snapshot(List<Row> rows, String freshness) {
            this.rows = Collections.unmodifiableList(rows);
            this.freshness = freshness;
        }
    }

    private static final Map<String, String> AGENT_LABELS = labels();

    private RuntimeStatusContract() {}

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("windows", "Окна и VirtualDisplay");
        labels.put("acc", "Восстановление ACC");
        labels.put("drive-reset", "Режим движения");
        labels.put("vehicle-air", "Климат и воздух");
        labels.put("apollo", "Apollo ADAS");
        labels.put("steering", "Кнопки руля");
        labels.put("launcher", "Лаунчер и док");
        labels.put("multidisplay", "Перенос между экранами");
        labels.put("keyboard-en", "Клавиатура EN");
        labels.put("keyboard-ru", "Клавиатура RU");
        labels.put("phone", "Телефонные номера");
        labels.put("cluster", "Приборная панель");
        labels.put("rds", "Радио и RDS");
        labels.put("statusbar", "Системные панели");
        labels.put("yandex-navigation", "Яндекс Навигатор");
        labels.put("dgis-navigation", "Навигация 2ГИС");
        return Collections.unmodifiableMap(labels);
    }

    public static boolean isValidPayload(String payload) {
        return parse(payload) != null;
    }

    private static JSONObject parse(String payload) {
        if (payload == null || payload.isEmpty() || payload.length() > 65_536)
            return null;
        try {
            JSONObject value = new JSONObject(payload);
            JSONObject loader = value.getJSONObject("loader");
            JSONObject agents = value.getJSONObject("agents");
            if (value.getInt("schema") != 2 || value.getString("boot").isEmpty()
                    || value.getLong("updatedUptime") < 0 || loader.getInt("pid") <= 0
                    || loader.getString("startTicks").isEmpty() || agents.length() > 256)
                return null;
            if (!isLoaderState(value.getString("state")))
                return null;
            for (java.util.Iterator<String> keys = agents.keys(); keys.hasNext();) {
                String id = keys.next();
                JSONObject agent = agents.getJSONObject(id);
                if (!id.matches("[a-zA-Z0-9._-]{1,180}")
                        || agent.getJSONObject("identity").getInt("pid") < 0
                        || agent.getLong("updatedUptime") < 0
                        || agent.getString("state").length() > 40
                        || agent.getString("target").length() > 180)
                    return null;
            }
            return value;
        } catch (JSONException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isLoaderState(String state) {
        return "running".equals(state) || "stopped".equals(state) || "failed".equals(state)
                || "quiescing".equals(state) || "quiesced".equals(state);
    }

    public static Snapshot describe(String payload, long nowSeconds, boolean sameBoot) {
        JSONObject value = parse(payload);
        String unavailable = unavailableReason(value, nowSeconds, sameBoot);
        List<Row> rows = new ArrayList<>();
        if (unavailable != null) {
            Level level =
                    value == null && payload == null && nowSeconds < 60 ? Level.YELLOW : Level.RED;
            rows.add(new Row("loader", "Загрузчик", unavailable, level, false));
            rows.add(new Row("components", "Комплект инжекторов ARM64 / ARM32",
                    "Нет актуального подтверждения", Level.YELLOW, false));
            rows.add(new Row("resident", "Native-резидент / JNI", "Нет актуального подтверждения",
                    Level.YELLOW, false));
            rows.add(new Row(
                    "java", "Java runtime", "Нет актуального подтверждения", Level.YELLOW, false));
            for (Map.Entry<String, String> label : AGENT_LABELS.entrySet()) {
                rows.add(new Row(label.getKey(), label.getValue(), "Нет актуального статуса",
                        Level.YELLOW, true));
            }
            return new Snapshot(rows, unavailable);
        }
        String loaderState = value.optString("state");
        boolean running = "running".equals(loaderState);
        Level loaderLevel = running                                               ? Level.GREEN
                : "failed".equals(loaderState) || "stopped".equals(loaderState) ? Level.RED
                                                                                  : Level.YELLOW;
        rows.add(new Row("loader", "Загрузчик",
                loaderDescription(loaderState) + " · PID "
                        + value.optJSONObject("loader").optInt("pid"),
                loaderLevel, false));
        String configurationError = value.optString("configurationError");
        boolean componentsReady = configurationError.isEmpty()
                && !value.optString("nativeId").isEmpty()
                && !value.optString("runtimeId").isEmpty();
        rows.add(new Row("components", "Комплект инжекторов ARM64 / ARM32",
                componentsReady ? "Файлы проверены при запуске"
                                : "Ошибка комплекта: " + configurationError,
                componentsReady ? Level.GREEN : Level.RED, false));
        JSONObject agents = value.optJSONObject("agents");
        List<Row> agentRows = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>(AGENT_LABELS.keySet());
        List<String> extraIds = new ArrayList<>();
        Map<String, JSONObject> observations = new LinkedHashMap<>();
        for (java.util.Iterator<String> keys = agents.keys(); keys.hasNext();) {
            String id = keys.next();
            JSONObject agent = agents.optJSONObject(id);
            observations.put(id, agent);
            if (!ids.contains(id))
                extraIds.add(id);
        }
        // A shared target ACK also confirms client modules installed by a static agent.
        for (JSONObject agent : new ArrayList<>(observations.values())) {
            JSONArray modules = agent.optJSONArray("modules");
            for (int index = 0; modules != null && index < modules.length(); index++) {
                String id = modules.optString(index);
                if (id.startsWith("client.") && !observations.containsKey(id)) {
                    observations.put(id, agent);
                    extraIds.add(id);
                }
            }
        }
        Collections.sort(extraIds);
        ids.addAll(extraIds);
        Set<Integer> confirmedProcesses = new LinkedHashSet<>();
        int failures = 0;
        for (String id : ids) {
            JSONObject agent = observations.get(id);
            Row row = agentRow(id, agent, value, nowSeconds, running);
            agentRows.add(row);
            if (row.level == Level.GREEN)
                confirmedProcesses.add(agent.optJSONObject("identity").optInt("pid"));
            if (row.level == Level.RED)
                failures++;
        }
        Level runtimeLevel = confirmedProcesses.isEmpty() ? failures > 0 ? Level.RED : Level.YELLOW
                : failures > 0                            ? Level.YELLOW
                                                          : Level.GREEN;
        String runtimeDetail = confirmedProcesses.isEmpty()
                ? "Нет подтверждённых активных процессов"
                : "Подтверждён в процессах: " + confirmedProcesses.size();
        if (failures > 0)
            runtimeDetail += " · ошибки агентов: " + failures;
        rows.add(new Row("resident", "Native-резидент / JNI", runtimeDetail, runtimeLevel, false));
        rows.add(new Row("java", "Java runtime", runtimeDetail, runtimeLevel, false));
        rows.addAll(agentRows);
        return new Snapshot(rows,
                "Статус загрузчика: " + Math.max(0, nowSeconds - value.optLong("updatedUptime"))
                        + " с назад · обновление каждые 5 с");
    }

    private static String unavailableReason(JSONObject value, long nowSeconds, boolean sameBoot) {
        if (value == null)
            return "Нет корректного статуса загрузчика";
        if (!sameBoot)
            return "Нет данных после перезагрузки Android";
        long age = nowSeconds - value.optLong("updatedUptime");
        if (age < -2)
            return "Некорректное время статуса";
        if (age > MAX_AGE_SECONDS)
            return "Нет связи с загрузчиком · статус устарел (" + age + " с)";
        return null;
    }

    private static Row agentRow(
            String id, JSONObject agent, JSONObject snapshot, long nowSeconds, boolean running) {
        String title = AGENT_LABELS.containsKey(id)
                ? AGENT_LABELS.get(id)
                : "Клиент · " + id.replaceFirst("^client\\.", "");
        if (agent == null)
            return new Row(id, title, "Нет данных от загрузчика", Level.YELLOW, true);
        int pid = agent.optJSONObject("identity").optInt("pid");
        String state = agent.optString("state");
        Level level = Level.YELLOW;
        String detail = stateDescription(state);
        if ("active".equals(state)) {
            long age = nowSeconds - agent.optLong("updatedUptime");
            boolean acknowledged = pid > 0
                    && !agent.optJSONObject("identity").optString("startTicks").isEmpty()
                    && !snapshot.optString("nativeId").isEmpty()
                    && !snapshot.optString("runtimeId").isEmpty()
                    && snapshot.optString("nativeId").equals(agent.optString("nativeId"))
                    && snapshot.optString("runtimeId").equals(agent.optString("runtimeId"))
                    && includes(agent.optJSONArray("modules"), id);
            level = acknowledged && age >= -2 && age <= MAX_AGE_SECONDS ? Level.GREEN : Level.RED;
            if (!acknowledged)
                detail = "Нет полного подтверждения runtime / модуля";
            else if (level == Level.RED)
                detail = "Подтверждение агента устарело";
        } else if ("failed".equals(state) || "unsupported".equals(state)
                || "restart-required".equals(state)) {
            level = Level.RED;
        }
        if (!running) {
            level = "failed".equals(snapshot.optString("state"))
                            || "stopped".equals(snapshot.optString("state"))
                    ? Level.RED
                    : Level.YELLOW;
            detail = "Загрузчик: "
                    + loaderDescription(snapshot.optString("state"))
                              .toLowerCase(java.util.Locale.ROOT);
        }
        String reason = agent.optString("reason");
        if (level == Level.RED && !reason.isEmpty())
            detail += " · " + reason;
        if (pid > 0)
            detail += " · PID " + pid;
        String architecture = agent.optString("architecture");
        if (!architecture.isEmpty())
            detail += " · "
                    + ("armeabi-v7a".equals(architecture)              ? "ARM32"
                                    : "arm64-v8a".equals(architecture) ? "ARM64"
                                                                         : architecture);
        return new Row(id, title, detail, level, true);
    }

    private static boolean includes(JSONArray values, String expected) {
        for (int index = 0; values != null && index < values.length(); index++) {
            if (expected.equals(values.optString(index)))
                return true;
        }
        return false;
    }

    private static String loaderDescription(String state) {
        switch (state) {
            case "running":
                return "Работает";
            case "quiescing":
                return "Завершает работу агентов";
            case "quiesced":
                return "Приостановлен для обслуживания";
            case "failed":
                return "Ошибка";
            default:
                return "Остановлен";
        }
    }

    private static String stateDescription(String state) {
        switch (state) {
            case "active":
                return "Работает";
            case "waiting":
                return "Ожидает запуска процесса";
            case "disabled":
                return "Отключён настройками";
            case "injecting":
                return "Подключение и подготовка";
            case "stopping":
                return "Завершение работы";
            case "failed":
                return "Ошибка агента";
            case "unsupported":
                return "Процесс не поддерживается";
            case "restart-required":
                return "Требуется перезапуск процесса";
            default:
                return "Состояние неизвестно";
        }
    }
}
