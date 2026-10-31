package ru.big.town.restoremode.settings.sections.vehicle;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.controls.SettingsToggle;
import ru.big.town.restoremode.settings.ui.layout.SettingsComponents;
import ru.big.town.restoremode.settings.ui.layout.SettingsPanels;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;
import ru.big.town.updater.ui.TurnSoundClient;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TurnSoundSettingsPage {
    private static final String[] KEYS = {
        "original",
        "Alfa_Romeo",
        "Audi",
        "BMW",
        "Ferrari_Luce",
        "Land_Rover",
        "Lexus_LFA",
        "Lexus_LS",
        "Mercedes",
        "Mini_Cooper",
        "Porsche",
        "Volvo",
        "Xiaomi_SU7"
    };
    private static final String[] LABELS = {
        "Оригинальный (из набора)",
        "Alfa Romeo",
        "Audi",
        "BMW",
        "Ferrari Luce",
        "Land Rover",
        "Lexus LFA",
        "Lexus LS",
        "Mercedes-Benz",
        "Mini Cooper",
        "Porsche",
        "Volvo",
        "Xiaomi SU7"
    };
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final SharedPreferences preferences;
    private final Runnable changed;
    private final SettingsComponents components;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TurnSoundPreview preview;
    private TextView progressLabel;
    private boolean closed;
    private boolean busy;
    private boolean known;
    private boolean enabled;
    private String sound = "original";
    private int volume = 60;
    private String status = "Проверка состояния приборки…";

    TurnSoundSettingsPage(Activity activity, SharedPreferences preferences, Runnable changed) {
        this.activity = activity;
        this.preferences = preferences;
        preview = new TurnSoundPreview(activity);
        sound = preferences.getString("turnSignalSoundType", "original");
        if (!java.util.Arrays.asList(KEYS).contains(sound)) {
            sound = "original";
        }
        volume = Math.max(15, Math.min(100, preferences.getInt("turnSignalSoundVolume", 60)));
        this.changed = changed;
        components = new SettingsComponents(activity);
        request("status");
    }

    void close() {
        preview.stop();
        closed = true;
        progressLabel = null;
    }

    void addRows(List<SettingsList.Row> rows) {
        rows.add(
                new SettingsList.Row(
                        "turn-sound:heading", parent -> components.heading("Звук поворотников")));
        rows.add(
                new SettingsList.Row(
                        "turn-sound:controls",
                        parent -> {
                            SettingsToggle toggle = new SettingsToggle(activity, null);
                            toggle.setChecked(enabled);
                            toggle.setEnabled(known && !busy);
                            toggle.setOnCheckedChangeListener(
                                    (button, checked) -> enabled = checked);

                            Button choose = new Button(activity);
                            choose.setText(label());
                            choose.setEnabled(!busy);
                            choose.setOnClickListener(view -> chooseSound());

                            TextView level = components.text(volume + "%", 22, 0xffb2d3fb);
                            SeekBar slider =
                                    new androidx.appcompat.widget.AppCompatSeekBar(activity);
                            slider.setMax(85);
                            slider.setProgress(Math.max(0, volume - 15));
                            slider.setEnabled(!busy);
                            slider.setContentDescription("Громкость звука поворотников");
                            slider.setProgressTintList(ColorStateList.valueOf(0xffaccdff));
                            slider.setThumbTintList(ColorStateList.valueOf(0xffaccdff));
                            slider.setOnSeekBarChangeListener(
                                    new SeekBar.OnSeekBarChangeListener() {
                                        @Override
                                        public void onProgressChanged(
                                                SeekBar view, int progress, boolean fromUser) {
                                            if (fromUser) {
                                                preview.stop();
                                                volume = progress + 15;
                                                level.setText(volume + "%");
                                            }
                                        }

                                        @Override
                                        public void onStartTrackingTouch(SeekBar view) {}

                                        @Override
                                        public void onStopTrackingTouch(SeekBar view) {
                                            saveSelection();
                                        }
                                    });

                            Button listen = new Button(activity);
                            listen.setText("Прослушать");
                            listen.setEnabled(!busy);
                            listen.setOnClickListener(view -> preview.play(sound, volume));

                            Button apply = new Button(activity);
                            apply.setText("Применить");
                            apply.setEnabled(known && !busy);
                            apply.setOnClickListener(
                                    view -> request(enabled ? "apply" : "restore"));

                            LinearLayout card =
                                    components.card(
                                            components.row(
                                                    "Изменять звук поворотников",
                                                    "Выберите состояние и нажмите «Применить»."
                                                            + " Выключение вернёт сохранённый звук"
                                                            + " и его исходную громкость.",
                                                    toggle),
                                            progressPanel(),
                                            components.text(
                                                    "Изменения вступают в силу после полной"
                                                        + " перезагрузки. Одновременно зажмите"
                                                        + " кнопки «звёздочка» и «трубка» на руле и"
                                                        + " удерживайте не менее 15 секунд, пока не"
                                                        + " погаснут экраны.",
                                                    17,
                                                    0xff9aaac0),
                                            components.row(
                                                    "Тип звука", "Два звука: тик и так", choose),
                                            components.row(
                                                    "Громкость",
                                                    "15–100% от амплитуды выбранного звука",
                                                    level),
                                            slider,
                                            components.row(
                                                    "Предпрослушивание",
                                                    "Через динамики мультимедиа; громкость приборки"
                                                            + " может отличаться",
                                                    listen),
                                            apply,
                                            components.text(
                                                    "Оригиналы сохраняются перед первой заменой и"
                                                        + " не удаляются при удалении VoyahTune.",
                                                    17,
                                                    0xff9aaac0));
                            if (!known && !busy) {
                                Button retry = new Button(activity);
                                retry.setText("Проверить состояние");
                                retry.setOnClickListener(view -> request("status"));
                                card.addView(retry);
                                Button restore = new Button(activity);
                                restore.setText("Восстановить оригиналы");
                                restore.setOnClickListener(view -> request("restore"));
                                card.addView(restore);
                            }
                            SettingsDesign.bindRow(activity, card);
                            return SettingsPanels.spaced(card);
                        }));
    }

    private LinearLayout progressPanel() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        progressLabel = components.text(status, 17, 0xffb2d3fb);
        progressLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(progressLabel);
        ProgressBar bar =
                new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(ColorStateList.valueOf(0xffaccdff));
        bar.setContentDescription("Выполнение операции со звуком поворотников");
        bar.setVisibility(busy ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams layout =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, components.dp(8));
        layout.topMargin = components.dp(8);
        layout.bottomMargin = components.dp(8);
        panel.addView(bar, layout);
        return panel;
    }

    private void showProgress(String stage) {
        main.post(
                () -> {
                    if (closed || activity.isDestroyed() || !busy) {
                        return;
                    }
                    status = TurnSoundProgress.text(stage);
                    if (progressLabel != null) {
                        progressLabel.setText(status);
                    }
                });
    }

    private String label() {
        for (int index = 0; index < KEYS.length; index++) {
            if (KEYS[index].equals(sound)) {
                return LABELS[index];
            }
        }
        return sound;
    }

    private void chooseSound() {
        int selected = 0;
        for (int index = 0; index < KEYS.length; index++) {
            if (KEYS[index].equals(sound)) {
                selected = index;
            }
        }
        new MaterialAlertDialogBuilder(activity, R.style.SettingsDialog)
                .setTitle("Тип звука поворотников")
                .setSingleChoiceItems(
                        LABELS,
                        selected,
                        (dialog, index) -> {
                            preview.stop();
                            sound = KEYS[index];
                            saveSelection();
                            dialog.dismiss();
                            changed.run();
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void saveSelection() {
        preferences
                .edit()
                .putString("turnSignalSoundType", sound)
                .putInt("turnSignalSoundVolume", volume)
                .apply();
    }

    private void request(String action) {
        if (busy || closed) {
            return;
        }
        preview.stop();
        busy = true;
        status =
                "status".equals(action)
                        ? "Проверка состояния приборки…"
                        : "restore".equals(action)
                                ? "Восстановление оригиналов…"
                                : "Проверка резервной копии и запись звука…";
        final String selectedSound = sound;
        final int selectedVolume = volume;
        main.post(
                () -> {
                    if (!closed && !activity.isDestroyed()) {
                        changed.run();
                    }
                });
        WORKER.execute(
                () -> {
                    JSONObject result = null;
                    String error = null;
                    try {
                        result =
                                new TurnSoundClient()
                                        .call(
                                                action,
                                                selectedSound,
                                                selectedVolume,
                                                this::showProgress);
                    } catch (Exception failure) {
                        error =
                                failure instanceof UnsupportedOperationException
                                        ? "Требуется обновление root-службы через USB"
                                        : "Не удалось подтвердить состояние: "
                                                + failure.getMessage();
                    }
                    final JSONObject response = result;
                    final String failure = error;
                    main.post(
                            () -> {
                                if (closed || activity.isDestroyed()) {
                                    return;
                                }
                                busy = false;
                                known = response != null;
                                if (known) {
                                    enabled = response.optBoolean("enabled", false);
                                    if (enabled && "status".equals(action)) {
                                        String appliedSound =
                                                response.optString("sound", "original");
                                        sound =
                                                java.util.Arrays.asList(KEYS).contains(appliedSound)
                                                        ? appliedSound
                                                        : "original";
                                        volume =
                                                Math.max(
                                                        15,
                                                        Math.min(
                                                                100,
                                                                response.optInt("volume", 60)));
                                    }
                                    status =
                                            enabled
                                                    ? "Файлы звука записаны · требуется полная"
                                                            + " перезагрузка"
                                                    : "restore".equals(action)
                                                            ? "Оригиналы восстановлены · требуется"
                                                                  + " полная перезагрузка"
                                                            : "Выключено · замена звука не активна";
                                } else {
                                    status = failure;
                                }
                                changed.run();
                            });
                });
    }
}
