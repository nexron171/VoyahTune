package ru.big.town.restoremode;


import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Content of the voice section; the host owns the rail, title and one full-page scroll view. */
final class VoiceSettingsPage {
    private static final int MICROPHONE_REQUEST = 41;
    private static final int TEST_MICROPHONE_REQUEST = 42;
    private final AppCompatActivity activity;
    private final SharedPreferences prefs;
    private final Runnable changed;
    private final Switch enabled, shortcut;
    private final Button tryVoice;
    private final SettingsChoiceGroup steeringPress;
    private final int shortPressId = View.generateViewId(), longPressId = View.generateViewId();
    private final LinearLayout commands;
    private final LinearLayout deepFilterStrength;
    private final TextView deepFilterStrengthLabel;
    private final SeekBar deepFilterStrengthSlider;
    private boolean updating;

    VoiceSettingsPage(AppCompatActivity activity, LinearLayout content,
                      SharedPreferences prefs, Runnable changed) {
        this.activity = activity; this.prefs = prefs; this.changed = changed;
        enabled = setting(content, "Включить голосового помощника");
        content.addView(text("Выберите короткое или долгое нажатие кнопки голосового помощника на руле. Повторный вызов начинает новую сессию. Прежние действия выбранного нажатия сохранятся и вернутся после смены нажатия или отключения помощника. Помощника также можно вызвать кнопкой «Попробовать голосовую команду» или ярлыком на главном экране.", 20, activity.getColor(R.color.settings_muted)));
        steeringPress = new SettingsChoiceGroup(activity, null);
        for (int i = 0; i < 2; i++) {
            android.widget.RadioButton choice = new android.widget.RadioButton(activity);
            choice.setId(i == 0 ? shortPressId : longPressId);
            choice.setText(i == 0 ? "Короткое нажатие" : "Долгое нажатие");
            steeringPress.addView(choice);
        }
        content.addView(steeringPress, new LinearLayout.LayoutParams(dp(480), -2));
        updateSteeringPress();
        steeringPress.setOnCheckedChangeListener((group, id) -> {
            if (updating) return;
            prefs.edit().putString(VoiceSteeringPolicy.PRESS_KEY,
                    id == shortPressId ? VoiceSteeringPolicy.SHORT : VoiceSteeringPolicy.LONG).apply();
            SplitConfigSync.pushSteering(activity, prefs);
            changed.run();
        });
        content.addView(text("Распознавание работает без интернета. Произносите одну команду за раз. Не обязательно произносить фразу целиком: достаточно ключевых слов, например «спорт» или «фары авто». Слова «выключи» и «переключи» определяют действие — их пропускать нельзя. Можно менять порядок слов и добавлять «пожалуйста». Помощник учитывает окончания и небольшие ошибки в названиях автомобильных команд. Неизвестная или неоднозначная фраза не выполняется.", 20, activity.getColor(R.color.settings_muted)));
        content.addView(text("Массаж, подогрев и вентиляция: без указания места команда относится к водителю. Для переднего пассажира добавьте «пассажира», например «массаж пассажира волны» или «подогрев сиденья пассажира три». Уровни — от 1 до 3. Выбор уровня или типа массажа может одновременно включить функцию. Доступность зависит от комплектации и прошивки автомобиля.", 20, activity.getColor(R.color.settings_muted)));
        content.addView(text("Окна: «открой» или «опусти» — открыть, «закрой» или «подними» — закрыть. Можно указать водителя, переднего пассажира, заднее левое/правое окно или группу: передние, задние, левые, правые. «Открой окна» относится ко всем четырём; для одного окна укажите место. «Проветривание» приоткрывает все четыре окна, «проветривание люка» — только люк. Величину открытия задаёт автомобиль. Шторка управляется отдельно: «открой шторку» / «закрой шторку». После отправки помощник не проверяет фактическое положение.", 20, activity.getColor(R.color.settings_muted)));
        deepFilterStrength = new LinearLayout(activity);
        deepFilterStrength.setOrientation(LinearLayout.VERTICAL);
        deepFilterStrength.setBackgroundResource(R.drawable.settings_card);
        deepFilterStrength.setPadding(dp(16), dp(10), dp(16), dp(10));
        content.addView(deepFilterStrength, new LinearLayout.LayoutParams(-1, -2));
        deepFilterStrengthLabel = text("", 26, activity.getColor(R.color.settings_text));
        deepFilterStrength.addView(deepFilterStrengthLabel);
        deepFilterStrengthSlider = new androidx.appcompat.widget.AppCompatSeekBar(activity);
        deepFilterStrengthSlider.setMax(VoiceAudioConfig.MAX_DEEP_FILTER_DB);
        deepFilterStrengthSlider.setContentDescription("Сила подавления шума");
        deepFilterStrength.addView(deepFilterStrengthSlider, new LinearLayout.LayoutParams(-1, dp(48)));
        deepFilterStrength.addView(text("0 дБ — без обработки · 6 дБ — мягче · 30 дБ — сильнее.\nМеньше значение — больше исходного звука и меньше искажений голоса. Настройка действует со следующей записи.", 20, activity.getColor(R.color.settings_muted)));
        deepFilterStrengthSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                deepFilterStrengthLabel.setText(value + " дБ");
                if (fromUser) prefs.edit().putInt(VoiceAudioConfig.DEEP_FILTER_DB_KEY, value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        shortcut = setting(content, "Ярлык «Голосовая команда» на главном экране");
        MaterialButton tryButton = new MaterialButton(activity);
        tryButton.setCornerRadius(dp(20));
        tryVoice = tryButton;
        tryVoice.setText("Попробовать голосовую команду"); tryVoice.setAllCaps(false);
        tryVoice.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26); tryVoice.setTextColor(activity.getColor(R.color.settings_text));
        tryVoice.setBackgroundTintList(ColorStateList.valueOf(activity.getColor(R.color.settings_border)));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-2, dp(64));
        buttonParams.setMargins(0, dp(12), 0, dp(20)); content.addView(tryVoice, buttonParams);
        tryVoice.setOnClickListener(v -> activity.startActivity(new Intent(activity, VoiceActivity.class)));
        MaterialButton testButton = new MaterialButton(activity);
        testButton.setText("Проверить распознавание без выполнения");
        testButton.setAllCaps(false);
        testButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24);
        content.addView(testButton, new LinearLayout.LayoutParams(-1, dp(64)));
        testButton.setOnClickListener(v -> {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, TEST_MICROPHONE_REQUEST);
                return;
            }
            activity.startActivity(new Intent(activity, VoiceActivity.class).putExtra(VoiceActivity.TEST_ONLY, true));
        });
        commands = new LinearLayout(activity); commands.setOrientation(LinearLayout.VERTICAL);
        content.addView(commands, new LinearLayout.LayoutParams(-1, -2));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            if (checked && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                updating = true; enabled.setChecked(false); updating = false;
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST);
            } else saveEnabled(checked);
        });
        shortcut.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) prefs.edit().putBoolean("showVoiceCommand", checked).apply();
        });
        arrangeContent(content, testButton);
    }

    /** Same controls and catalog as before; composition mirrors the approved HTML screen. */
    private void arrangeContent(LinearLayout content, Button testButton) {
        SettingsComponents ui = new SettingsComponents(activity);
        LinearLayout help = new LinearLayout(activity); help.setOrientation(LinearLayout.VERTICAL);
        java.util.List<View> notes = new java.util.ArrayList<>();
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof TextView && !(child instanceof Button)) notes.add(child);
        }
        content.removeAllViews();
        for (View note : notes) help.addView(note);
        LinearLayout intro = ui.intro(R.drawable.settings_icon_mic, "Голос, который понимает автомобиль",
                "Распознавание работает без интернета. Произносите одну команду за раз.");
        SettingsIllustration wave = new SettingsIllustration(activity, null); wave.setTag("settings.wave");
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(dp(260), dp(76)); waveParams.leftMargin = dp(24);
        intro.addView(wave, waveParams); content.addView(intro);
        LinearLayout activation = ui.card(
                ui.row("Включить голосового помощника", "Вызов с руля, из ярлыка или кнопкой ниже. Повторный вызов начинает новую сессию.", enabled),
                ui.row("Вызов кнопкой на руле", "Прежние действия выбранного нажатия сохраняются.", steeringPress),
                ui.row("Ярлык «Голосовая команда»", "Показывать на главном экране.", shortcut));
        steeringPress.setLayoutParams(new LinearLayout.LayoutParams(dp(480), -2));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2); ap.topMargin = dp(22); content.addView(activation, ap);
        content.addView(ui.heading("Звук и распознавание"));
        deepFilterStrength.removeAllViews(); deepFilterStrength.setPadding(dp(25), dp(25), dp(25), dp(25));
        deepFilterStrengthLabel.setTextSize(16); deepFilterStrengthLabel.setTextColor(0xffb2d3fb);
        deepFilterStrengthLabel.setPadding(dp(13), dp(9), dp(13), dp(9));
        deepFilterStrengthLabel.setBackgroundResource(R.drawable.settings_pill);
        deepFilterStrength.addView(ui.head(R.drawable.settings_icon_sound, "Подавление шума", deepFilterStrengthLabel));
        deepFilterStrengthSlider.setPadding(0, 0, 0, 0);
        deepFilterStrengthSlider.setProgressTintList(ColorStateList.valueOf(0xffaccdff));
        deepFilterStrengthSlider.setThumbTintList(ColorStateList.valueOf(0xffaccdff));
        deepFilterStrength.addView(deepFilterStrengthSlider, new LinearLayout.LayoutParams(-1, dp(24)));
        LinearLayout scale = new LinearLayout(activity);
        String[] scaleLabels = {"0 · без обработки", "6 · мягче", "30 · сильнее"};
        for (int i = 0; i < scaleLabels.length; i++) {
            TextView label = ui.text(scaleLabels[i], 16, 0xff8ea0ba);
            label.setGravity(i == 0 ? Gravity.START : i == 1 ? Gravity.CENTER : Gravity.END);
            scale.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams scaleParams = new LinearLayout.LayoutParams(-1, -2); scaleParams.topMargin = dp(12);
        deepFilterStrength.addView(scale, scaleParams);
        TextView noiseNote = ui.text("Меньше значение — больше исходного звука и меньше искажений голоса. Настройка действует со следующей записи.", 17, 0xffa2b0c5);
        noiseNote.setLineSpacing(0, 1.5f);
        noiseNote.setPadding(0, dp(16), 0, 0); deepFilterStrength.addView(noiseNote);
        tryVoice.setTag("settings.primary");
        SettingsFlow trialActions = new SettingsFlow(activity, null);
        SettingsComponents.detach(tryVoice); SettingsComponents.detach(testButton);
        trialActions.addView(tryVoice, new LinearLayout.LayoutParams(-2, -2));
        trialActions.addView(testButton, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout trial = ui.card(ui.head(R.drawable.settings_icon_mic, "Попробуйте команду", null),
                ui.text("Примеры: «спорт», «фары авто», «открой окна».", 17, 0xffa2b0c5), trialActions);
        ((LinearLayout.LayoutParams) trialActions.getLayoutParams()).topMargin = dp(18);
        SettingsGrid grid = new SettingsGrid(activity, null); grid.addView(deepFilterStrength); grid.addView(trial);
        content.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        content.addView(ui.heading("Примеры голосовых команд"));
        content.addView(commands, new LinearLayout.LayoutParams(-1, -2));
        MaterialButton helpToggle = new MaterialButton(activity); helpToggle.setTag("settings.details");
        helpToggle.setAllCaps(false); helpToggle.setText("Как формулировать команды    ＋");
        helpToggle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        helpToggle.setOnClickListener(v -> {
            boolean open = help.getVisibility() != View.VISIBLE;
            help.setVisibility(open ? View.VISIBLE : View.GONE);
            helpToggle.setText("Как формулировать команды    " + (open ? "−" : "＋"));
        });
        help.setVisibility(View.GONE); content.addView(helpToggle, new LinearLayout.LayoutParams(-1, dp(72)));
        content.addView(help, new LinearLayout.LayoutParams(-1, -2));
        SettingsDesign.styleTree(content);
    }

    void refresh() {
        updating = true;
        enabled.setChecked(prefs.getBoolean(VoiceCommands.ENABLED, false));
        shortcut.setChecked(prefs.getBoolean("showVoiceCommand", false));
        updateStrength();
        updateSteeringPress();
        updating = false;
        tryVoice.setEnabled(enabled.isChecked());
        tryVoice.setAlpha(enabled.isChecked() ? 1 : .45f);
        commands.removeAllViews();
        Map<String, VoiceCommandCatalog.Command> actions = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> phrases = new LinkedHashMap<>();
        for (VoiceCommandCatalog.Command command : VoiceCommands.load(activity)) {
            String key = command.action.startsWith(VoiceFuelCommand.PREFIX) ? VoiceFuelCommand.PREFIX : command.action;
            actions.putIfAbsent(key, command);
            phrases.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(command.phrases);
        }
        for (VoiceCommandGroups.Group group : VoiceCommandGroups.Group.values()) {
            Map<String, VoiceCommandCatalog.Command> members = new LinkedHashMap<>();
            for (Map.Entry<String, VoiceCommandCatalog.Command> entry : actions.entrySet()) {
                if (VoiceCommandGroups.forAction(entry.getValue().action).contains(group))
                    members.put(entry.getKey(), entry.getValue());
            }
            commandGroup(group, members, phrases);
        }
    }

    private void commandGroup(VoiceCommandGroups.Group group,
                              Map<String, VoiceCommandCatalog.Command> members,
                              Map<String, LinkedHashSet<String>> phrases) {
        SettingsComponents ui = new SettingsComponents(activity);
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.settings_details); card.setPadding(dp(1), dp(1), dp(1), dp(1));
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        header.setPadding(dp(24), dp(22), dp(24), dp(22)); header.setFocusable(true);
        header.addView(ui.text(group.title, 23, 0xfff3f5fa), new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = ui.text(members.size() + " команд", 16, 0xff839bbd);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(-2, -2); countParams.rightMargin = dp(20);
        header.addView(count, countParams);
        TextView marker = ui.text("＋", 22, 0xff9ebcdf); header.addView(marker);
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(24), 0, dp(24), dp(24));
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2); cardParams.topMargin = dp(14);
        commands.addView(card, cardParams); SettingsDesign.styleTree(card);
        String key = "voiceCommandGroupExpanded_" + group.name();
        Runnable update = () -> {
            boolean expanded = prefs.getBoolean(key, false);
            marker.setText(expanded ? "−" : "＋");
            header.setContentDescription(group.title + ", " + (expanded ? "свернуть группу" : "раскрыть группу"));
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            // Build rows on first expansion; collapsed app lists can contain hundreds of phrases.
            if (expanded && body.getChildCount() == 0) {
                for (Map.Entry<String, VoiceCommandCatalog.Command> entry : members.entrySet()) {
                    VoiceCommandCatalog.Command command = entry.getValue();
                    boolean fuel = entry.getKey().equals(VoiceFuelCommand.PREFIX);
                    row(body, fuel ? "Топливо: поддержание заряда (SREV)"
                                    : command.title + (command.confirm ? "\nС подтверждением" : ""),
                            (fuel ? "Топливо <число>: словами или цифрами. Любое число округляется до ближайших 5% в пределах 25–80%. Например: топливо семьдесят три → 75%.\n" : "")
                                    + String.join("; ", phrases.get(entry.getKey())));
                }
            }
        };
        header.setOnClickListener(v -> {
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, false)).apply();
            update.run();
        });
        update.run();
    }

    private String selectedPress() {
        return VoiceSteeringPolicy.normalize(prefs.getString(VoiceSteeringPolicy.PRESS_KEY, VoiceSteeringPolicy.LONG));
    }

    private void updateSteeringPress() {
        steeringPress.check(VoiceSteeringPolicy.SHORT.equals(selectedPress()) ? shortPressId : longPressId);
    }

    private void updateStrength() {
        VoiceAudioConfig selected = VoiceAudioConfig.read(prefs);
        deepFilterStrengthSlider.setProgress(selected.deepFilterDb);
        deepFilterStrengthLabel.setText(selected.deepFilterDb + " дБ");
    }

    private Switch setting(LinearLayout content, String label) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_card);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView title = text(label, 26, activity.getColor(R.color.settings_text));
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new SettingsToggle(activity); toggle.setContentDescription(label);
        toggle.setThumbResource(R.drawable.switch_thumb); toggle.setTrackResource(R.drawable.switch_track);
        row.addView(toggle);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10); content.addView(row, params);
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        return toggle;
    }

    private void row(LinearLayout parent, String action, String phrases) {
        SettingsComponents ui = new SettingsComponents(activity);
        View divider = new View(activity); divider.setBackgroundColor(0xff364359);
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(15), 0, dp(15));
        TextView title = ui.text(action, 18, 0xffcedef3), variants = ui.text(phrases, 18, 0xffa1b1c8);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams phrasesParams = new LinearLayout.LayoutParams(0, -2, 2); phrasesParams.leftMargin = dp(24);
        row.addView(variants, phrasesParams);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        SettingsDesign.styleTree(row);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size >= 24 ? 20 : 16);
        view.setPadding(0, dp(4), 0, dp(12));
        return view;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void saveEnabled(boolean value) {
        prefs.edit().putBoolean(VoiceCommands.ENABLED, value).apply();
        VoiceWarmupService.sync(activity);
        updating = true; enabled.setChecked(value); updating = false;
        tryVoice.setEnabled(value); tryVoice.setAlpha(value ? 1 : .45f);
        SplitConfigSync.pushSteering(activity, prefs); changed.run();
    }
    void onPermissionResult(int request, int[] grants) {
        if (request == TEST_MICROPHONE_REQUEST) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                activity.startActivity(new Intent(activity, VoiceActivity.class).putExtra(VoiceActivity.TEST_ONLY, true));
            } else Toast.makeText(activity, "Для проверки разрешите доступ к микрофону", Toast.LENGTH_LONG).show();
            return;
        }
        if (request != MICROPHONE_REQUEST) return;
        boolean allowed = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        saveEnabled(allowed);
        if (!allowed) Toast.makeText(activity, "Для голосового управления разрешите микрофон в настройках Android",
                Toast.LENGTH_LONG).show();
    }
}
