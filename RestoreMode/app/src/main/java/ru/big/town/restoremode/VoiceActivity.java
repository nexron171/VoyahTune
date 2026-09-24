package ru.big.town.restoremode;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ResultReceiver;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;
import java.util.UUID;

/** Dedicated translucent task. A new wheel invocation replaces the current recognition session. */
public class VoiceActivity extends AppCompatActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final VoiceRecognizer recognizer = new VoiceRecognizer();
    private VoiceOrbView orb;
    private TextView status, transcript;
    private Messenger nativeService;
    private boolean bound, ended, submitted;
    private String session;
    private List<VoiceCommandCatalog.Command> commands;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private AlertDialog confirmation;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            nativeService = new Messenger(binder);
            if (!ended && session != null) beginRecognition();
        }
        @Override public void onServiceDisconnected(ComponentName name) { nativeService = null; fail("Нет связи с сервисом автомобиля"); }
        @Override public void onBindingDied(ComponentName name) {
            nativeService = null;
            if (bound) { unbindService(this); bound = false; }
            fail("Соединение с сервисом прервано");
        }
        @Override public void onNullBinding(ComponentName name) { fail("Сервис автомобиля недоступен"); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0x80000000);
        LinearLayout center = new LinearLayout(this); center.setOrientation(LinearLayout.VERTICAL); center.setGravity(Gravity.CENTER);
        orb = new VoiceOrbView(this);
        int size = (int) (300 * getResources().getDisplayMetrics().density);
        center.addView(orb, new LinearLayout.LayoutParams(size, size));
        status = label(26); transcript = label(20); center.addView(status); center.addView(transcript);
        root.addView(center, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        Button close = new Button(this); close.setText("Закрыть"); close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        closeParams.setMargins(16, 16, 24, 16); root.addView(close, closeParams);
        setContentView(root);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        newSession();
        ensureBinding();
    }
    private void ensureBinding() {
        if (ended || bound) return;
        try {
            bound = bindService(new Intent().setClassName("ru.big.town.anative", "ru.big.town.anative.SetModesService"), connection, BIND_AUTO_CREATE);
            if (!bound) fail("Сервис автомобиля недоступен");
        } catch (RuntimeException e) { fail("Не удалось подключиться к сервису автомобиля"); }
    }
    private TextView label(int size) {
        TextView view = new TextView(this); view.setTextColor(0xffffffff); view.setTextSize(size);
        view.setGravity(Gravity.CENTER); view.setPadding(24, 8, 24, 8); return view;
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); newSession();
        if (nativeService != null && !ended) beginRecognition();
        else ensureBinding();
    }
    private void newSession() {
        cancelSession();
        session = UUID.randomUUID().toString(); ended = false; submitted = false;
        status.setText("Подготовка распознавания…"); transcript.setText(""); orb.state(false, false);
        if (!getSharedPreferences("DrivePreferences", MODE_PRIVATE).getBoolean(VoiceCommands.ENABLED, false)) {
            fail("Голосовое управление выключено"); return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Разрешите микрофон в разделе «Голосовое управление»"); return;
        }
        commands = VoiceCommands.load(this);
        ui.postDelayed(() -> fail("Не удалось подготовить распознавание"), 30000);
    }
    private void beginRecognition() {
        final String token = session;
        if (!send("begin", null, null)) { fail("Сервис автомобиля недоступен"); return; }
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change -> { if (change < 0 && token.equals(session)) fail("Микрофон занят другим приложением"); }, ui).build();
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail("Аудиоканал занят. Завершите звонок и повторите команду."); return;
        }
        recognizer.start(this, new VoiceRecognizer.Listener() {
            private boolean active() { return token.equals(session) && !ended && !isFinishing(); }
            @Override public void listening() {
                if (!active()) return;
                ui.removeCallbacksAndMessages(null);
                status.setText("Слушаю…"); orb.state(true, false);
                ui.postDelayed(() -> fail("Не удалось распознать команду"), 12000);
            }
            @Override public void audio(float level, String partial) {
                if (active()) { orb.level(level); transcript.setText(partial); }
            }
            @Override public void result(String text) { if (active()) recognized(text); }
            @Override public void error(String message) { if (active()) fail(message); }
        });
    }
    private void recognized(String text) {
        recognizer.cancel(); releaseFocus(); ui.removeCallbacksAndMessages(null);
        orb.state(false, false); transcript.setText(text);
        VoiceCommandCatalog.Command command = VoiceCommandCatalog.match(commands, text);
        if (command == null) { fail(text.isEmpty() ? "Не расслышал команду" : "Команда не распознана"); return; }
        if (command.confirm) {
            status.setText("Подтвердите действие");
            confirmation = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                    .setTitle(command.title).setMessage("Выполнить распознанную команду?")
                    .setPositiveButton("Выполнить", (d, w) -> execute(command))
                    .setNegativeButton("Отмена", (d, w) -> finish()).setOnCancelListener(d -> finish()).create();
            confirmation.show();
        } else execute(command);
    }
    private void execute(VoiceCommandCatalog.Command command) {
        final String token = session;
        status.setText("Передаю команду…");
        ResultReceiver reply = new ResultReceiver(ui) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (!token.equals(session) || ended || isFinishing()) return;
                if (code != 1) { fail(data == null ? "Не удалось выполнить команду" : data.getString("error", "Не удалось выполнить команду")); return; }
                if (command.action.startsWith("auto_light:")) {
                    getSharedPreferences("DrivePreferences", MODE_PRIVATE).edit()
                            .putBoolean("autoLight", command.action.endsWith(":on")).apply();
                }
                ended = true; ui.removeCallbacksAndMessages(null);
                status.setText("Команда передана"); transcript.setText(command.title);
                ui.postDelayed(() -> finish(), 1200);
            }
        };
        submitted = send("execute", command.action, reply);
        if (!submitted) { fail("Не удалось отправить команду"); return; }
        if (command.action.equals("system_back") || command.action.startsWith("app:")
                || command.action.startsWith("split:") || command.action.equals("open_voyahtune")) finish();
        else ui.postDelayed(() -> fail("Сервис не ответил на команду"), 8000);
    }
    private boolean send(String op, String action, ResultReceiver reply) {
        if (nativeService == null || session == null) return false;
        try {
            Bundle data = new Bundle(); data.putString("session", session); data.putString("op", op);
            data.putString("action", action); data.putParcelable("reply", reply);
            Message message = Message.obtain(null, VoiceCommands.MESSAGE); message.setData(data);
            nativeService.send(message); return true;
        } catch (Exception e) { return false; }
    }
    private void fail(String message) {
        if (ended || isFinishing()) return;
        ended = true; recognizer.cancel(); releaseFocus(); send("cancel", null, null);
        ui.removeCallbacksAndMessages(null); orb.state(false, true); status.setText(message);
        ui.postDelayed(() -> finish(), 3000);
    }
    private void releaseFocus() {
        if (focus != null && audio != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
    }
    private void cancelSession() {
        ended = true; recognizer.cancel(); releaseFocus(); ui.removeCallbacksAndMessages(null);
        if (confirmation != null) { confirmation.dismiss(); confirmation = null; }
        send("cancel", null, null);
    }
    @Override protected void onPause() {
        recognizer.cancel(); releaseFocus();
        if (!submitted) cancelSession();
        super.onPause();
        finish();
    }
    @Override protected void onStop() {
        super.onStop(); recognizer.cancel(); releaseFocus();
        if (!submitted) cancelSession();
        finish();
    }
    @Override protected void onDestroy() {
        recognizer.cancel(); releaseFocus(); ui.removeCallbacksAndMessages(null);
        if (!submitted) send("cancel", null, null);
        if (bound) unbindService(connection);
        super.onDestroy();
    }
}
