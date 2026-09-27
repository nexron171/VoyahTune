package ru.big.town.updater;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
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
    private Button refresh;
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
        setContentView(scroll);
        text(content, "Обновления VoyahTune", 28);
        status = text(content, "Подключение к службе…", 19);
        refresh = button(content, "Обновить состояние", v -> refresh());
        button(content, "Проверить обновления", null).setEnabled(false);
        button(content, "Скачать", null).setEnabled(false);
        button(content, "Установить", null).setEnabled(false);
        text(content, "Операции обновления недоступны в этой версии службы.", 16);
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

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Opening an existing window also does not dispatch actions from the Intent.
        refresh();
    }

    private void refresh() {
        request("status", null, result -> {
            JSONObject settings = result.optJSONObject("settings");
            if (settings != null && !loadedSettings) {
                catalogUrl.setText(settings.optString("catalogUrl"));
                loadedSettings = true;
            }
            if ("ready".equals(result.optString("state"))) {
                status.setText("Служба работает · " + result.optString("serviceVersion"));
            } else {
                showError(result.optString("error", "Ошибка настроек службы"));
            }
        });
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
                if (url != null) request.put("url", url);
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
        save.setEnabled(!value);
        showLogs.setEnabled(!value);
        exportLogs.setEnabled(!value);
        catalogUrl.setEnabled(!value);
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
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
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
