package ru.big.town.restoremode;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Messenger;
import android.os.ResultReceiver;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.io.File;

/** Dedicated translucent task. A new wheel invocation replaces the current recognition session. */
public class VoiceActivity extends AppCompatActivity {
    static final String TEST_ONLY = "voiceTestOnly";
    private static final int COLOR_REJECTED = 0xffff6b6b;
    private static final int COLOR_REJECTED_NOTE = 0xffffb0b0;
    private static final int COLOR_NOTE = 0xffbbbbbb;
    private static final long COMMAND_TIMEOUT_MS = 8000;
    private static final long SEQUENCE_BUDGET_MS = 20000;
    private static final long SEQUENCE_ROW_MS = 1200;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final VoiceRecognizer recognizer = new VoiceRecognizer();
    private VoiceOrbView orb;
    private TextView status, transcript, details;
    private LinearLayout resultList;
    private final List<TextView> rowNotes = new ArrayList<>();
    private List<VoiceCommandSequence.Segment> sequence;
    private Runnable commandTimeout;
    private int sequenceNext, sequenceAccepted;
    private long sequenceDuration;
    private boolean testOnly;
    private Messenger nativeService;
    private boolean bound, ended, submitted, interrupted;
    private String session;
    private List<VoiceCommandCatalog.Command> commands;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private AlertDialog confirmation;
    private VoiceSounds sounds;
    private File recording;
    private MediaPlayer playback;
    private final VoiceCloseControl closeControl = new VoiceCloseControl();

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
        sounds = new VoiceSounds(this);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xCC000000);
        LinearLayout center = new LinearLayout(this); center.setOrientation(LinearLayout.VERTICAL); center.setGravity(Gravity.CENTER);
        orb = new VoiceOrbView(this);
        int size = (int) (360 * getResources().getDisplayMetrics().density);
        center.addView(orb, new LinearLayout.LayoutParams(size, size));
        status = label(26); transcript = label(40); center.addView(status); center.addView(transcript);
        resultList = new LinearLayout(this);
        resultList.setOrientation(LinearLayout.VERTICAL); resultList.setGravity(Gravity.CENTER);
        center.addView(resultList);
        details = label(16); details.setTag("voiceDiagnostics");
        details.setTextColor(0xffbbbbbb); center.addView(details);
        closeControl.attach(this, center, () -> { cancelSession(); finish(); }, this::playRecording);
        root.addView(center, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        setContentView(root);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        newSession();
        ensureBinding();
    }
    private void ensureBinding() {
        if (isAnimationPreview() || ended || bound) return;
        if (testOnly) { beginRecognition(); return; }
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
        newSession(true);
    }
    private void newSession(boolean activationCue) {
        cancelSession();
        clearRecording();
        testOnly = getIntent().getBooleanExtra(TEST_ONLY, false);
        closeControl.testMode(testOnly);
        session = UUID.randomUUID().toString(); ended = false; submitted = false; interrupted = false;
        sequence = null; sequenceNext = 0; sequenceAccepted = 0; sequenceDuration = 0; commandTimeout = null;
        clearRows();
        status.setText("Подготовка помощника…"); transcript.setText(""); orb.state(false, false);
        details.setText("");
        details.setVisibility(testOnly ? View.VISIBLE : View.GONE);
        if (isAnimationPreview()) {
            status.setText("Слушаю…");
            transcript.setText("Тест анимации · имитация голоса");
            orb.state(true, false);
            if (activationCue) sounds.activation();
            return;
        }
        if (!testOnly && !getSharedPreferences("DrivePreferences", MODE_PRIVATE).getBoolean(VoiceCommands.ENABLED, false)) {
            fail("Голосовое управление выключено"); return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Разрешите микрофон в разделе «Голосовое управление»"); return;
        }
        // The shared catalog is usually warm (warmup service / earlier session); a cold build
        // runs on a background thread and is only read back in recognized().
        commands = null;
        VoiceCommands.preload(this);
        ui.postDelayed(() -> fail("Подготовка помощника занимает слишком много времени"), 90000);
    }
    /** Only the debug-source-set preview activity overrides this; release has no demo entry. */
    protected boolean isAnimationPreview() { return false; }
    protected final void previewLevel(float level) { orb.level(level); }
    /** Debug preview invokes the actual result UI and cancellation timers, without sending commands. */
    protected final void previewState(String state) {
        if (!isAnimationPreview()) return;
        if ("listening".equals(state)) { newSession(); return; }
        // Replace timers and sounds without resetting the current colour transition.
        cancelSession();
        session = UUID.randomUUID().toString(); ended = false; submitted = false; interrupted = false;
        if ("recognized".equals(state)) {
            orb.recognized(); status.setText("Команда распознана"); transcript.setText("Режим движения: Спорт");
        } else if ("success".equals(state)) {
            showSuccess("Тестовая команда · закрытие через 3 секунды");
        } else if ("fuel".equals(state)) {
            showSuccess(VoiceResultPresentation.successText("port_cap:fuel", "", 26),
                    VoiceResultPresentation.successDurationMs("port_cap:fuel"));
        } else if ("error".equals(state)) {
            fail("Тестовая ошибка · закрытие через 6 секунд");
        } else if ("sequence".equals(state)) {
            ended = true;
            showRows(VoiceCommandSequence.sample());
            orb.recognized(); status.setText("Команды переданы");
            sounds.success();
            ui.postDelayed(() -> finish(), 6000);
        }
    }
    private void beginRecognition() {
        final String token = session;
        if (!testOnly && !send("begin", null, null)) { fail("Сервис автомобиля недоступен"); return; }
        recognizer.start(this, new VoiceRecognizer.Listener() {
            private boolean active() { return token.equals(session) && !ended && !isFinishing(); }
            @Override public void ready(Runnable beginCapture) {
                if (!active() || interrupted || !getLifecycle().getCurrentState().isAtLeast(
                        androidx.lifecycle.Lifecycle.State.STARTED)) {
                    recognizer.cancel();
                    return;
                }
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(change -> { if (change < 0 && token.equals(session)) fail("Микрофон занят другим приложением"); }, ui).build();
                if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    fail("Аудиоканал занят. Завершите звонок и повторите команду."); return;
                }
                beginCapture.run();
            }
            @Override public void listening() {
                if (!active()) return;
                ui.removeCallbacksAndMessages(null);
                status.setText("Слушаю…"); orb.state(true, false);
                sounds.activation();
                ui.postDelayed(() -> fail("Не удалось распознать команду"), 12000);
            }
            @Override public void audio(float level, String partial) {
                if (active()) { orb.level(level); transcript.setText(partial); }
            }
            @Override public void processing() {
                if (!active()) return;
                releaseFocus(); // Recording has ended; decoding needs no audio channel.
                status.setText("Распознаю…"); orb.state(false, false);
                ui.removeCallbacksAndMessages(null);
                ui.postDelayed(() -> fail("Распознавание занимает слишком много времени"), 15000);
            }
            @Override public void details(String text) { if (active() && testOnly) details.setText(text); }
            @Override public void recording(File file) {
                if (!active()) { file.delete(); return; }
                if (recording != null) recording.delete();
                recording = file;
            }
            @Override public void result(String text) { if (active()) recognized(text); }
            @Override public void error(String message) { if (active()) fail(message); }
        });
    }
    private void recognized(String text) {
        recognizer.cancel(); releaseFocus(); ui.removeCallbacksAndMessages(null);
        orb.state(false, false); transcript.setText(text);
        // The background preload has usually finished by now; a still-cold cache builds here.
        if (commands == null) commands = VoiceCommands.load(this);
        List<VoiceCommandSequence.Segment> segments = VoiceCommandSequence.parse(commands, text);
        if (segments.size() > 1) { recognizedSequence(text, segments); return; }
        VoiceCommandCatalog.Command command = segments.isEmpty() ? null : segments.get(0).command;
        if (testOnly) {
            ended = true;
            closeControl.recordingAvailable(recording != null);
            if (command != null) orb.recognized(); else orb.state(false, true);
            status.setText(command == null ? "Тест: команда не распознана" : "Тест: " + command.title);
            transcript.setText(text.isEmpty() ? "Речь не распознана" : text);
            details.append("\nПроверка без выполнения команды");
            return;
        }
        if (command == null) { fail(text.isEmpty() ? "Не расслышал команду" : "Команда не распознана"); return; }
        orb.recognized();
        if (command.confirm) {
            status.setText("Подтвердите действие");
            confirmation = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                    .setTitle(command.title).setMessage("Выполнить распознанную команду?")
                    .setPositiveButton("Выполнить", (d, w) -> execute(command))
                    .setNegativeButton("Отмена", (d, w) -> finish()).setOnCancelListener(d -> finish()).create();
            confirmation.show();
        } else execute(command);
    }
    /** Segments run in speech order; rejected ones are shown in red and never sent. */
    private void recognizedSequence(String text, List<VoiceCommandSequence.Segment> segments) {
        sequence = segments; sequenceNext = 0; sequenceAccepted = 0; sequenceDuration = 0;
        showRows(segments);
        int rejected = 0;
        for (VoiceCommandSequence.Segment segment : segments) if (!segment.accepted()) rejected++;
        if (testOnly) {
            ended = true;
            closeControl.recordingAvailable(recording != null);
            if (rejected < segments.size()) orb.recognized(); else orb.state(false, true);
            status.setText("Тест: команд " + segments.size() + ", отклонено " + rejected);
            transcript.setText(text.isEmpty() ? "Речь не распознана" : text);
            details.append("\nПроверка без выполнения команд");
            return;
        }
        if (rejected == segments.size()) { fail("Команды не распознаны"); return; }
        orb.recognized();
        status.setText("Передаю команды…");
        ui.postDelayed(() -> fail("Выполнение команд занимает слишком много времени"), SEQUENCE_BUDGET_MS);
        stepSequence();
    }
    private void stepSequence() {
        while (sequenceNext < sequence.size() && !sequence.get(sequenceNext).accepted()) sequenceNext++;
        if (sequenceNext >= sequence.size()) { finishSequence(); return; }
        final int at = sequenceNext;
        final String token = session;
        status.setText("Команда " + (at + 1) + " из " + sequence.size() + "…");
        rowNotes.get(at).setTextColor(COLOR_NOTE); rowNotes.get(at).setText("Отправляю…");
        ResultReceiver reply = new ResultReceiver(ui) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (!token.equals(session) || ended || isFinishing()) return;
                ui.removeCallbacks(commandTimeout);
                completeStep(at, code == 1 ? null : data == null ? "Не удалось выполнить команду"
                        : data.getString("error", "Не удалось выполнить команду"), data);
            }
        };
        commandTimeout = null;
        submitted = send("execute", sequence.get(at).command.action, reply, at, sequence.size());
        if (!submitted) { completeStep(at, "Не удалось отправить команду", null); return; }
        commandTimeout = () -> {
            if (!token.equals(session) || ended || isFinishing() || sequenceNext != at) return;
            completeStep(at, "Сервис не ответил на команду", null);
        };
        ui.postDelayed(commandTimeout, COMMAND_TIMEOUT_MS);
    }
    /** A failing segment is reported in place; the remaining commands still run. */
    private void completeStep(int at, String error, Bundle data) {
        if (error == null) {
            sequenceAccepted++;
            rowNotes.get(at).setText(noteFor(sequence.get(at).command, data));
            sequenceDuration = Math.max(sequenceDuration,
                    VoiceResultPresentation.successDurationMs(sequence.get(at).command.action));
        } else {
            rowNotes.get(at).setTextColor(COLOR_REJECTED_NOTE); rowNotes.get(at).setText(error);
        }
        sequenceNext = at + 1;
        stepSequence();
    }
    private String noteFor(VoiceCommandCatalog.Command command, Bundle data) {
        if (command.action.startsWith("auto_light:")) {
            getSharedPreferences("DrivePreferences", MODE_PRIVATE).edit()
                    .putBoolean("autoLight", command.action.endsWith(":on")).apply();
        }
        if ("port_cap:fuel".equals(command.action)) {
            int refillLiters = data == null ? -1 : data.getInt("fuelRefillLiters", -1);
            return VoiceResultPresentation.successText(command.action, command.title, refillLiters);
        }
        if (command.action.startsWith("fuel_charge:") && data != null
                && data.getBoolean("chargeTargetConfirmed", false)) {
            return "Уровень поддержания заряда подтверждён";
        }
        if (VoiceSeatCommands.isAction(command.action) || command.action.startsWith("wheel_heat:")
                || VoiceWindowCommands.isAction(command.action)) {
            return "Команда отправлена";
        }
        return "Выполнено";
    }
    private void finishSequence() {
        if (ended || isFinishing()) return;
        if (sequenceAccepted == 0) { fail("Команды не выполнены"); return; }
        ended = true; recognizer.cancel(); releaseFocus(); ui.removeCallbacksAndMessages(null);
        orb.recognized(); status.setText("Команды переданы");
        sounds.success();
        closeControl.recordingAvailable(recording != null);
        long duration = Math.max(3000, Math.max(sequenceDuration, SEQUENCE_ROW_MS * sequenceAccepted));
        ui.postDelayed(() -> finish(), duration);
    }
    private void showRows(List<VoiceCommandSequence.Segment> segments) {
        clearRows();
        for (int at = 0; at < segments.size(); at++) {
            VoiceCommandSequence.Segment segment = segments.get(at);
            TextView title = label(22);
            title.setText((at + 1) + ". " + (segment.accepted() ? segment.command.title : segment.text));
            TextView note = label(16);
            note.setTextColor(COLOR_NOTE);
            if (segment.accepted()) {
                note.setText("");
            } else {
                title.setTextColor(COLOR_REJECTED);
                note.setTextColor(COLOR_REJECTED_NOTE);
                note.setText(segment.reason);
            }
            resultList.addView(title); resultList.addView(note);
            rowNotes.add(note);
        }
    }
    private void clearRows() {
        resultList.removeAllViews(); rowNotes.clear();
    }
    private void execute(VoiceCommandCatalog.Command command) {
        final String token = session;
        status.setText("Передаю команду…");
        transcript.setText(command.title);
        ResultReceiver reply = new ResultReceiver(ui) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (!token.equals(session) || ended || isFinishing()) return;
                if (code != 1) { fail(data == null ? "Не удалось выполнить команду" : data.getString("error", "Не удалось выполнить команду")); return; }
                if (command.action.startsWith("auto_light:")) {
                    getSharedPreferences("DrivePreferences", MODE_PRIVATE).edit()
                            .putBoolean("autoLight", command.action.endsWith(":on")).apply();
                }
                int refillLiters = data == null ? -1 : data.getInt("fuelRefillLiters", -1);
                showSuccess(VoiceResultPresentation.successText(command.action, command.title, refillLiters),
                        VoiceResultPresentation.successDurationMs(command.action));
                if (VoiceSeatCommands.isAction(command.action) || command.action.startsWith("wheel_heat:")
                        || VoiceWindowCommands.isAction(command.action)) {
                    status.setText("Команда отправлена");
                }
                if (command.action.startsWith("fuel_charge:") && data != null
                        && data.getBoolean("chargeTargetConfirmed", false)) {
                    status.setText("Уровень поддержания заряда подтверждён");
                }
            }
        };
        submitted = send("execute", command.action, reply);
        if (!submitted) { fail("Не удалось отправить команду"); return; }
        ui.postDelayed(() -> fail("Сервис не ответил на команду"), 8000);
    }
    private boolean send(String op, String action, ResultReceiver reply) {
        return send(op, action, reply, 0, 1);
    }
    private boolean send(String op, String action, ResultReceiver reply, int index, int count) {
        if (nativeService == null || session == null) return false;
        try {
            nativeService.send(VoiceCommandMessage.create(session, op, action, reply, index, count)); return true;
        } catch (Exception e) { return false; }
    }
    private void fail(String message) {
        if (ended || isFinishing()) return;
        ended = true; recognizer.cancel(); releaseFocus(); send("cancel", null, null);
        ui.removeCallbacksAndMessages(null); orb.state(false, true); status.setText(message);
        sounds.error();
        closeControl.recordingAvailable(recording != null);
        if (!testOnly) ui.postDelayed(() -> finish(), 6000);
    }
    private void showSuccess(String title) {
        showSuccess(title, 3000);
    }
    private void showSuccess(String title, int durationMs) {
        ended = true; recognizer.cancel(); releaseFocus(); ui.removeCallbacksAndMessages(null);
        orb.recognized(); status.setText("Команда передана"); transcript.setText(title);
        sounds.success();
        closeControl.recordingAvailable(recording != null);
        ui.postDelayed(() -> finish(), durationMs);
    }
    private void releaseFocus() {
        if (focus != null && audio != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
    }
    private void playRecording() {
        if (!testOnly) return;
        if (playback != null) { stopPlayback(); return; }
        if (!ended || recording == null || !recording.isFile()) return;
        ui.removeCallbacksAndMessages(null); // Hold the finished result screen while inspecting audio.
        sounds.stop();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change -> { if (change < 0) stopPlayback(); }, ui).build();
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            releaseFocus();
            status.setText("Не удалось получить звук для прослушивания");
            return;
        }
        MediaPlayer player = new MediaPlayer();
        playback = player;
        closeControl.playing(true);
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        player.setOnPreparedListener(p -> { if (playback == p && !isFinishing()) p.start(); });
        player.setOnCompletionListener(p -> { if (playback == p) stopPlayback(); });
        player.setOnErrorListener((p, what, extra) -> {
            if (playback != p) return true;
            stopPlayback(); status.setText("Не удалось прослушать запись"); return true;
        });
        try { player.setDataSource(recording.getAbsolutePath()); player.prepareAsync(); }
        catch (Exception e) { stopPlayback(); status.setText("Не удалось открыть запись"); }
    }
    private void stopPlayback() {
        if (playback != null) {
            playback.setOnPreparedListener(null); playback.setOnCompletionListener(null); playback.setOnErrorListener(null);
            playback.release(); playback = null; releaseFocus();
        }
        closeControl.playing(false);
    }
    private void clearRecording() {
        stopPlayback();
        closeControl.recordingAvailable(false);
        if (recording != null) { recording.delete(); recording = null; }
    }
    private void cancelSession() {
        sounds.stop();
        ended = true; recognizer.cancel(); releaseFocus(); ui.removeCallbacksAndMessages(null);
        if (confirmation != null) { confirmation.dismiss(); confirmation = null; }
        send("cancel", null, null);
    }
    @Override protected void onResume() {
        super.onResume();
        // onNewIntent starts a replacement session between pause and resume.
        // A resume without a new invocation must not revive a cancelled session.
        if (interrupted) finish();
    }
    @Override protected void onPause() {
        clearRecording();
        sounds.stop();
        interrupted = true;
        recognizer.cancel(); releaseFocus();
        if (!submitted) cancelSession();
        super.onPause();
    }
    @Override protected void onStop() {
        super.onStop(); recognizer.cancel(); releaseFocus();
        if (!submitted) cancelSession();
        finish();
    }
    @Override protected void onDestroy() {
        clearRecording();
        sounds.release();
        closeControl.dispose();
        recognizer.cancel(); releaseFocus(); ui.removeCallbacksAndMessages(null);
        if (!submitted) send("cancel", null, null);
        if (bound) unbindService(connection);
        super.onDestroy();
    }
}
