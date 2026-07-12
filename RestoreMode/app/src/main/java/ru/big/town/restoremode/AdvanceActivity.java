package ru.big.town.restoremode;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.text.TextWatcher;
import android.widget.NumberPicker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class AdvanceActivity extends AppCompatActivity {
    private EditText canCommandsEditor;
    private ImageButton buttonBack;
    private NumberPicker pickerCustomCommandCount;

    // Кнопки удаления примеров (tag = нормализованный hex команды)
    private final List<ImageButton> deleteButtons = new ArrayList<>();

    // Навигация (разделы)
    private TextView navCustomCommands, navComfort, navOther;
    private View pageCustomCommands, pageComfort, pageOther;

    // Примеры команд: {команда, описание}
    private static final String[][] EXAMPLE_COMMANDS = {
            {"64 08 80 00 00 00 00 00 00 03", "обогрев руля вкл"},
            {"64 08 40 00 00 00 00 00 00 03", "обогрев руля выкл"},
            {"65 08 00 00 c1 c0 20 00 00 00", "обогрев заднего стекла вкл"},
            {"65 08 00 00 c1 c0 10 00 00 00", "обогрев заднего стекла выкл"},
            {"7a 08 00 00 00 00 01 00 00 00", "автодальний вкл"},
            {"7a 08 00 00 00 00 02 00 00 00", "автодальний выкл"},
            {"68 08 02 00 00 f0 2c 54 08 00", "форсе EV вкл"},
            {"68 08 02 00 00 f0 2c 24 08 00", "форсе EV выкл"},
    };

    public void onButtonClickFinish(View v){
        Intent intent = new Intent();
        intent.putExtra("customCommand", canCommandsEditor.getText().toString());
        intent.putExtra("customCommandCount", pickerCustomCommandCount.getValue());
        setResult(RESULT_OK, intent);
        finish();
    }

    public void onButtonClickClean(View v){
        canCommandsEditor.setText("");
    }

    /** Строит список кнопок примеров команд + кнопку удаления в каждой строке. */
    private void buildExampleButtons() {
        LinearLayout container = findViewById(R.id.examplesContainer);
        if (container == null) return;
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String[] pair : EXAMPLE_COMMANDS) {
            final String hex = pair[0];
            final String label = pair[1];
            View row = inflater.inflate(R.layout.item_command, container, false);
            Button btn = row.findViewById(R.id.cmdButton);
            ImageButton del = row.findViewById(R.id.cmdDelete);
            btn.setText(label);
            btn.setOnClickListener(v -> insertCommand(hex));
            del.setTag(hex.replaceAll("[^0-9a-fA-F]", "").toLowerCase());
            del.setOnClickListener(v -> removeCommand(hex));
            deleteButtons.add(del);
            container.addView(row);
        }
        updateDeleteButtons();
    }

    /** Кнопка удаления активна только если её команда есть в текстовом поле. */
    private void updateDeleteButtons() {
        if (canCommandsEditor == null) return;
        Set<String> present = new HashSet<>();
        for (String line : canCommandsEditor.getText().toString().split("\n")) {
            String norm = line.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
            if (!norm.isEmpty()) present.add(norm);
        }
        for (ImageButton del : deleteButtons) {
            String target = (String) del.getTag();
            boolean enabled = target != null && present.contains(target);
            del.setEnabled(enabled);
            del.setAlpha(enabled ? 1f : 0.3f);
        }
    }

    /** Добавляет команду в текстовое поле (TextWatcher сам отформатирует). */
    private void insertCommand(String hex) {
        String cur = canCommandsEditor.getText().toString();
        if (cur.length() > 0 && !cur.endsWith("\n")) cur = cur + "\n";
        canCommandsEditor.setText(cur + hex + "\n");
        canCommandsEditor.setSelection(canCommandsEditor.getText().length());
    }

    /** Удаляет первую совпадающую команду из текстового поля. */
    private void removeCommand(String hex) {
        String target = hex.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
        String[] lines = canCommandsEditor.getText().toString().split("\n");
        StringBuilder sb = new StringBuilder();
        boolean removed = false;
        for (String line : lines) {
            String norm = line.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
            if (norm.isEmpty()) continue;
            if (!removed && norm.equals(target)) { removed = true; continue; }
            sb.append(norm).append("\n");
        }
        canCommandsEditor.setText(sb.toString());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_advance);

        canCommandsEditor   = findViewById(R.id.rawCanCodes);
        buttonBack          = findViewById(R.id.buttonBack);
        pickerCustomCommandCount = findViewById(R.id.pickerCustomCommandCount);
        pickerCustomCommandCount.setMaxValue(10);
        pickerCustomCommandCount.setMinValue(1);
        pickerCustomCommandCount.setTextColor(0xffffffff);
        pickerCustomCommandCount.setTextSize(40f);

        Intent intent = getIntent();
        if (intent != null) {
            String customCommand    = intent.getStringExtra("customCommand");
            int customCommandCount  = intent.getIntExtra("customCommandCount", 1);

            canCommandsEditor.setText(customCommand);
            pickerCustomCommandCount.setValue(customCommandCount);

            Log.i("$$$ Advance Create $$$$", String.format(
                    "%s %d", customCommand, customCommandCount));
        }
        TextView textWarn = findViewById(R.id.TextWarn);
        textWarn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                View focused = getCurrentFocus();
                if (imm != null && focused != null) {
                    imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                    focused.clearFocus();
                }
            }
        });

        canCommandsEditor.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    Log.i("$$$ setOnFocusChangeListener $$$$", "FOCUS ON");
                } else {
                    Log.i("$$$ setOnFocusChangeListener $$$$", "FOCUS OFF");

                }
            }
        });


        canCommandsEditor.addTextChangedListener(new TextWatcher() {
            private boolean isFormatting = false;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                Log.i("$$$ beforeTextChanged $$$", s.toString()+String.format("int start, int count, int after: %d, %d %d ", start,count,after));
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                Log.i("$$$ onTextChanged $$$", s.toString()+String.format("int start, int before, int count: %d, %d %d ", start,before,count));
                if (isFormatting) return;
                isFormatting = true;

                String input = s.toString().toLowerCase();
                String filtered = input.replaceAll("[^0-9a-f,\n]", "");
                String[] q;
                q=filtered.split("\n");
                StringBuilder formatted = new StringBuilder();
                for(String i: q){
                    Log.i("LENGTH i",String.format("%s %d",i,i.length()));
                    for(int j=0; j<i.length(); j++){
                        if(j % 2 == 0){
                            formatted.append(" ");
                        }
                        formatted.append(i.charAt(j));
                        if(j >= 19){
                           formatted.append("\n");
                        }
                    }
                }
                Log.i("$$$ LENGTH formatted.length $$$ ",String.format("%d",formatted.length()));

                if(formatted.length() % 31 == 0){
                    canCommandsEditor.setBackgroundColor(Color.WHITE);
                    buttonBack.setEnabled(true);
                    buttonBack.setAlpha(1f);
                } else {
                    canCommandsEditor.setBackgroundColor(0xffffafaf);
                    buttonBack.setEnabled(false);
                    buttonBack.setAlpha(0.4f);
                }

                canCommandsEditor.removeTextChangedListener(this);
                    canCommandsEditor.setText(formatted.toString());
                    canCommandsEditor.setSelection(formatted.length());
                    canCommandsEditor.addTextChangedListener(this);
                    isFormatting = false;
            }

            @Override
            public void afterTextChanged(Editable s) {
                Log.i("$$$ afterTextChanged $$$", s.toString());
                updateDeleteButtons();
            }
        });

        buildExampleButtons();

        // Навигация между разделами
        navCustomCommands = findViewById(R.id.navCustomCommands);
        navComfort        = findViewById(R.id.navComfort);
        navOther          = findViewById(R.id.navOther);
        pageCustomCommands = findViewById(R.id.pageCustomCommands);
        pageComfort        = findViewById(R.id.pageComfort);
        pageOther          = findViewById(R.id.pageOther);
        navCustomCommands.setOnClickListener(v -> setSection(0));
        navComfort.setOnClickListener(v -> setSection(1));
        navOther.setOnClickListener(v -> setSection(2));
        setSection(0);

        SharedPreferences prefs = getSharedPreferences("DrivePreferences", MODE_PRIVATE);

        // Раздел «Комфорт»: тоггл «Сервисный режим дворников в холодную погоду».
        // Применяется на «Применить»/пробуждении/старте (Native читает колонку 13 провайдера).
        Switch switchWiperCold = findViewById(R.id.switchWiperCold);
        switchWiperCold.setChecked(prefs.getBoolean("wiperColdMode", false));
        switchWiperCold.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("wiperColdMode", checked).apply());

        // Раздел «Другое»: тоггл «Режим отладки» (состояние сохраняется, поведение — позже)
        Switch switchDebugMode = findViewById(R.id.switchDebugMode);
        switchDebugMode.setChecked(prefs.getBoolean("debugMode", false));
        switchDebugMode.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("debugMode", checked).apply());
    }

    /** Переключение разделов навигации. */
    private void setSection(int index) {
        if (pageCustomCommands != null) pageCustomCommands.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        if (pageComfort != null)        pageComfort.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (pageOther != null)          pageOther.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (navCustomCommands != null)  navCustomCommands.setSelected(index == 0);
        if (navComfort != null)         navComfort.setSelected(index == 1);
        if (navOther != null)           navOther.setSelected(index == 2);
    }
}