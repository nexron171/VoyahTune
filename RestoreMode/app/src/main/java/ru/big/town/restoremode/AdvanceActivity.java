package ru.big.town.restoremode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.Editable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

    // Навигация (разделы): 0 режимы+безопасность, 1 комфорт, 2 команды, 3 другое
    private TextView navCustomCommands, navDriveModes, navComfort, navOther;
    private View pageCustomCommands, pageDriveModes, pageComfort, pageOther;
    // Заголовок раздела в верхней панели (на одной строке с «Применить»)
    private TextView sectionTitle;
    private static final String[] SECTION_TITLES = {
            "Режимы вождения и безопасность", "Комфорт", "Собственные команды", "Другое"
    };

    // DrivePreferences — единый источник настроек
    private SharedPreferences prefs;

    // Автосвет (перенесён в «Комфорт»)
    private RadioGroup autoLightGroup;
    private TextView textSensorLevel;
    private NumberPicker pickerSensorThreshold, pickerThresholdOff;
    private CheckBox checkBox34;

    // Сообщения в SetModesService (через GlobalVars.serviceMessenger, забинденный MainActivity)
    static final int MSG_AUTO_LIGHT_ENABLE  = 10;
    static final int MSG_AUTO_LIGHT_DISABLE = 11;
    static final int MSG_APPLY_DRIVE_MODES  = 1;
    static final int MSG_RESULT             = 4;

    // Кнопка «Применить» (верхняя панель) — блокировка + прогресс на время цикла отправки
    private Button buttonApplyAdvance;
    private ProgressBar applyProgressAdvance;
    private boolean applying = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable applyTimeout = () -> setApplying(false);
    // Свой клиент для приёма MSG_RESULT (реплай сервиса о завершении цикла)
    private final Messenger applyClient = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_RESULT) setApplying(false);
            else super.handleMessage(msg);
        }
    });

    // Приём уровня датчика освещённости из Native (для показания «Датчик: N»)
    private final BroadcastReceiver luxReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int sensorLevel = intent.getIntExtra("sensorLevel", -1);
            if (textSensorLevel != null) {
                textSensorLevel.setText(sensorLevel >= 0 ? "Датчик: " + sensorLevel : "Датчик: —");
            }
        }
    };

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

        prefs = getSharedPreferences("DrivePreferences", MODE_PRIVATE);

        buttonApplyAdvance   = findViewById(R.id.buttonApplyAdvance);
        applyProgressAdvance = findViewById(R.id.applyProgressAdvance);
        sectionTitle         = findViewById(R.id.sectionTitle);

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
        navDriveModes     = findViewById(R.id.navDriveModes);
        navComfort        = findViewById(R.id.navComfort);
        navOther          = findViewById(R.id.navOther);
        pageCustomCommands = findViewById(R.id.pageCustomCommands);
        pageDriveModes     = findViewById(R.id.pageDriveModes);
        pageComfort        = findViewById(R.id.pageComfort);
        pageOther          = findViewById(R.id.pageOther);
        navDriveModes.setOnClickListener(v -> setSection(0));
        navComfort.setOnClickListener(v -> setSection(1));
        navCustomCommands.setOnClickListener(v -> setSection(2));
        navOther.setOnClickListener(v -> setSection(3));
        setSection(0);

        // Раздел «Режимы вождения и безопасность» (перенесено с главного экрана)
        initModeRadios();
        initModeEnableToggles();
        initCheckBox34();
        initPedestrianSoundGroup();

        // Раздел «Комфорт»: автосвет + сервисный режим дворников
        initAutoLight();
        initSensorPickers();

        Switch switchWiperCold = findViewById(R.id.switchWiperCold);
        switchWiperCold.setChecked(prefs.getBoolean("wiperColdMode", false));
        switchWiperCold.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("wiperColdMode", checked).apply());

        // Раздел «Другое»: тоггл «Режим отладки»
        Switch switchDebugMode = findViewById(R.id.switchDebugMode);
        switchDebugMode.setChecked(prefs.getBoolean("debugMode", false));
        switchDebugMode.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("debugMode", checked).apply());
    }

    /**
     * Кнопка «Применить» на экране «Дополнительно» — как на главном: шлём
     * MSG_APPLY_DRIVE_MODES в SetModesService (через мессенджер, забинденный MainActivity),
     * реплай MSG_RESULT приходит на наш applyClient и разблокирует кнопку.
     */
    public void onButtonClickApply(View v) {
        if (applying) return;
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance apply $$$", "SetModesService не забинден");
            return;
        }
        try {
            Message msg = Message.obtain(null, MSG_APPLY_DRIVE_MODES);
            msg.replyTo = applyClient;
            GlobalVars.serviceMessenger.send(msg);
            setApplying(true);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void setApplying(boolean on) {
        applying = on;
        if (buttonApplyAdvance != null) buttonApplyAdvance.setEnabled(!on);
        if (applyProgressAdvance != null) applyProgressAdvance.setVisibility(on ? View.VISIBLE : View.GONE);
        uiHandler.removeCallbacks(applyTimeout);
        if (on) uiHandler.postDelayed(applyTimeout, 12000); // страховка, если MSG_RESULT не придёт
    }

    /** Переключение разделов навигации (0 режимы+безопасность, 1 комфорт, 2 команды, 3 другое). */
    private void setSection(int index) {
        if (sectionTitle != null && index >= 0 && index < SECTION_TITLES.length)
            sectionTitle.setText(SECTION_TITLES[index]);
        if (pageDriveModes != null)     pageDriveModes.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        if (pageComfort != null)        pageComfort.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (pageCustomCommands != null) pageCustomCommands.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (pageOther != null)          pageOther.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        if (navDriveModes != null)      navDriveModes.setSelected(index == 0);
        if (navComfort != null)         navComfort.setSelected(index == 1);
        if (navCustomCommands != null)  navCustomCommands.setSelected(index == 2);
        if (navOther != null)           navOther.setSelected(index == 3);
    }

    /** Сегмент-контрол «Предупреждение пешеходов» (Со звуком/Без звука). Перенесён с главного. */
    private void initPedestrianSoundGroup() {
        RadioGroup group = findViewById(R.id.pedestrianSoundGroup);
        if (group == null) return;
        boolean disabled = prefs.getBoolean("disablePedestrianSound", false);
        group.check(disabled ? R.id.pedestrianSoundOn : R.id.pedestrianSoundOff);
        group.setOnCheckedChangeListener((g, checkedId) -> {
            boolean off = (checkedId == R.id.pedestrianSoundOn);
            prefs.edit().putBoolean("disablePedestrianSound", off).apply();
            Log.i("$$$ Advance pedestrian $$$", off ? "DISABLED (muted)" : "ENABLED");
        });
    }

    // -------------------------------------------------------------------------
    // Режимы вождения / энергии / рекуперации (перенос с главного экрана)
    // -------------------------------------------------------------------------

    private void initModeRadios() {
        RadioGroup drive   = findViewById(R.id.drive_modes_group);
        RadioGroup energy  = findViewById(R.id.energy_modes_group);
        RadioGroup recycle = findViewById(R.id.recycle_modes_group);
        checkRadioByTag(drive,   prefs.getString("driveMode", "INDIVIDUAL"));
        checkRadioByTag(energy,  prefs.getString("energy",    "SREV"));
        checkRadioByTag(recycle, prefs.getString("recycle",   "LOW"));
        if (drive != null)   drive.setOnCheckedChangeListener((g, id) -> saveRadio("driveMode", id));
        if (energy != null)  energy.setOnCheckedChangeListener((g, id) -> saveRadio("energy", id));
        if (recycle != null) recycle.setOnCheckedChangeListener((g, id) -> saveRadio("recycle", id));
    }

    private void checkRadioByTag(RadioGroup group, String value) {
        if (group == null || value == null) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof RadioButton && value.equals(child.getTag())) {
                ((RadioButton) child).setChecked(true);
                return;
            }
        }
    }

    private void saveRadio(String key, int checkedId) {
        View v = findViewById(checkedId);
        if (v != null && v.getTag() != null) {
            prefs.edit().putString(key, v.getTag().toString()).apply();
            Log.i("$$$ Advance mode $$$", key + "=" + v.getTag());
        }
    }

    private void initModeEnableToggles() {
        setupEnableSwitch(R.id.switchDriveMode,  R.id.drive_modes_group,   "driveEnabled");
        setupEnableSwitch(R.id.switchEnergy,     R.id.energy_modes_group,  "energyEnabled");
        setupEnableSwitch(R.id.switchRecycle,    R.id.recycle_modes_group, "recycleEnabled");
    }

    private void setupEnableSwitch(int switchId, int groupId, String key) {
        Switch sw = findViewById(switchId);
        if (sw == null) return;
        boolean enabled = prefs.getBoolean(key, false);
        sw.setChecked(enabled);
        applyModeToggle(groupId, enabled);
        sw.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            applyModeToggle(groupId, checked);
        });
    }

    /** Делает RadioGroup кликабельным/некликабельным и меняет прозрачность. */
    private void applyModeToggle(int groupId, boolean enabled) {
        RadioGroup group = findViewById(groupId);
        if (group == null) return;
        group.setAlpha(enabled ? 1.0f : 0.4f);
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setEnabled(enabled);
            group.getChildAt(i).setClickable(enabled);
        }
    }

    private void initCheckBox34() {
        checkBox34 = findViewById(R.id.checkBox34);
        if (checkBox34 == null) return;
        checkBox34.setChecked(prefs.getBoolean("checkBox34", false));
        applyCheckBox34();
    }

    /** Вызывается из XML (android:onClick) на чекбоксе «3/4 кнопки». */
    public void onCheckBox34Click(View v) {
        applyCheckBox34();
    }

    private void applyCheckBox34() {
        if (checkBox34 == null) return;
        RadioButton smart = findViewById(R.id.SMART);
        if (checkBox34.isChecked()) {
            if (smart != null) smart.setVisibility(View.GONE);
            checkBox34.setText("4 кнопки");
            prefs.edit().putBoolean("checkBox34", true).apply();
        } else {
            if (smart != null) smart.setVisibility(View.VISIBLE);
            checkBox34.setText("3 кнопки");
            prefs.edit().putBoolean("checkBox34", false).apply();
        }
    }

    // -------------------------------------------------------------------------
    // Автосвет (перенос в «Комфорт»)
    // -------------------------------------------------------------------------

    private void initAutoLight() {
        autoLightGroup  = findViewById(R.id.autoLightGroup);
        textSensorLevel = findViewById(R.id.textSensorLevel);
        if (autoLightGroup == null) return;

        boolean on = prefs.getBoolean("autoLight", false);
        autoLightGroup.check(on ? R.id.autoLightOn : R.id.autoLightOff);
        autoLightGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean enabled = (checkedId == R.id.autoLightOn);
            prefs.edit().putBoolean("autoLight", enabled).apply();
            sendAutoLightMessage(enabled);
            if (!enabled && textSensorLevel != null) textSensorLevel.setText("Датчик: —");
            Log.i("$$$ Advance autolight $$$", enabled ? "ON" : "OFF");
        });
    }

    /** Немедленный старт/стоп LightSensorService через мессенджер, забинденный MainActivity. */
    private void sendAutoLightMessage(boolean enable) {
        int what = enable ? MSG_AUTO_LIGHT_ENABLE : MSG_AUTO_LIGHT_DISABLE;
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance autolight $$$", "SetModesService не забинден — состояние применится позже");
            return;
        }
        try {
            GlobalVars.serviceMessenger.send(Message.obtain(null, what));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void initSensorPickers() {
        pickerSensorThreshold = findViewById(R.id.pickerSensorThreshold);
        pickerThresholdOff    = findViewById(R.id.pickerThresholdOff);
        if (pickerSensorThreshold == null) return;

        // Порог включения (нижний): 1..6 (макс 6, т.к. выключение должно быть строго больше)
        pickerSensorThreshold.setMinValue(1);
        pickerSensorThreshold.setMaxValue(6);
        pickerSensorThreshold.setTextColor(0xffffffff);
        int savedThreshold = Math.min(Math.max(prefs.getInt("lightSensorThreshold", 3), 1), 6);
        pickerSensorThreshold.setValue(savedThreshold);

        // Порог выключения (верхний): строго > порога включения, максимум 7
        if (pickerThresholdOff != null) {
            pickerThresholdOff.setMaxValue(7);
            pickerThresholdOff.setMinValue(savedThreshold + 1);
            pickerThresholdOff.setTextColor(0xffffffff);
            int savedThresholdOff = Math.max(
                    prefs.getInt("lightSensorThresholdOff", 5), savedThreshold + 1);
            pickerThresholdOff.setValue(savedThresholdOff);
            pickerThresholdOff.setOnValueChangedListener((picker, oldVal, newVal) ->
                    prefs.edit().putInt("lightSensorThresholdOff", newVal).apply());
        }

        // Изменение порога включения двигает нижнюю границу выключения (Выкл > Вкл)
        pickerSensorThreshold.setOnValueChangedListener((picker, oldVal, newVal) -> {
            prefs.edit().putInt("lightSensorThreshold", newVal).apply();
            if (pickerThresholdOff != null) {
                pickerThresholdOff.setMinValue(newVal + 1);
                prefs.edit().putInt("lightSensorThresholdOff", pickerThresholdOff.getValue()).apply();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter("ru.big.town.anative.LUX_UPDATE");
        registerReceiver(luxReceiver, filter, RECEIVER_EXPORTED);
        Intent req = new Intent("ru.big.town.anative.REQUEST_LUX_UPDATE");
        req.setPackage("ru.big.town.anative");
        sendBroadcast(req);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(luxReceiver);
        } catch (Exception ignored) {
        }
    }
}
