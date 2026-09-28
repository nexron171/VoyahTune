package ru.big.town.updater;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The sole external entry always opens this menu and ignores all Intent data/extras. */
public final class MainActivity extends Activity {
    private static final int EXPORT_LOGS = 1;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RootClient client = new RootClient();
    private TextView status;
    private TextView logs;
    private EditText catalogUrl;
    private Button refresh, check, download, install, test;
    private TextView releaseInfo;
    private boolean operationBusy, hasRelease, verified, repair;
    private String noticeShowing;
    private final Handler poll = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { public void run() { if (!busy) refresh(); poll.postDelayed(this, 2000); } };
    private Button save;
    private Button showLogs;
    private Button exportLogs;
    private boolean busy;
    private boolean loadedSettings;
    private String logText = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Never read Intent extras, data, URI grants or caller-supplied operation names.
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(28);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        applyWindowInsets(scroll);
        setContentView(scroll);
        scroll.requestApplyInsets();
        text(content, "Обновления VoyahTune", 28);
        status = text(content, "Подключение к службе…", 19);
        refresh = button(content, "Обновить состояние", v -> refresh());
        releaseInfo = text(content, "", 18);
        check = button(content, "Проверить обновления", v -> action("check", false));
        download = button(content, "Скачать", v -> action("download", false));
        install = button(content, "Установить", v -> new AlertDialog.Builder(this)
            .setTitle("Установить обновление?")
            .setMessage("Автомобиль должен стоять в P с включённым питанием. Сохраняйте питание до завершения. Головное устройство перезагрузится. При сбое потребуется установка через USB с компьютера.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Установить и перезагрузить", (dialog, which) -> action("apply", false)).show());
        test = button(content, "Проверить релиз для повторной установки", v -> action("check", true));
        text(content, "Повторная установка позволяет проверить обновление на текущей версии.", 15);
        text(content, "Адрес каталога релизов", 22);
        catalogUrl = new EditText(this);
        catalogUrl.setSingleLine(true);
        catalogUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        catalogUrl.setHint("https://…/index.json");
        content.addView(catalogUrl);
        save = button(content, "Сохранить адрес", v -> saveUrl());
        text(content, "Смена адреса не запускает скачивание или установку.", 16);
        showLogs = button(content, "Посмотреть логи", v -> loadLogs(false));
        exportLogs = button(content, "Выгрузить логи", v -> loadLogs(true));
        button(content, "Скопировать логи", v -> {
            if (logText.isEmpty()) return;
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("VoyahTune updater", logText));
            Toast.makeText(this, "Логи скопированы", Toast.LENGTH_SHORT).show();
        });
        logs = text(content, "", 15);
        logs.setTextIsSelectable(true);
        button(content, "Закрыть", v -> finish());
        refresh();
    }

    private void applyWindowInsets(View root) {
        // Qinggan's dock overlays the window without reporting an inset.
        // Match the spacing used by the RestoreMode settings screen.
        final int nativeDock = dp(145);
        int statusBarId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        final int statusBarHeight = statusBarId > 0
                ? getResources().getDimensionPixelSize(statusBarId) : 0;
        root.setPadding(nativeDock, statusBarHeight, 0, 0);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            int top = bars.top > 0 ? bars.top : statusBarHeight;
            view.setPadding(nativeDock + bars.left, top, bars.right, bars.bottom);
            return insets;
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Opening an existing window also does not dispatch actions from the Intent.
        refresh();
    }

    @Override protected void onResume() { super.onResume(); poll.post(tick); }
    @Override protected void onPause() { poll.removeCallbacks(tick); super.onPause(); }
    private void action(String command, boolean sameVersion) {
        request(command, sameVersion ? "same" : null, result -> refresh());
    }
    private void refresh() {
        request("status", null, result -> {
            JSONObject settings = result.optJSONObject("settings");
            if (settings != null && !loadedSettings) {
                catalogUrl.setText(settings.optString("catalogUrl")); loadedSettings = true;
            }
            JSONObject state = result.getJSONObject("state");
            String phase = state.getString("phase");
            operationBusy = java.util.Arrays.asList("checking", "downloading", "verifying", "applying", "reboot-pending", "validating").contains(phase);
            repair = "repair-required".equals(phase);
            verified = "verified".equals(phase);
            JSONObject selected = state.optJSONObject("selected"); hasRelease = selected != null;
            String details = "Установлено: " + state.optString("installedVersion");
            if (selected != null) {
                JSONObject archive = selected.getJSONObject("payload");
                details += "\nДоступно: " + selected.getString("version") + " · " + (archive.getLong("size") / (1024 * 1024)) + " МБ";
            }
            releaseInfo.setText(details);
            String stage = state.optString("step");
            long total = state.optLong("total"), bytes = state.optLong("bytes");
            if (total > 0) stage += "\n" + (100 * bytes / total) + "% · " + bytes + " / " + total;
            status.setText(stage);
            if (!state.isNull("error")) showError(state.optString("error"));
            if (!result.isNull("settingsError")) showError(result.optString("settingsError"));
            setBusy(false);
            if (!state.isNull("notice")) showNotice(state.getString("notice"), state.optString("error"));
            else noticeShowing = null;
        });
    }
    private void showNotice(String notice, String error) {
        if (notice.equals(noticeShowing)) return;
        noticeShowing = notice;
        String title = "error".equals(notice) ? "Ошибка обновления VoyahTune"
            : "success".equals(notice) ? "VoyahTune обновлён" : "Доступна новая версия VoyahTune";
        String message = "error".equals(notice) ? error + "\nПосмотрите или выгрузите логи. Установите релиз через USB с компьютера."
            : "success".equals(notice) ? "Проверка запуска служб завершена успешно." : "Версия " + notice + ". Скачать её можно в меню обновления.";
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Открыть меню", (d,w) -> {})
            .setNegativeButton("Скрыть", (d,w) -> finish()).create();
        dialog.setOnDismissListener(d -> {
            // Dismissing only acknowledges the notice. It never downloads or installs.
            if (!worker.isShutdown()) worker.execute(() -> { try { client.call(new JSONObject().put("command", "dismiss")); } catch (Exception ignored) {} });
        });
        dialog.setOnCancelListener(d -> finish());
        dialog.show();
    }

    private void saveUrl() {
        String value = catalogUrl.getText().toString().trim();
        request("set_catalog_url", value, result -> {
            catalogUrl.setText(result.getJSONObject("settings").getString("catalogUrl"));
            loadedSettings = true;
            status.setText("Адрес каталога сохранён");
        });
    }

    private void loadLogs(boolean export) {
        request("logs", null, result -> {
            logText = result.optString("logs");
            logs.setText(logText);
            if (export) {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "voyahtune-updater.log");
                try { startActivityForResult(intent, EXPORT_LOGS); }
                catch (android.content.ActivityNotFoundException unavailable) {
                    status.setText("Выбор файла недоступен. Можно скопировать логи или получить их через USB.");
                }
            }
        });
    }

    private interface Response { void apply(JSONObject response) throws Exception; }
    private void request(String command, String url, Response response) {
        if (busy) return;
        setBusy(true);
        worker.execute(() -> {
            try {
                JSONObject request = new JSONObject().put("command", command);
                if (url != null && "set_catalog_url".equals(command)) request.put("url", url);
                if ("check".equals(command)) request.put("same_version", "same".equals(url));
                JSONObject result = client.call(request);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setBusy(false);
                    try { response.apply(result); } catch (Exception e) { showError(e.getMessage()); }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setBusy(false);
                    showError(error.getMessage());
                });
            }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        refresh.setEnabled(!value);
        save.setEnabled(!value && !operationBusy);
        check.setEnabled(!value && !operationBusy && !repair);
        test.setEnabled(!value && !operationBusy && !repair);
        download.setEnabled(!value && !operationBusy && !repair && hasRelease);
        install.setEnabled(!value && !operationBusy && !repair && verified);
        showLogs.setEnabled(!value);
        exportLogs.setEnabled(!value);
        catalogUrl.setEnabled(!value && !operationBusy);
    }
    private void showError(String reason) {
        status.setText("Ошибка: " + (reason == null ? "служба недоступна" : reason)
            + "\nПосмотрите или выгрузите логи. Установите релиз через USB с компьютера.");
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_LOGS || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri destination = data.getData();
        byte[] snapshot = logText.getBytes(StandardCharsets.UTF_8);
        worker.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                if (out == null) throw new java.io.IOException("Не удалось открыть файл");
                out.write(snapshot);
                runOnUiThread(() -> { if (!isDestroyed()) status.setText("Логи сохранены"); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (!isDestroyed()) showError(e.getMessage()); });
            }
        });
    }
    @Override protected void onDestroy() { poll.removeCallbacks(tick); worker.shutdown(); super.onDestroy(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout content, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setPadding(0, dp(8), 0, dp(8)); content.addView(view); return view;
    }
    private Button button(LinearLayout content, String label, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(listener);
        content.addView(button, new LinearLayout.LayoutParams(-1, dp(58))); return button;
    }
}
