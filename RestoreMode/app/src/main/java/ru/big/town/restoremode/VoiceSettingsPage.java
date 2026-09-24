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
    private final AppCompatActivity activity;
    private final SharedPreferences prefs;
    private final Runnable changed;
    private final Switch enabled, shortcut;
    private final Button tryVoice;
    private final LinearLayout commands;
    private boolean updating;

    VoiceSettingsPage(AppCompatActivity activity, LinearLayout content,
                      SharedPreferences prefs, Runnable changed) {
        this.activity = activity; this.prefs = prefs; this.changed = changed;
        enabled = setting(content, "Включить голосового помощника");
        content.addView(text("Вызов голосового помощника кнопкой на руле доступен только в Full. В Light помощник вызывается только из VoyahTune: кнопкой «Попробовать голосовую команду» или ярлыком «Голосовая команда» на главном экране."
                + (BuildConfig.IS_FULL
                ? " Удерживайте кнопку голосового помощника на руле. Повторное удержание начинает новую сессию. Прежнее назначение долгого нажатия сохранится и вернётся после отключения помощника."
                : ""), 20, 0xffaaaaaa));
        content.addView(text("Распознавание работает без интернета. Произносите одну команду за раз. Не обязательно произносить фразу целиком: достаточно ключевых слов, например «спорт» или «фары авто». Слова «выключи» и «переключи» определяют действие — их пропускать нельзя. Можно менять порядок слов и добавлять «пожалуйста». Неизвестная или неоднозначная фраза не выполняется.", 20, 0xffaaaaaa));
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
        updating = false;
        tryVoice.setEnabled(enabled.isChecked());
        tryVoice.setAlpha(enabled.isChecked() ? 1 : .45f);
        commands.removeAllViews();
        row("Команда", "Фразы", true);
        Map<String, VoiceCommandCatalog.Command> actions = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> phrases = new LinkedHashMap<>();
        for (VoiceCommandCatalog.Command command : VoiceCommands.load(activity)) {
            String key = command.action.startsWith(VoiceFuelCommand.PREFIX) ? VoiceFuelCommand.PREFIX : command.action;
            actions.putIfAbsent(key, command);
            phrases.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(command.phrases);
        }
        for (Map.Entry<String, VoiceCommandCatalog.Command> entry : actions.entrySet()) {
            VoiceCommandCatalog.Command command = entry.getValue();
            boolean fuel = entry.getKey().equals(VoiceFuelCommand.PREFIX);
            row(fuel ? "Топливо: поддержание заряда (SREV)"
                            : command.title + (command.confirm ? "\nС подтверждением" : ""),
                    (fuel ? "Топливо <число>: словами или цифрами. Любое число округляется до ближайших 5% в пределах 25–80%. Например: топливо семьдесят три → 75%.\n" : "")
                            + String.join("; ", phrases.get(entry.getKey())), false);
        }
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

    private void row(String action, String phrases, boolean heading) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.TOP);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView title = text(action, heading ? 24 : 22, 0xffffffff);
        TextView variants = text(phrases, heading ? 24 : 20, heading ? 0xffffffff : 0xffcccccc);
        title.setPadding(0, dp(4), dp(24), dp(4));
        variants.setPadding(dp(24), dp(4), 0, dp(4));
        if (heading) { title.setTypeface(null, Typeface.BOLD); variants.setTypeface(null, Typeface.BOLD); }
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(variants, new LinearLayout.LayoutParams(0, -2, 3));
        commands.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(activity); divider.setBackgroundColor(0xff373f4a);
        commands.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
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
        updating = true; enabled.setChecked(value); updating = false;
        tryVoice.setEnabled(value); tryVoice.setAlpha(value ? 1 : .45f);
        SplitConfigSync.pushSteering(activity, prefs); changed.run();
    }
    void onPermissionResult(int request, int[] grants) {
        if (request != MICROPHONE_REQUEST) return;
        boolean allowed = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        saveEnabled(allowed);
        if (!allowed) Toast.makeText(activity, "Для голосового управления разрешите микрофон в настройках Android",
                Toast.LENGTH_LONG).show();
    }
}
