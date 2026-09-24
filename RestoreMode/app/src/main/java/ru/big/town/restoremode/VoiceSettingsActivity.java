package ru.big.town.restoremode;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class VoiceSettingsActivity extends AppCompatActivity {
    private SharedPreferences prefs;
    private Switch enabled;
    private Button tryVoice;
    private boolean updating;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("DrivePreferences", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32, 16, 32, 16);
        root.setBackgroundColor(0xff22252f);
        Button back = new Button(this); back.setText("‹  Голосовое управление");
        back.setOnClickListener(v -> finish()); root.addView(back);
        enabled = new Switch(this); enabled.setText("Включить голосового помощника");
        enabled.setTextColor(0xffffffff); enabled.setTextSize(23); enabled.setPadding(16, 20, 16, 20);
        enabled.setChecked(prefs.getBoolean(VoiceCommands.ENABLED, false)); root.addView(enabled);
        TextView hint = text(BuildConfig.IS_FULL
                ? "Удерживайте кнопку голосового помощника на руле. Повторное удержание начинает новую сессию. Прежнее назначение долгого нажатия сохранится и вернётся после отключения помощника."
                : "В Light запуск доступен кнопкой на этом экране. Для кнопки руля нужна Full-версия.", 18);
        root.addView(hint);
        root.addView(text("Распознавание работает без интернета. Произносите одну команду за раз. Можно менять порядок ключевых слов и добавлять «пожалуйста». Неизвестная или неоднозначная фраза не выполняется.", 18));
        tryVoice = new Button(this); tryVoice.setText("Попробовать голосовую команду");
        tryVoice.setEnabled(enabled.isChecked());
        tryVoice.setOnClickListener(v -> startActivity(new Intent(this, VoiceActivity.class)));
        root.addView(tryVoice);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            if (checked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                setChecked(false);
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 41);
            } else save(checked);
        });
        Switch shortcut = new Switch(this);
        shortcut.setText("Ярлык «Голосовая команда» на главном экране");
        shortcut.setTextColor(0xffffffff); shortcut.setTextSize(20); shortcut.setPadding(16, 12, 16, 12);
        shortcut.setChecked(prefs.getBoolean("showVoiceCommand", false));
        shortcut.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("showVoiceCommand", checked).apply());
        root.addView(shortcut);
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        row(list, "Фразы", "Действие", true);
        for (VoiceCommandCatalog.Command command : VoiceCommands.load(this)) {
            row(list, String.join("\n", command.phrases), command.title + (command.confirm ? "\nС подтверждением" : ""), false);
        }
        scroll.addView(list); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void row(LinearLayout list, String phrases, String action, boolean heading) {
        LinearLayout row = new LinearLayout(this); row.setPadding(16, 16, 16, 16);
        row.setBackgroundColor(heading ? 0xff373f4a : (list.getChildCount() % 2 == 0 ? 0xff292e39 : 0xff22252f));
        row.addView(text(phrases, heading ? 22 : 18), new LinearLayout.LayoutParams(0, -2, 2));
        row.addView(text(action, heading ? 22 : 18), new LinearLayout.LayoutParams(0, -2, 1));
        list.addView(row);
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextColor(0xffeeeeff);
        view.setTextSize(size); view.setPadding(8, 8, 16, 8); return view;
    }
    private void setChecked(boolean value) { updating = true; enabled.setChecked(value); updating = false; }
    private void save(boolean value) {
        prefs.edit().putBoolean(VoiceCommands.ENABLED, value).apply();
        setChecked(value); tryVoice.setEnabled(value);
        SplitConfigSync.pushSteering(this, prefs);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 41) {
            boolean allowed = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
            save(allowed);
            if (!allowed) android.widget.Toast.makeText(this,
                    "Для голосового управления разрешите микрофон в настройках Android", android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
