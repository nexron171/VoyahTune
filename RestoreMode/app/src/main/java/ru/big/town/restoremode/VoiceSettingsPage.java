package ru.big.town.restoremode;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Voice settings retain preferences and catalog data; the viewport owns their Views. */
final class VoiceSettingsPage {
    private static final int MICROPHONE_REQUEST = 41;
    private static final int TEST_MICROPHONE_REQUEST = 42;
    private static final String HELP_EXPANDED = "voiceHelpExpanded";
    private static final String[] HELP_PARAGRAPHS = {
        "Выберите короткое или долгое нажатие кнопки голосового помощника на руле. Повторный "
                + "вызов начинает новую сессию. Прежние действия выбранного нажатия сохранятся и "
                + "вернутся после смены нажатия или отключения помощника. Помощника также можно "
                + "вызвать кнопкой «Попробовать голосовую команду» или ярлыком на главном экране.",
        "Распознавание работает без интернета. Произносите одну команду за раз. Не обязательно"
            + " произносить фразу целиком: достаточно ключевых слов, например «спорт» или «фары"
            + " авто». Слова «выключи» и «переключи» определяют действие — их пропускать нельзя."
            + " Можно менять порядок слов и добавлять «пожалуйста». Помощник учитывает окончания и"
            + " небольшие ошибки в названиях автомобильных команд. Неизвестная или неоднозначная"
            + " фраза не выполняется.",
        "Массаж, подогрев и вентиляция: без указания места команда относится к водителю. Для"
            + " переднего пассажира добавьте «пассажира», например «массаж пассажира волны» или"
            + " «подогрев сиденья пассажира три». Уровни — от 1 до 3. Выбор уровня или типа массажа"
            + " может одновременно включить функцию. Доступность зависит от комплектации и прошивки"
            + " автомобиля.",
        "Окна: «открой» или «опусти» — открыть, «закрой» или «подними» — закрыть. Можно указать"
            + " водителя, переднего пассажира, заднее левое/правое окно или группу: передние,"
            + " задние, левые, правые. «Открой окна» относится ко всем четырём; для одного окна"
            + " укажите место. «Проветривание» приоткрывает все четыре окна, «проветривание люка» —"
            + " только люк. Величину открытия задаёт автомобиль. Шторка управляется отдельно:"
            + " «открой шторку» / «закрой шторку». После отправки помощник не проверяет фактическое"
            + " положение."
    };

    private final AppCompatActivity activity;
    private final SharedPreferences preferences;
    private final Runnable steeringChanged;
    private final SettingsList viewport;
    private final SettingsComponents components;
    private final ExecutorService catalogWorker = Executors.newSingleThreadExecutor();
    private final Map<String, VoiceCommandCatalog.Command> commandsByAction = new LinkedHashMap<>();
    private final Map<String, LinkedHashSet<String>> phrasesByAction = new LinkedHashMap<>();
    private boolean catalogLoading;
    private boolean catalogLoaded;
    private boolean closed;

    VoiceSettingsPage(
            AppCompatActivity activity,
            SettingsList viewport,
            SharedPreferences preferences,
            Runnable steeringChanged) {
        this.activity = activity;
        this.viewport = viewport;
        this.preferences = preferences;
        this.steeringChanged = steeringChanged;
        components = new SettingsComponents(activity);
    }

    void refresh() {
        refresh(false);
    }

    void refresh(boolean resetScroll) {
        if (closed || !SettingsSection.VOICE.equals(viewport.getTag())) {
            return;
        }
        if (!catalogLoaded || resetScroll) {
            loadCatalog();
        }
        List<SettingsList.Row> rows = new ArrayList<>();
        addRow(rows, "intro", this::createIntroduction);
        addRow(rows, "activation", this::createActivationCard);
        addRow(rows, "audioHeading", () -> components.heading("Звук и распознавание"));
        addRow(rows, "audio", this::createAudioAndTrialCards);
        addRow(rows, "commandsHeading", () -> components.heading("Примеры голосовых команд"));
        for (VoiceCommandGroups.Group group : VoiceCommandGroups.Group.values()) {
            List<String> actions = new ArrayList<>();
            for (Map.Entry<String, VoiceCommandCatalog.Command> entry :
                    commandsByAction.entrySet()) {
                if (VoiceCommandGroups.forAction(entry.getValue().action).contains(group)) {
                    actions.add(entry.getKey());
                }
            }
            addRow(rows, "group:" + group.name(), () -> createGroupHeader(group, actions.size()));
            if (preferences.getBoolean(groupPreference(group), false)) {
                for (String action : actions) {
                    addRow(
                            rows,
                            "command:" + group.name() + ":" + action,
                            () -> createCommandRow(action));
                }
            }
        }
        addRow(rows, "helpToggle", this::createHelpToggle);
        if (preferences.getBoolean(HELP_EXPANDED, false)) {
            for (int index = 0; index < HELP_PARAGRAPHS.length; index++) {
                String paragraph = HELP_PARAGRAPHS[index];
                addRow(rows, "help:" + index, () -> components.text(paragraph, 17, 0xff97a6bc));
            }
        }
        viewport.submit(rows, resetScroll);
    }

    private void addRow(List<SettingsList.Row> rows, String key, Supplier<View> factory) {
        rows.add(
                new SettingsList.Row(
                        "voice:" + key,
                        parent -> {
                            View view = factory.get();
                            SettingsDesign.styleTree(view);
                            return view;
                        }));
    }

    private void loadCatalog() {
        if (catalogLoading) {
            return;
        }
        catalogLoading = true;
        catalogWorker.execute(
                () -> {
                    List<VoiceCommandCatalog.Command> catalog = VoiceCommands.load(activity);
                    activity.runOnUiThread(
                            () -> {
                                if (closed) {
                                    return;
                                }
                                commandsByAction.clear();
                                phrasesByAction.clear();
                                for (VoiceCommandCatalog.Command command : catalog) {
                                    String key =
                                            command.action.startsWith(VoiceFuelCommand.PREFIX)
                                                    ? VoiceFuelCommand.PREFIX
                                                    : command.action;
                                    commandsByAction.putIfAbsent(key, command);
                                    phrasesByAction
                                            .computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                                            .addAll(command.phrases);
                                }
                                catalogLoading = false;
                                catalogLoaded = true;
                                refresh(false);
                            });
                });
    }

    private View createIntroduction() {
        LinearLayout introduction =
                components.intro(
                        R.drawable.settings_icon_mic,
                        "Голос, который понимает автомобиль",
                        "Распознавание работает без интернета. Произносите одну команду за раз.");
        SettingsIllustration wave = new SettingsIllustration(activity, null);
        wave.setTag("settings.wave");
        LinearLayout.LayoutParams waveSize = new LinearLayout.LayoutParams(dp(260), dp(76));
        waveSize.leftMargin = dp(24);
        introduction.addView(wave, waveSize);
        return introduction;
    }

    private View createActivationCard() {
        Switch enabled = new SettingsToggle(activity);
        enabled.setContentDescription("Включить голосового помощника");
        enabled.setChecked(preferences.getBoolean(VoiceCommands.ENABLED, false));
        enabled.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (checked && !hasMicrophonePermission()) {
                        activity.requestPermissions(
                                new String[] {Manifest.permission.RECORD_AUDIO},
                                MICROPHONE_REQUEST);
                        refresh();
                    } else {
                        saveEnabled(checked);
                    }
                });
        SettingsChoiceGroup press = new SettingsChoiceGroup(activity, null);
        int shortPressId = View.generateViewId();
        int longPressId = View.generateViewId();
        RadioButton shortPress = new RadioButton(activity);
        shortPress.setId(shortPressId);
        shortPress.setText("Короткое нажатие");
        press.addView(shortPress);
        RadioButton longPress = new RadioButton(activity);
        longPress.setId(longPressId);
        longPress.setText("Долгое нажатие");
        press.addView(longPress);
        String selected =
                VoiceSteeringPolicy.normalize(
                        preferences.getString(
                                VoiceSteeringPolicy.PRESS_KEY, VoiceSteeringPolicy.LONG));
        press.check(VoiceSteeringPolicy.SHORT.equals(selected) ? shortPressId : longPressId);
        press.setOnCheckedChangeListener(
                (group, id) -> {
                    preferences
                            .edit()
                            .putString(
                                    VoiceSteeringPolicy.PRESS_KEY,
                                    id == shortPressId
                                            ? VoiceSteeringPolicy.SHORT
                                            : VoiceSteeringPolicy.LONG)
                            .apply();
                    SplitConfigSync.pushSteering(activity, preferences);
                    steeringChanged.run();
                });
        Switch shortcut = new SettingsToggle(activity);
        shortcut.setContentDescription("Ярлык «Голосовая команда» на главном экране");
        shortcut.setChecked(preferences.getBoolean("showVoiceCommand", false));
        shortcut.setOnCheckedChangeListener(
                (button, checked) ->
                        preferences.edit().putBoolean("showVoiceCommand", checked).apply());
        LinearLayout card =
                components.card(
                        components.row(
                                "Включить голосового помощника",
                                "Вызов с руля, из ярлыка или кнопкой ниже. "
                                        + "Повторный вызов начинает новую сессию.",
                                enabled),
                        components.row(
                                "Вызов кнопкой на руле",
                                "Прежние действия выбранного нажатия сохраняются.",
                                press),
                        components.row(
                                "Ярлык «Голосовая команда»",
                                "Показывать на главном экране.",
                                shortcut));
        press.setLayoutParams(new LinearLayout.LayoutParams(dp(480), -2));
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
        spacing.topMargin = dp(22);
        card.setLayoutParams(spacing);
        return card;
    }

    private View createAudioAndTrialCards() {
        TextView strength =
                components.text(
                        VoiceAudioConfig.read(preferences).deepFilterDb + " дБ", 16, 0xffb2d3fb);
        strength.setPadding(dp(13), dp(9), dp(13), dp(9));
        strength.setBackgroundResource(R.drawable.settings_pill);
        SeekBar slider = new androidx.appcompat.widget.AppCompatSeekBar(activity);
        slider.setMax(VoiceAudioConfig.MAX_DEEP_FILTER_DB);
        slider.setProgress(VoiceAudioConfig.read(preferences).deepFilterDb);
        slider.setContentDescription("Сила подавления шума");
        slider.setProgressTintList(ColorStateList.valueOf(0xffaccdff));
        slider.setThumbTintList(ColorStateList.valueOf(0xffaccdff));
        slider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar view, int value, boolean fromUser) {
                        strength.setText(value + " дБ");
                        if (fromUser) {
                            preferences
                                    .edit()
                                    .putInt(VoiceAudioConfig.DEEP_FILTER_DB_KEY, value)
                                    .apply();
                        }
                    }

                    public void onStartTrackingTouch(SeekBar view) {}

                    public void onStopTrackingTouch(SeekBar view) {}
                });
        LinearLayout audio =
                components.card(
                        components.head(
                                R.drawable.settings_icon_sound, "Подавление шума", strength));
        audio.addView(slider, new LinearLayout.LayoutParams(-1, dp(24)));
        LinearLayout scale = new LinearLayout(activity);
        String[] labels = {"0 · без обработки", "6 · мягче", "30 · сильнее"};
        for (int index = 0; index < labels.length; index++) {
            TextView label = components.text(labels[index], 16, 0xff8ea0ba);
            label.setGravity(
                    index == 0 ? Gravity.START : index == 1 ? Gravity.CENTER : Gravity.END);
            scale.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams scaleSpacing = new LinearLayout.LayoutParams(-1, -2);
        scaleSpacing.topMargin = dp(12);
        audio.addView(scale, scaleSpacing);
        TextView note =
                components.text(
                        "Меньше значение — больше исходного звука и меньше искажений "
                                + "голоса. Настройка действует со следующей записи.",
                        17,
                        0xffa2b0c5);
        note.setLineSpacing(0, 1.5f);
        note.setPadding(0, dp(16), 0, 0);
        audio.addView(note);
        Button tryVoice = button("Попробовать голосовую команду");
        tryVoice.setTag("settings.primary");
        boolean enabled = preferences.getBoolean(VoiceCommands.ENABLED, false);
        tryVoice.setEnabled(enabled);
        tryVoice.setAlpha(enabled ? 1f : .45f);
        tryVoice.setOnClickListener(
                view -> activity.startActivity(new Intent(activity, VoiceActivity.class)));
        Button testVoice = button("Проверить распознавание без выполнения");
        testVoice.setOnClickListener(
                view -> {
                    if (hasMicrophonePermission()) {
                        openRecognitionTest();
                    } else {
                        activity.requestPermissions(
                                new String[] {Manifest.permission.RECORD_AUDIO},
                                TEST_MICROPHONE_REQUEST);
                    }
                });
        SettingsFlow actions = new SettingsFlow(activity, null);
        actions.addView(tryVoice);
        actions.addView(testVoice);
        LinearLayout trial =
                components.card(
                        components.head(R.drawable.settings_icon_mic, "Попробуйте команду", null),
                        components.text(
                                "Примеры: «спорт», «фары авто», «открой окна».", 17, 0xffa2b0c5),
                        actions);
        ((LinearLayout.LayoutParams) actions.getLayoutParams()).topMargin = dp(18);
        SettingsGrid grid = new SettingsGrid(activity, null);
        grid.addView(audio);
        grid.addView(trial);
        return grid;
    }

    private String groupPreference(VoiceCommandGroups.Group group) {
        return "voiceCommandGroupExpanded_" + group.name();
    }

    private View createGroupHeader(VoiceCommandGroups.Group group, int commandCount) {
        boolean expanded = preferences.getBoolean(groupPreference(group), false);
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(22), dp(24), dp(22));
        header.setBackgroundResource(R.drawable.settings_details);
        header.addView(
                components.text(group.title, 23, 0xfff3f5fa),
                new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(
                components.text(
                        commandCount + " команд   " + (expanded ? "−" : "＋"), 16, 0xff839bbd));
        header.setContentDescription(
                group.title + ", " + (expanded ? "свернуть группу" : "раскрыть группу"));
        header.setOnClickListener(
                view -> {
                    preferences.edit().putBoolean(groupPreference(group), !expanded).apply();
                    refresh();
                });
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
        spacing.topMargin = dp(14);
        header.setLayoutParams(spacing);
        return header;
    }

    private View createCommandRow(String action) {
        VoiceCommandCatalog.Command command = commandsByAction.get(action);
        boolean fuel = action.equals(VoiceFuelCommand.PREFIX);
        String title =
                fuel
                        ? "Топливо: поддержание заряда (SREV)"
                        : command.title + (command.confirm ? "\nС подтверждением" : "");
        String phrases =
                (fuel
                                ? "Топливо <число>: словами или цифрами. Округление до ближайших 5%"
                                        + " в пределах 25–80%.\n"
                                : "")
                        + String.join("; ", phrasesByAction.get(action));
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(24), dp(15), dp(24), dp(15));
        row.setBackgroundResource(R.drawable.settings_details);
        row.addView(
                components.text(title, 18, 0xffcedef3), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams phraseSize = new LinearLayout.LayoutParams(0, -2, 2);
        phraseSize.leftMargin = dp(24);
        row.addView(components.text(phrases, 18, 0xffa1b1c8), phraseSize);
        return row;
    }

    private View createHelpToggle() {
        boolean expanded = preferences.getBoolean(HELP_EXPANDED, false);
        Button toggle = button("Как формулировать команды    " + (expanded ? "−" : "＋"));
        toggle.setTag("settings.details");
        toggle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        toggle.setOnClickListener(
                view -> {
                    preferences.edit().putBoolean(HELP_EXPANDED, !expanded).apply();
                    refresh();
                });
        return toggle;
    }

    private Button button(String label) {
        MaterialButton button = new MaterialButton(activity);
        button.setText(label);
        button.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return button;
    }

    private int dp(int value) {
        return components.dp(value);
    }

    private boolean hasMicrophonePermission() {
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void openRecognitionTest() {
        activity.startActivity(
                new Intent(activity, VoiceActivity.class).putExtra(VoiceActivity.TEST_ONLY, true));
    }

    private void saveEnabled(boolean enabled) {
        preferences.edit().putBoolean(VoiceCommands.ENABLED, enabled).apply();
        VoiceWarmupService.sync(activity);
        SplitConfigSync.pushSteering(activity, preferences);
        steeringChanged.run();
        refresh();
    }

    void onPermissionResult(int request, int[] grants) {
        boolean granted = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        if (request == TEST_MICROPHONE_REQUEST) {
            if (granted) {
                openRecognitionTest();
            } else {
                Toast.makeText(
                                activity,
                                "Для проверки разрешите доступ к микрофону",
                                Toast.LENGTH_LONG)
                        .show();
            }
        } else if (request == MICROPHONE_REQUEST) {
            saveEnabled(granted);
            if (!granted) {
                Toast.makeText(
                                activity,
                                "Для голосового управления разрешите микрофон в настройках Android",
                                Toast.LENGTH_LONG)
                        .show();
            }
        }
    }

    void close() {
        closed = true;
        catalogWorker.shutdownNow();
    }
}
