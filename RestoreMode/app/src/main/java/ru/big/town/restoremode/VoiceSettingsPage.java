package ru.big.town.restoremode;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
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
    private final LinearLayout commands;
    private final LinearLayout deepFilterStrength;
    private final TextView deepFilterStrengthLabel;
    private final SeekBar deepFilterStrengthSlider;
    private boolean updating;

    VoiceSettingsPage(AppCompatActivity activity, LinearLayout content,
                      SharedPreferences prefs, Runnable changed) {
        this.activity = activity; this.prefs = prefs; this.changed = changed;
        enabled = setting(content, "Включить голосового помощника");
        content.addView(text("Вызов голосового помощника кнопкой на руле доступен только в Full. В Light помощник вызывается только из VoyahTune: кнопкой «Попробовать голосовую команду» или ярлыком «Голосовая команда» на главном экране."
                + (BuildConfig.IS_FULL
                ? " Удерживайте кнопку голосового помощника на руле. Повторное удержание начинает новую сессию. Прежнее назначение долгого нажатия сохранится и вернётся после отключения помощника."
                : ""), 20, 0xffaaaaaa));
        content.addView(text("Распознавание работает без интернета. Произносите одну команду за раз. Не обязательно произносить фразу целиком: достаточно ключевых слов, например «спорт» или «фары авто». Слова «выключи» и «переключи» определяют действие — их пропускать нельзя. Можно менять порядок слов и добавлять «пожалуйста». Помощник учитывает окончания и небольшие ошибки в названиях автомобильных команд. Неизвестная или неоднозначная фраза не выполняется.", 20, 0xffaaaaaa));
        content.addView(text("Массаж, подогрев и вентиляция: без указания места команда относится к водителю. Для переднего пассажира добавьте «пассажира», например «массаж пассажира волны» или «подогрев сиденья пассажира три». Уровни — от 1 до 3. Выбор уровня или типа массажа может одновременно включить функцию. Доступность зависит от комплектации и прошивки автомобиля.", 20, 0xffaaaaaa));
        content.addView(text("Окна: «открой» или «опусти» — открыть, «закрой» или «подними» — закрыть. Можно указать водителя, переднего пассажира, заднее левое/правое окно или группу: передние, задние, левые, правые. «Открой окна» относится ко всем четырём; для одного окна укажите место. «Проветривание» приоткрывает все четыре окна, «проветривание люка» — только люк. Величину открытия задаёт автомобиль. Шторка управляется отдельно: «открой шторку» / «закрой шторку». После отправки помощник не проверяет фактическое положение.", 20, 0xffaaaaaa));
        deepFilterStrength = new LinearLayout(activity);
        deepFilterStrength.setOrientation(LinearLayout.VERTICAL);
        deepFilterStrength.setBackgroundResource(R.drawable.layout_category_bg);
        deepFilterStrength.setPadding(dp(16), dp(10), dp(16), dp(10));
        content.addView(deepFilterStrength, new LinearLayout.LayoutParams(-1, -2));
        deepFilterStrengthLabel = text("", 26, 0xffffffff);
        deepFilterStrength.addView(deepFilterStrengthLabel);
        deepFilterStrengthSlider = new androidx.appcompat.widget.AppCompatSeekBar(activity);
        deepFilterStrengthSlider.setMax(VoiceAudioConfig.MAX_DEEP_FILTER_DB);
        deepFilterStrengthSlider.setContentDescription("Сила подавления шума");
        deepFilterStrength.addView(deepFilterStrengthSlider, new LinearLayout.LayoutParams(-1, dp(48)));
        deepFilterStrength.addView(text("0 дБ — без обработки · 6 дБ — мягче · 30 дБ — сильнее.\nМеньше значение — больше исходного звука и меньше искажений голоса. Настройка действует со следующей записи.", 20, 0xffaaaaaa));
        deepFilterStrengthSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                deepFilterStrengthLabel.setText("Сила подавления шума: до " + value + " дБ");
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
        tryVoice.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26); tryVoice.setTextColor(0xffffffff);
        tryVoice.setBackgroundTintList(ColorStateList.valueOf(0xff373f4a));
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
    }

    void refresh() {
        updating = true;
        enabled.setChecked(prefs.getBoolean(VoiceCommands.ENABLED, false));
        shortcut.setChecked(prefs.getBoolean("showVoiceCommand", false));
        updateStrength();
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
        MaterialButton header = new MaterialButton(activity);
        header.setAllCaps(false);
        header.setCornerRadius(dp(12));
        header.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
        header.setTextColor(0xffffffff);
        header.setBackgroundTintList(ColorStateList.valueOf(0xff373f4a));
        header.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        header.setPadding(dp(16), dp(10), dp(16), dp(10));
        header.setMinHeight(dp(64));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2);
        headerParams.topMargin = dp(12);
        commands.addView(header, headerParams);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        commands.addView(body, new LinearLayout.LayoutParams(-1, -2));
        String key = "voiceCommandGroupExpanded_" + group.name();
        Runnable update = () -> {
            boolean expanded = prefs.getBoolean(key, false);
            header.setText((expanded ? "▾  " : "▸  ") + group.title + " (" + members.size() + ")");
            header.setContentDescription(group.title + ", " + (expanded ? "свернуть группу" : "раскрыть группу"));
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            // Build rows on first expansion; collapsed app lists can contain hundreds of phrases.
            if (expanded && body.getChildCount() == 0) {
                row(body, "Команда", "Фразы", true);
                for (Map.Entry<String, VoiceCommandCatalog.Command> entry : members.entrySet()) {
                    VoiceCommandCatalog.Command command = entry.getValue();
                    boolean fuel = entry.getKey().equals(VoiceFuelCommand.PREFIX);
                    row(body, fuel ? "Топливо: поддержание заряда (SREV)"
                                    : command.title + (command.confirm ? "\nС подтверждением" : ""),
                            (fuel ? "Топливо <число>: словами или цифрами. Любое число округляется до ближайших 5% в пределах 25–80%. Например: топливо семьдесят три → 75%.\n" : "")
                                    + String.join("; ", phrases.get(entry.getKey())), false);
                }
            }
        };
        header.setOnClickListener(v -> {
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, false)).apply();
            update.run();
        });
        update.run();
    }

    private void updateStrength() {
        VoiceAudioConfig selected = VoiceAudioConfig.read(prefs);
        deepFilterStrengthSlider.setProgress(selected.deepFilterDb);
        deepFilterStrengthLabel.setText("Сила подавления шума: до " + selected.deepFilterDb + " дБ");
    }

    private Switch setting(LinearLayout content, String label) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.layout_category_bg);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView title = text(label, 26, 0xffffffff);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(activity); toggle.setContentDescription(label);
        toggle.setThumbResource(R.drawable.switch_thumb); toggle.setTrackResource(R.drawable.switch_track);
        row.addView(toggle);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10); content.addView(row, params);
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        return toggle;
    }

    private void row(LinearLayout parent, String action, String phrases, boolean heading) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.TOP);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView title = text(action, heading ? 24 : 22, 0xffffffff);
        TextView variants = text(phrases, heading ? 24 : 20, heading ? 0xffffffff : 0xffcccccc);
        title.setPadding(0, dp(4), dp(24), dp(4));
        variants.setPadding(dp(24), dp(4), 0, dp(4));
        if (heading) { title.setTypeface(null, Typeface.BOLD); variants.setTypeface(null, Typeface.BOLD); }
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(variants, new LinearLayout.LayoutParams(0, -2, 3));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(activity); divider.setBackgroundColor(0xff373f4a);
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
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
