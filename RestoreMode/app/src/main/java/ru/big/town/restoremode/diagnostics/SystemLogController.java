package ru.big.town.restoremode.diagnostics;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import ru.big.town.restoremode.BuildConfig;
import ru.big.town.restoremode.R;
import ru.big.town.updater.ui.ApplicationLogClient;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Keeps disk and network operations in the root service across Activity recreation. */
final class SystemLogController {
    private static final long POLL_INTERVAL_MS = 1000;

    private final ApplicationLogClient client = new ApplicationLogClient();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Consumer<String> displayLog;
    private final Activity activity;
    private final SharedPreferences resultPreferences;
    private final Button startButton;
    private final Button stopButton;
    private final Button uploadButton;
    private final Button deleteButton;
    private final TextView statusView;
    private final TextView pathView;
    private boolean visible;
    private boolean closed;
    private boolean requestPending;
    private boolean commandPending;
    private AlertDialog resultDialog;
    private JSONObject latestState;

    private final Runnable poll = () -> request("status");

    SystemLogController(Activity activity, Consumer<String> displayLog) {
        this.activity = activity;
        this.displayLog = displayLog;
        resultPreferences = activity.getSharedPreferences("SystemLogResults", Context.MODE_PRIVATE);
        startButton = activity.findViewById(R.id.buttonStartLog);
        stopButton = activity.findViewById(R.id.buttonStopLog);
        uploadButton = activity.findViewById(R.id.buttonUploadLog);
        deleteButton = activity.findViewById(R.id.buttonDeleteLog);
        statusView = activity.findViewById(R.id.textLogStatus);
        pathView = activity.findViewById(R.id.textLogPath);
        startButton.setOnClickListener(view -> request("start"));
        stopButton.setOnClickListener(view -> request("stop"));
        uploadButton.setOnClickListener(view -> request("upload"));
        deleteButton.setOnClickListener(view -> request("delete"));
        setButtonsEnabled(false);
    }

    void resume() {
        visible = true;
        handler.removeCallbacks(poll);
        request("status");
    }

    void pause() {
        visible = false;
        handler.removeCallbacks(poll);
        if (resultDialog != null) {
            resultDialog.dismiss();
            resultDialog = null;
        }
    }

    void close() {
        closed = true;
        pause();
        worker.shutdown();
    }

    private void request(String action) {
        boolean command = !"status".equals(action);
        if (closed || (!command && requestPending) || commandPending) {
            return;
        }
        requestPending = true;
        commandPending = command;
        handler.removeCallbacks(poll);
        if (command) {
            setButtonsEnabled(false);
            statusView.setText("Выполняем действие…");
        }
        worker.execute(
                () -> {
                    JSONObject result = null;
                    String error = null;
                    try {
                        result = client.call(action, BuildConfig.VERSION_NAME);
                    } catch (Exception failure) {
                        error = failure.getMessage();
                        if (error == null || error.isEmpty()) {
                            error = "Не удалось связаться со службой записи логов";
                        }
                    }
                    JSONObject response = result;
                    String message = error;
                    handler.post(
                            () -> {
                                if (closed) {
                                    return;
                                }
                                if (!command && commandPending) {
                                    return;
                                }
                                requestPending = false;
                                if (command) {
                                    commandPending = false;
                                }
                                if (response != null) {
                                    render(response);
                                } else {
                                    statusView.setText(message);
                                    setButtonsEnabled(false);
                                    if (command && "upload".equals(action) && visible) {
                                        showUploadFailure(message, "");
                                    }
                                }
                                if (visible) {
                                    handler.removeCallbacks(poll);
                                    handler.postDelayed(poll, POLL_INTERVAL_MS);
                                }
                            });
                });
    }

    private void render(JSONObject state) {
        latestState = state;
        boolean enabled = state.optBoolean("enabled");
        boolean uploading = state.optBoolean("uploading");
        long size = state.optLong("size");
        setButtonEnabled(startButton, !enabled && !uploading);
        setButtonEnabled(stopButton, enabled && !uploading);
        setButtonEnabled(uploadButton, size > 0 && !uploading);
        setButtonEnabled(deleteButton, (size > 0 || enabled) && !uploading);
        statusView.setText(state.optString("message", "Запись остановлена"));
        pathView.setText(
                String.format(
                        Locale.getDefault(),
                        "%s · %.2f / 15 МБ · последние 100 записей",
                        state.optString("path"),
                        size / 1_000_000.0));
        displayLog.accept(state.optString("log"));
        showUploadResult();
    }

    private void setButtonsEnabled(boolean enabled) {
        setButtonEnabled(startButton, enabled);
        setButtonEnabled(stopButton, enabled);
        setButtonEnabled(uploadButton, enabled);
        setButtonEnabled(deleteButton, enabled);
    }

    private void setButtonEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.4f);
    }

    private void showUploadResult() {
        if (!visible || closed || resultDialog != null || latestState == null) {
            return;
        }
        String status = latestState.optString("uploadStatus");
        if (!"success".equals(status) && !"error".equals(status)) {
            return;
        }
        String sessionId = latestState.optString("sessionId");
        String resultId = sessionId + ":" + latestState.optLong("uploadAttempt");
        if (resultId.equals(resultPreferences.getString("acknowledgedResult", ""))) {
            return;
        }
        if ("error".equals(status)) {
            showUploadFailure(latestState.optString("uploadError", "Ошибка выгрузки"), resultId);
            return;
        }

        View content =
                activity.getLayoutInflater().inflate(R.layout.dialog_log_upload_success, null);
        TextView identifier = content.findViewById(R.id.textUploadedSessionId);
        identifier.setText(sessionId);
        resultDialog =
                new MaterialAlertDialogBuilder(activity, R.style.DarkDialog)
                        .setTitle("Логи успешно выгружены")
                        .setView(content)
                        .setPositiveButton("OK", (dialog, which) -> acknowledge(resultId))
                        .setOnCancelListener(dialog -> acknowledge(resultId))
                        .create();
        showDialog();
        Window window = resultDialog.getWindow();
        if (window != null) {
            float density = activity.getResources().getDisplayMetrics().density;
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int dialogWidth =
                    Math.min(Math.round(820 * density), screenWidth - Math.round(48 * density));
            window.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showUploadFailure(String message, String resultId) {
        if (!visible || closed || resultDialog != null) {
            return;
        }
        resultDialog =
                new MaterialAlertDialogBuilder(activity, R.style.DarkDialog)
                        .setTitle("Не удалось выгрузить логи")
                        .setMessage(message + "\n\nФайл сохранён на устройстве.")
                        .setPositiveButton(
                                "Повторить",
                                (dialog, which) -> {
                                    acknowledge(resultId);
                                    request("upload");
                                })
                        .setNegativeButton("Отмена", (dialog, which) -> acknowledge(resultId))
                        .setOnCancelListener(dialog -> acknowledge(resultId))
                        .create();
        showDialog();
    }

    private void showDialog() {
        resultDialog.setOnDismissListener(dialog -> resultDialog = null);
        resultDialog.setCanceledOnTouchOutside(false);
        resultDialog.show();
    }

    private void acknowledge(String resultId) {
        if (!resultId.isEmpty()) {
            resultPreferences.edit().putString("acknowledgedResult", resultId).apply();
        }
    }
}
