package ru.big.town.restoremode.diagnostics.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import ru.big.town.updater.ui.SystemStatusClient;

/** Runs only on the visible settings screen's existing diagnostics worker. */
public final class SystemDiagnosticsReader {
    public static final class Result {
        public final List<RuntimeStatusContract.Row> rows;
        public final String freshness;

        Result(List<RuntimeStatusContract.Row> rows, String freshness) {
            this.rows = rows;
            this.freshness = freshness;
        }
    }

    private final Context context;
    private final SystemStatusClient root = new SystemStatusClient();

    public SystemDiagnosticsReader(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result read() {
        List<RuntimeStatusContract.Row> rows = new ArrayList<>();
        readRoot(rows);
        readNative(rows);
        SharedPreferences preferences = context.getSharedPreferences(
                RuntimeStatusContract.PREFERENCES_NAME, Context.MODE_PRIVATE);
        int boot = Settings.Global.getInt(
                context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        boolean sameBoot =
                boot >= 0 && preferences.getInt(RuntimeStatusContract.BOOT_COUNT_KEY, -2) == boot;
        RuntimeStatusContract.Snapshot runtime = RuntimeStatusContract.describe(
                preferences.getString(RuntimeStatusContract.PAYLOAD_KEY, null),
                SystemClock.elapsedRealtime() / 1000, sameBoot);
        rows.addAll(runtime.rows);
        return new Result(rows, runtime.freshness);
    }

    private void readRoot(List<RuntimeStatusContract.Row> rows) {
        try {
            JSONObject response = root.read();
            JSONObject state = response.optJSONObject("state");
            boolean ready = response.optInt("pid") > 0
                    && "unified".equals(response.optString("infrastructure"));
            String error = state == null ? "" : state.optString("error");
            if ("null".equals(error))
                error = "";
            RuntimeStatusContract.Level level = !ready || !error.isEmpty()
                    ? RuntimeStatusContract.Level.RED
                    : RuntimeStatusContract.Level.GREEN;
            String detail = ready ? "Отвечает · PID " + response.optInt("pid")
                                  : "Несовместимый ответ службы";
            if (!error.isEmpty())
                detail += " · " + error;
            rows.add(new RuntimeStatusContract.Row(
                    "root", "Root-служба / OTA", detail, level, false));
        } catch (Exception exception) {
            rows.add(new RuntimeStatusContract.Row("root", "Root-служба / OTA",
                    "Не отвечает · " + failure(exception), RuntimeStatusContract.Level.RED, false));
        }
    }

    private void readNative(List<RuntimeStatusContract.Row> rows) {
        try {
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://ru.big.town.anative.ota"), "diagnostics", null, null);
            boolean service = result != null && result.getBoolean("serviceReady");
            boolean settings = result != null && result.getBoolean("settingsReady");
            String error = result == null ? "Нет ответа" : result.getString("error", "");
            rows.add(new RuntimeStatusContract.Row("native", "Привилегированный сервис Native",
                    service ? "Работает · PID " + result.getInt("pid")
                            : "Сервис не готов" + suffix(error),
                    service ? RuntimeStatusContract.Level.GREEN : RuntimeStatusContract.Level.RED,
                    false));
            rows.add(new RuntimeStatusContract.Row("settings", "Настройки RestoreMode / IPC",
                    settings ? "Обмен с Native подтверждён"
                             : "Нет подтверждения обмена" + suffix(error),
                    settings ? RuntimeStatusContract.Level.GREEN : RuntimeStatusContract.Level.RED,
                    false));
        } catch (Exception exception) {
            rows.add(new RuntimeStatusContract.Row("native", "Привилегированный сервис Native",
                    "Нет ответа · " + failure(exception), RuntimeStatusContract.Level.RED, false));
            rows.add(new RuntimeStatusContract.Row("settings", "Настройки RestoreMode / IPC",
                    "Обмен с Native не подтверждён", RuntimeStatusContract.Level.YELLOW, false));
        }
    }

    private static String suffix(String message) {
        return message.isEmpty() ? "" : " · " + message;
    }

    private static String failure(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isEmpty())
            message = exception.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) + "…" : message;
    }
}
