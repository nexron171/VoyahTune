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
import androidx.activity.OnBackPressedCallback;
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

    // Навигация: 0 главный экран, 1 настройки автомобиля (+комфорт), 2 приложения и разделение экрана,
    //            3 Apollo Tech, 4 команды (видимость настраивается), 5 кнопки на руле, 6 другое
    private TextView navMainScreen, navCustomCommands, navDriveModes, navSplitScreen, navApolloTech,
            navSteeringButtons, navOther;
    private View pageMainScreen, pageCustomCommands, pageDriveModes, pageSplitScreen, pageApolloTech,
            pageSteeringButtons, pageOther;
    // Заголовок раздела в верхней панели (на одной строке с «Применить»)
    private TextView sectionTitle;
    // Освободившийся после переноса «Комфорта» индекс 3 занимает Apollo Tech. Индекс 4
    // (Собственные команды) показывается отдельной настройкой.
    private static final String[] SECTION_TITLES = {
            "Главный экран", "Настройки автомобиля", "Приложения и разделение экрана", "Apollo Tech",
            "Собственные команды", "Кнопки на руле", "Другое"
    };
    private static final String PREF_SHOW_CUSTOM_COMMANDS = "showCustomCommands";
    private int currentSection;

    // Кнопки на руле — 4 кнопки-пикера действий (звёздочка/DVR × короткое/долгое). Поля/логика ниже.
    private Button steerStarShortBtn, steerStarLongBtn, steerDvrShortBtn, steerDvrLongBtn, steerVoiceShortBtn, steerVoiceLongBtn, steerPhoneShortBtn, steerPhoneLongBtn;

    // DrivePreferences — единый источник настроек
    private SharedPreferences prefs;

    // Автосвет (перенесён в «Комфорт»)
    private RadioGroup autoLightGroup;
    private TextView textSensorLevel;
    private CheckBox checkBox34;

    // Сообщения в SetModesService (через GlobalVars.serviceMessenger, забинденный MainActivity)
    static final int MSG_AUTO_LIGHT_ENABLE  = 10;
    static final int MSG_AUTO_LIGHT_DISABLE = 11;
    static final int MSG_APPLY_DRIVE_MODES  = 1;
    static final int MSG_RESULT             = 4;
    static final int MSG_REBOOT             = 22;
    static final int MSG_FLOATING_BACK      = 24;
    static final int MSG_FLOATING_BACK_SIDE = 25;
    static final int MSG_GRANT_INSTALL      = 26;
    static final int MSG_CLOSE_ALL          = 27;
    static final int MSG_SET_THEME          = 28;
    static final int MSG_APPLY_FORCED_EV    = 35;
    static final int MSG_APOLLO_QUERY       = 36;
    static final int MSG_APOLLO_SET_TLC     = 37;
    static final int MSG_APOLLO_SET_MASTER  = 38;
    static final int MSG_APOLLO_SET_GLA     = 39;
    static final int MSG_APOLLO_SET_GLA_SOUND = 40;
    static final int MSG_APOLLO_SET_TSR     = 41;

    private static final String ACTION_APOLLO_TLC_UPDATE =
            "ru.big.town.anative.APOLLO_TLC_UPDATE";
    private static final String ACTION_REQUEST_APOLLO_TLC_UPDATE =
            "ru.big.town.anative.REQUEST_APOLLO_TLC_UPDATE";
    private static final String NATIVE_PACKAGE = "ru.big.town.anative";
    private static final String NATIVE_BIND_PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
    private static final int APOLLO_UNKNOWN = Integer.MIN_VALUE;

    // Apollo Tech всегда отображает только подтверждённое Native состояние. Значения не сохраняются
    // в RestoreMode prefs: при каждом открытии раздела выполняется read-only запрос к автомобилю.
    private Switch switchApolloMaster, switchApolloTlc, switchApolloTrafficLights,
            switchApolloTrafficSigns;
    private RadioGroup apolloGreenSoundGroup;
    private Button buttonApolloForceOff;
    private TextView textApolloStatus, textApolloDiagnostics, textApolloFullOnly;
    private View apolloControls;
    private View apolloGreenSoundContainer;
    private boolean syncingApolloUi;
    private boolean apolloHasState;
    private boolean apolloCanConnected;
    private boolean apolloProfileSupported;
    private boolean apolloDirectTlcMode;
    private boolean apolloMasterKnown;
    private boolean apolloMasterEnabled;
    private boolean apolloPending;
    private int apolloPlcSwitch = APOLLO_UNKNOWN;
    private int apolloPlcStatus = APOLLO_UNKNOWN;
    private int apolloAnpSwitch = APOLLO_UNKNOWN;
    private int apolloTlcCapability = APOLLO_UNKNOWN;
    private int apolloPlcCapabilitySa = APOLLO_UNKNOWN;
    private int apolloGear = -1;
    private int apolloGlaSwitch = APOLLO_UNKNOWN;
    private int apolloGlaLightChangeSwitch = APOLLO_UNKNOWN;
    private int apolloTsrSwitch = APOLLO_UNKNOWN;
    private String apolloError = "";

    private final BroadcastReceiver apolloReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_APOLLO_TLC_UPDATE.equals(intent.getAction())) return;
            apolloHasState = true;
            apolloCanConnected = intent.getBooleanExtra("canConnected", false);
            apolloProfileSupported = intent.getBooleanExtra("profileSupported", false);
            apolloDirectTlcMode = intent.getBooleanExtra("directTlcMode", false);
            apolloMasterKnown = intent.getBooleanExtra("masterKnown", false);
            apolloMasterEnabled = intent.getBooleanExtra("masterEnabled", false);
            apolloPending = intent.getBooleanExtra("pending", false);
            apolloPlcSwitch = intent.getIntExtra("plcSwitch", APOLLO_UNKNOWN);
            apolloPlcStatus = intent.getIntExtra("plcStatus", APOLLO_UNKNOWN);
            apolloAnpSwitch = intent.getIntExtra("anpSwitch", APOLLO_UNKNOWN);
            apolloTlcCapability = intent.getIntExtra("tlcCapability", APOLLO_UNKNOWN);
            apolloPlcCapabilitySa = intent.getIntExtra("plcCapabilitySa", APOLLO_UNKNOWN);
            apolloGear = intent.getIntExtra("gear", -1);
            apolloGlaSwitch = intent.getIntExtra("glaSwitch", APOLLO_UNKNOWN);
            apolloGlaLightChangeSwitch = intent.getIntExtra(
                    "glaLightChangeSwitch", APOLLO_UNKNOWN);
            apolloTsrSwitch = intent.getIntExtra("tsrSwitch", APOLLO_UNKNOWN);
            String error = intent.getStringExtra("error");
            apolloError = error == null ? "" : error;
            updateApolloUi();
        }
    };

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

    // Реал-тайм слежение селектора за текущим режимом в машине: Native шлёт MODE_SYNCED при смене режима
    // (штатным меню/кнопкой руля/применением) → двигаем нужный radio, даже если экран настроек открыт.
    private final BroadcastReceiver modeSyncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String mode = intent.getStringExtra("mode");
            if (mode == null || mode.isEmpty()) return;
            String modeKey = intent.getStringExtra("modeKey");
            if (modeKey == null) {
                modeKey = intent.getBooleanExtra("isEnergy", false) ? "energy" : "driveMode";
            }
            int groupId = "energy".equals(modeKey) ? R.id.energy_modes_group
                    : "recycle".equals(modeKey) ? R.id.recycle_modes_group
                    : R.id.drive_modes_group;
            RadioGroup g = findViewById(groupId);
            if (g != null) checkRadioByTag(g, mode);
        }
    };

    // Кнопка руля может переключить бинарные настройки, пока этот экран открыт. Обновляем контролы
    // без повторной отправки CAN-команды из их OnCheckedChangeListener.
    private boolean syncingSettingUi;
    private final BroadcastReceiver settingSyncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String key = intent.getStringExtra("key");
            if (key == null || !intent.hasExtra("value")) return;
            boolean value = intent.getBooleanExtra("value", false);
            prefs.edit().putBoolean(key, value).apply();
            syncingSettingUi = true;
            try {
                if ("forcedEv".equals(key)) {
                    RadioGroup group = findViewById(R.id.forcedEvGroup);
                    if (group != null) group.check(value ? R.id.forcedEvOn : R.id.forcedEvOff);
                } else if ("disablePedestrianSound".equals(key)) {
                    RadioGroup group = findViewById(R.id.pedestrianSoundGroup);
                    if (group != null) group.check(value ? R.id.pedestrianSoundOn : R.id.pedestrianSoundOff);
                }
            } finally {
                syncingSettingUi = false;
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
        finishWithCustomCommands();
    }

    private void finishWithCustomCommands() {
        if (!saveCustomCommands()) return;
        Intent intent = new Intent();
        intent.putExtra("customCommand", canCommandsEditor.getText().toString());
        intent.putExtra("customCommandCount", pickerCustomCommandCount.getValue());
        setResult(RESULT_OK, intent);
        finish();
    }

    /** Сохраняет команды только после успешной проверки формата редактором. */
    private boolean saveCustomCommands() {
        if (buttonBack != null && !buttonBack.isEnabled()) {
            Log.w("$$$ Advance commands $$$", "Команды не сохранены: неверный формат");
            return false;
        }
        prefs.edit()
                .putString("customCommand", canCommandsEditor.getText().toString())
                .putInt("customCommandCount", pickerCustomCommandCount.getValue())
                .apply();
        return true;
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
        applyWindowInsets();

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

        // Системная и плавающая кнопки «Назад» сохраняют данные так же, как кнопка в интерфейсе.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishWithCustomCommands();
            }
        });

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
        navMainScreen     = findViewById(R.id.navMainScreen);
        navCustomCommands = findViewById(R.id.navCustomCommands);
        navDriveModes     = findViewById(R.id.navDriveModes);
        navSplitScreen    = findViewById(R.id.navSplitScreen);
        navApolloTech     = findViewById(R.id.navApolloTech);
        navSteeringButtons = findViewById(R.id.navSteeringButtons);
        navOther          = findViewById(R.id.navOther);
        pageMainScreen     = findViewById(R.id.pageMainScreen);
        pageCustomCommands = findViewById(R.id.pageCustomCommands);
        pageDriveModes     = findViewById(R.id.pageDriveModes);
        pageSplitScreen    = findViewById(R.id.pageSplitScreen);
        pageApolloTech     = findViewById(R.id.pageApolloTech);
        pageSteeringButtons = findViewById(R.id.pageSteeringButtons);
        pageOther          = findViewById(R.id.pageOther);
        navMainScreen.setOnClickListener(v -> setSection(0));
        navDriveModes.setOnClickListener(v -> setSection(1));
        navSplitScreen.setOnClickListener(v -> setSection(2));
        navCustomCommands.setOnClickListener(v -> setSection(4));
        navApolloTech.setOnClickListener(v -> setSection(3));
        navSteeringButtons.setOnClickListener(v -> setSection(5));
        navOther.setOnClickListener(v -> setSection(6));
        initApolloTech();
        setSection(0);

        // «Собственные команды» (4) по умолчанию скрыты. Пункт можно включить в разделе «Другое».
        navCustomCommands.setVisibility(
                prefs.getBoolean(PREF_SHOW_CUSTOM_COMMANDS, false) ? View.VISIBLE : View.GONE);

        // LIGHT: скрываем разделы «Приложения и разделение экрана» (2) и «Кнопки на руле» (5) — split/VD и Frida-руль.
        if (!BuildConfig.IS_FULL) {
            if (navSplitScreen != null)     navSplitScreen.setVisibility(View.GONE);
            if (navSteeringButtons != null) navSteeringButtons.setVisibility(View.GONE);
        }

        // Раздел «Главный экран»: тумблеры видимости карточек (по умолчанию все включены)
        bindShowSwitch(R.id.switchShowTripTimer, "showTripTimer");
        bindShowSwitch(R.id.switchShowPowerHold, "showPowerHold");
        bindShowSwitch(R.id.switchShowWashMode,  "showWashMode");
        bindShowSwitch(R.id.switchShowAutoLight, "showAutoLight");
        bindShowSwitch(R.id.switchShowPedestrian, "showPedestrian");
        bindShowSwitch(R.id.switchShowBatteryHeat, "showBatteryHeat");
        bindShowSwitch(R.id.switchShowForcedEv,   "showForcedEv");

        // Сохранение истории поездок (отдельно от таймера). Выкл → Native удалит журнал.
        Switch switchSaveHistory = findViewById(R.id.switchSaveTripHistory);
        switchSaveHistory.setChecked(prefs.getBoolean("saveTripHistory", true));
        switchSaveHistory.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("saveTripHistory", checked).apply();
            Intent i = new Intent("ru.big.town.anative.TRIP_HISTORY").setPackage("ru.big.town.anative");
            i.putExtra("enabled", checked);
            sendBroadcast(i);
        });

        // Ярлыки приложений на главном — в обоих флейворах (в light открывают приложение обычным
        // способом, в full — на VD). Пресеты сплита и per-app DPI — только в full.
        initAppShortcuts();
        initDockOverride();
        if (BuildConfig.IS_FULL) {
            initSplitScreen();
            initAppDpiList();
        }

        // Раздел «Настройки автомобиля» (режимы + безопасность + комфорт слиты в один раздел)
        initModeRadios();
        initModeEnableToggles();
        initCheckBox34();
        initPedestrianSoundGroup();
        initForcedEvGroup();

        // Автоматический прогрев батареи: при <10°C на улице Native включит прогрев.
        // Флаг читает Native из ContentProvider (колонка 17), broadcast не нужен.
        Switch switchBatteryHeat = findViewById(R.id.switchBatteryHeatAuto);
        if (switchBatteryHeat != null) {
            switchBatteryHeat.setChecked(prefs.getBoolean("batteryHeatAuto", false));
            switchBatteryHeat.setOnCheckedChangeListener((b, checked) ->
                    prefs.edit().putBoolean("batteryHeatAuto", checked).apply());
        }

        // Автосвет + сервисный режим дворников (были в «Комфорт», теперь в «Настройки автомобиля»)
        initAutoLight();

        Switch switchWiperCold = findViewById(R.id.switchWiperCold);
        switchWiperCold.setChecked(prefs.getBoolean("wiperColdMode", false));
        switchWiperCold.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("wiperColdMode", checked).apply());

        // «Пауза музыки при открытии двери водителя»: флаг читает Native из ContentProvider (колонка 18)
        // и старт/стоп сервиса-реактора двери — broadcast не нужен, применяется на ближайшем чтении настроек.
        Switch switchPauseMedia = findViewById(R.id.switchPauseMediaOnDoor);
        if (switchPauseMedia != null) {
            switchPauseMedia.setChecked(prefs.getBoolean("pauseMediaOnDoor", false));
            switchPauseMedia.setOnCheckedChangeListener((b, checked) ->
                    prefs.edit().putBoolean("pauseMediaOnDoor", checked).apply());
        }

        // Раздел «Другое»: тоггл «Режим отладки»
        Switch switchDebugMode = findViewById(R.id.switchDebugMode);
        switchDebugMode.setChecked(prefs.getBoolean("debugMode", false));
        switchDebugMode.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("debugMode", checked).apply());

        // Раздел «Другое»: показывать скрытый по умолчанию раздел «Собственные команды».
        Switch switchShowCustomCommands = findViewById(R.id.switchShowCustomCommands);
        switchShowCustomCommands.setChecked(prefs.getBoolean(PREF_SHOW_CUSTOM_COMMANDS, false));
        switchShowCustomCommands.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(PREF_SHOW_CUSTOM_COMMANDS, checked).apply();
            navCustomCommands.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        // Раздел «Другое»: «Автозапуск VoyahTune» (по умолчанию выключено) —
        // при пробуждении Native откроет RestoreMode. Настройку дублируем в Native (NativePrefs).
        Switch switchAutoLaunch = findViewById(R.id.switchAutoLaunch);
        switchAutoLaunch.setChecked(prefs.getBoolean("autoLaunchOnWake", false));
        switchAutoLaunch.setOnCheckedChangeListener((b, checked) ->
                // Флаг читает Native из ContentProvider (единый источник) — broadcast не нужен.
                prefs.edit().putBoolean("autoLaunchOnWake", checked).apply());

        // Раздел «Другое»: тоггл «Плавающая кнопка Назад» (по умолчанию выключено)
        Switch switchFloatingBack = findViewById(R.id.switchFloatingBack);
        switchFloatingBack.setChecked(prefs.getBoolean("floatingBackButton", false));
        switchFloatingBack.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("floatingBackButton", checked).apply();
            sendFloatingBack(checked);
        });

        // Раздел «Другое»: тема оформления (0 авто, 1 светлая, 2 тёмная) — применяет Native через
        // Settings.Secure.ui_night_mode + UiModeManager. Затрагивает систему и приложения, следующие теме.
        RadioGroup themeGroup = findViewById(R.id.themeOverrideGroup);
        if (themeGroup != null) {
            checkRadioByTag(themeGroup, String.valueOf(prefs.getInt("themeOverride", 0)));
            themeGroup.setOnCheckedChangeListener((g, id) -> {
                View c = findViewById(id);
                if (c != null && c.getTag() != null) {
                    int mode = Integer.parseInt(c.getTag().toString());
                    prefs.edit().putInt("themeOverride", mode).apply();
                    sendTheme(mode);
                }
            });
        }

        // Раздел «Другое»: пароль инженерного меню на сегодня.
        showEngineeringPassword();

        // Положение плавающей кнопки: 0 лево, 1 верх, 2 право
        RadioGroup sideGroup = findViewById(R.id.floatingBackSideGroup);
        if (sideGroup != null) {
            checkRadioByTag(sideGroup, String.valueOf(prefs.getInt("floatingBackSide", 0)));
            sideGroup.setOnCheckedChangeListener((g, id) -> {
                View c = findViewById(id);
                if (c != null && c.getTag() != null) {
                    int side = Integer.parseInt(c.getTag().toString());
                    prefs.edit().putInt("floatingBackSide", side).apply();
                    sendFloatingBackSide(side);
                }
            });
        }

        // Раздел «Кнопки на руле» (Frida-перехват кнопки-звёздочки) — только в full.
        if (BuildConfig.IS_FULL) {
            initSteeringButtons();
        }
    }

    /**
     * Отступы экрана настроек: системные панели из insets + левый родной док головы (~145dp, висит
     * поверх и в insets НЕ приходит — как в главном экране и хосте сплита). Иначе левая навигационная
     * рейка уезжает под родной док.
     */
    private void applyWindowInsets() {
        final float density = getResources().getDisplayMetrics().density;
        final int nativeDock = Math.round(density * 145f);
        View root = findViewById(R.id.main);
        if (root == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int top = sb.top;
            if (top == 0) {
                int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) top = getResources().getDimensionPixelSize(id);
            }
            v.setPadding(nativeDock + sb.left, top, sb.right, sb.bottom);
            return insets;
        });
    }

    /**
     * Кнопка «Применить» на экране «Дополнительно» — как на главном: шлём
     * MSG_APPLY_DRIVE_MODES в SetModesService (через мессенджер, забинденный MainActivity),
     * реплай MSG_RESULT приходит на наш applyClient и разблокирует кнопку.
     */
    public void onButtonClickApply(View v) {
        if (applying) return;
        // ApplyEngine перечитывает команды через ContentProvider, поэтому сохраняем их до сообщения.
        if (!saveCustomCommands()) return;
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
        if (applyProgressAdvance != null) {
            applyProgressAdvance.setVisibility(on && currentSection != 3 ? View.VISIBLE : View.GONE);
        }
        uiHandler.removeCallbacks(applyTimeout);
        if (on) uiHandler.postDelayed(applyTimeout, 12000); // страховка, если MSG_RESULT не придёт
    }

    /** Тумблер видимости карточки на главном экране: пишет флаг в DrivePreferences (MainActivity читает в onResume). */
    private void bindShowSwitch(int switchId, String key) {
        Switch sw = findViewById(switchId);
        if (sw == null) return;
        sw.setChecked(prefs.getBoolean(key, true));
        sw.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
    }

    /** Вкл/выкл плавающую кнопку «Назад» — шлём в SetModesService (тот правит secure settings). */
    private void sendFloatingBack(boolean enable) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance floatBack $$$", "SetModesService не забинден");
            return;
        }
        try {
            GlobalVars.serviceMessenger.send(Message.obtain(null, MSG_FLOATING_BACK, enable ? 1 : 0, 0));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /** Тема оформления (0 авто, 1 светлая, 2 тёмная) → Native применит через secure-настройку + UiModeManager. */
    private void sendTheme(int mode) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance theme $$$", "SetModesService не забинден");
            return;
        }
        try {
            GlobalVars.serviceMessenger.send(Message.obtain(null, MSG_SET_THEME, mode, 0));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * Пароль инженерного меню меняется каждые сутки и считается из даты: год (ГГГГ) и месяц-день
     * (ММДД) складываются ПОСИМВОЛЬНО, БЕЗ переноса разряда, результаты склеиваются подряд.
     * Например для 28.07.2026: 2+0=2, 0+7=7, 2+2=4, 6+8=14 → «27414». Из-за отсутствия переноса
     * длина плавает: 4 знака, если все суммы однозначные, иначе 5-6.
     *
     * Дату берём по ПЕКИНСКОМУ времени: смена пароля происходит в тамошнюю полночь (19:00 МСК).
     */
    static String engineeringPassword(java.util.Calendar beijingNow) {
        String year = String.format(java.util.Locale.US, "%04d", beijingNow.get(java.util.Calendar.YEAR));
        String monthDay = String.format(java.util.Locale.US, "%02d%02d",
                beijingNow.get(java.util.Calendar.MONTH) + 1, beijingNow.get(java.util.Calendar.DAY_OF_MONTH));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append((year.charAt(i) - '0') + (monthDay.charAt(i) - '0'));
        }
        return sb.toString();
    }

    /**
     * Показать пароль на сегодня. Считаем от даты САМОЙ машины — если её часы уехали, пароль всё
     * равно совпадёт с тем, что ждёт голова. Дату, от которой считали, показываем рядом, чтобы
     * было видно, что она правдоподобна.
     */
    private void showEngineeringPassword() {
        TextView pass = findViewById(R.id.textEngPassword);
        TextView date = findViewById(R.id.textEngPasswordDate);
        if (pass == null) return;
        try {
            java.util.Calendar beijing = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            pass.setText(engineeringPassword(beijing));
            if (date != null) {
                // Показываем ИМЕННО пекинскую дату: пароль считается от неё, и после 19:00 МСК она уже
                // «завтрашняя». Подписываем явно, иначе выглядит как ошибка.
                date.setText(String.format(java.util.Locale.US, "дата расчёта: %02d.%02d.%04d по Пекину",
                        beijing.get(java.util.Calendar.DAY_OF_MONTH),
                        beijing.get(java.util.Calendar.MONTH) + 1,
                        beijing.get(java.util.Calendar.YEAR)));
            }
        } catch (Exception e) {
            pass.setText("—");
            if (date != null) date.setText("не удалось определить дату машины");
        }
    }

    /** Сторона плавающей кнопки (0 лево, 1 верх, 2 право). */
    private void sendFloatingBackSide(int side) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance floatBack $$$", "SetModesService не забинден");
            return;
        }
        try {
            GlobalVars.serviceMessenger.send(Message.obtain(null, MSG_FLOATING_BACK_SIDE, side, 0));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /** «Закрыть приложения»: сторонние приложения force-stop в Native (priv-app) → стартуют с нуля. */
    public void onButtonCloseAll(View v) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Закрыть приложения")
                .setMessage("Все открытые сторонние приложения будут полностью закрыты и при следующем запуске откроются с нуля. Системные приложения не затрагиваются. Продолжить?")
                .setPositiveButton("Закрыть", (d, w) -> {
                    boolean ok = false;
                    if (GlobalVars.isBound && GlobalVars.serviceMessenger != null) {
                        try {
                            GlobalVars.serviceMessenger.send(Message.obtain(null, MSG_CLOSE_ALL));
                            ok = true;
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                    com.google.android.material.snackbar.Snackbar.make(
                            findViewById(R.id.main),
                            ok ? "Приложения закрыты" : "Сервис не готов",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
                    Log.i("$$$ Advance closeAll $$$", "MSG_CLOSE_ALL sent=" + ok);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // -------------------------------------------------------------------------
    // Системный док — переопределение приложений в доке лаунчера (слоты 1 и 2).
    // Выбранные пакеты хранятся в DrivePreferences (dockOverride1/2 + *Label); их читает
    // Frida-хук в процессе лаунчера, чтобы подменить ярлыки дока и открывать их на нашем VD.
    // -------------------------------------------------------------------------
    private Button dockApp1Btn, dockApp2Btn;
    private Button dockSplit1Btn, dockSplit2Btn;

    private void initDockOverride() {
        // «Системный док» завязан на Frida-хук лаунчера → только full. В light прячем весь блок.
        View block = findViewById(R.id.dockOverrideBlock);
        if (!BuildConfig.IS_FULL) {
            if (block != null) block.setVisibility(View.GONE);
            return;
        }
        dockApp1Btn = findViewById(R.id.buttonDockApp1);
        dockApp2Btn = findViewById(R.id.buttonDockApp2);
        dockSplit1Btn = findViewById(R.id.buttonDockSplit1);
        dockSplit2Btn = findViewById(R.id.buttonDockSplit2);
        refreshDockButtons();
        if (dockApp1Btn != null) dockApp1Btn.setOnLongClickListener(v -> { clearDockApp(1); return true; });
        if (dockApp2Btn != null) dockApp2Btn.setOnLongClickListener(v -> { clearDockApp(2); return true; });
        pushDockConfig();   // синхронизируем выбор дока в Native при открытии раздела
    }

    public void onPickDockApp1(View v) { pickDockApp(1); }
    public void onPickDockApp2(View v) { pickDockApp(2); }
    public void onPickDockSplit1(View v) { pickDockSplit(1); }
    public void onPickDockSplit2(View v) { pickDockSplit(2); }

    private void pickDockApp(int slot) {
        showAppPicker("Приложение " + slot + " в доке", (pkg, label) -> {
            prefs.edit().putString("dockOverride" + slot, pkg)
                        .putString("dockOverride" + slot + "Label", label).apply();
            refreshDockButtons();
            pushDockConfig();
        });
    }

    /** Выбор сплита, открываемого долгим нажатием на слот дока. Список — только «готовые» пресеты
     *  (оба приложения выбраны). «Нет» снимает назначение. Индекс пресета хранится в dockOverride&lt;slot&gt;Split. */
    private void pickDockSplit(int slot) {
        final java.util.List<SplitStore.Preset> all = SplitStore.load(prefs);
        final java.util.List<Integer> readyIdx = new java.util.ArrayList<>();
        final java.util.List<CharSequence> labels = new java.util.ArrayList<>();
        labels.add("Нет (только открыть приложение)");
        for (int i = 0; i < all.size(); i++) {
            SplitStore.Preset ps = all.get(i);
            if (ps.ready()) {
                readyIdx.add(i);
                labels.add((ps.ll.isEmpty() ? ps.l : ps.ll) + "  /  " + (ps.rl.isEmpty() ? ps.r : ps.rl));
            }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Сплит по долгому нажатию (слот " + slot + ")")
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                    if (which == 0) {
                        prefs.edit().remove("dockOverride" + slot + "Split")
                                    .remove("dockOverride" + slot + "SplitLabel").apply();
                    } else {
                        int idx = readyIdx.get(which - 1);
                        prefs.edit().putInt("dockOverride" + slot + "Split", idx)
                                    .putString("dockOverride" + slot + "SplitLabel", labels.get(which).toString()).apply();
                    }
                    refreshDockButtons();
                    pushDockConfig();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void clearDockApp(int slot) {
        // Слот сброшен → назначение сплита на этот слот теряет смысл, чистим и его.
        prefs.edit().remove("dockOverride" + slot).remove("dockOverride" + slot + "Label")
                    .remove("dockOverride" + slot + "Split").remove("dockOverride" + slot + "SplitLabel").apply();
        refreshDockButtons();
        pushDockConfig();
        com.google.android.material.snackbar.Snackbar.make(findViewById(R.id.main),
                "Слот " + slot + " сброшен", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
    }

    private void refreshDockButtons() {
        setDockButtonText(dockApp1Btn, 1);
        setDockButtonText(dockApp2Btn, 2);
        setDockSplitButton(dockSplit1Btn, 1);
        setDockSplitButton(dockSplit2Btn, 2);
    }

    private void setDockButtonText(Button b, int slot) {
        if (b == null) return;
        String label = prefs.getString("dockOverride" + slot + "Label", "");
        b.setText("Приложение " + slot + ": " + (label.isEmpty() ? "не выбрано" : label));
    }

    /** Кнопка выбора сплита для слота: видима только когда в слоте выбрано приложение; текст — назначенный сплит. */
    private void setDockSplitButton(Button b, int slot) {
        if (b == null) return;
        boolean hasApp = !prefs.getString("dockOverride" + slot, "").isEmpty();
        b.setVisibility(hasApp ? View.VISIBLE : View.GONE);
        String label = prefs.getString("dockOverride" + slot + "SplitLabel", "");
        b.setText("Сплит по долгому нажатию: " + (label.isEmpty() ? "не выбран" : label));
    }

    /** Колбэк выбора приложения из диалога-списка. */
    interface AppPicked { void onPicked(String pkg, String label); }

    /** Диалог со списком установленных лаунчер-приложений; выбор → cb. */
    private void showAppPicker(String title, AppPicked cb) {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);

        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (android.content.pm.ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (!map.containsKey(pkg)) map.put(pkg, ri.loadLabel(pm).toString());
        }
        final java.util.List<String> pkgs = new java.util.ArrayList<>(map.keySet());
        java.util.Collections.sort(pkgs, (a, b) -> map.get(a).compareToIgnoreCase(map.get(b)));

        final CharSequence[] items = new CharSequence[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) items[i] = map.get(pkgs.get(i)) + "  ·  " + pkgs.get(i);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle(title)
                .setItems(items, (d, which) -> cb.onPicked(pkgs.get(which), map.get(pkgs.get(which))))
                .setNegativeButton("Отмена", null)
                .show();
    }

    /**
     * «Выдать права на установку»: выбор приложения →
     * MSG_GRANT_INSTALL в Native (priv-app), тот выдаёт app-op REQUEST_INSTALL_PACKAGES.
     */
    public void onButtonGrantInstall(View v) {
        showAppPicker("Выдать права на установку", (pkg, label) -> {
            sendGrantInstall(pkg);
            com.google.android.material.snackbar.Snackbar.make(
                    findViewById(R.id.main), "Право на установку выдано: " + label,
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
        });
    }

    // -------------------------------------------------------------------------
    // Приложения-ярлыки на главном экране (плитки как у сплита, запуск обычный)
    // -------------------------------------------------------------------------
    private android.widget.LinearLayout appShortcutsContainer;

    private void initAppShortcuts() {
        appShortcutsContainer = findViewById(R.id.appShortcutsContainer);
        renderAppShortcuts();
    }

    /** Кнопка «＋ Добавить приложение». */
    public void onAddAppShortcut(View v) {
        showAppPicker("Добавить приложение", (pkg, label) -> {
            java.util.List<String> list = AppShortcutStore.load(prefs);
            if (!list.contains(pkg)) {
                list.add(pkg);
                AppShortcutStore.save(prefs, list);
                renderAppShortcuts();
            }
        });
    }

    private void renderAppShortcuts() {
        if (appShortcutsContainer == null) return;
        appShortcutsContainer.removeAllViews();
        java.util.List<String> list = AppShortcutStore.load(prefs);
        android.content.pm.PackageManager pm = getPackageManager();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < list.size(); i++) {
            final String pkg = list.get(i);
            View row = inf.inflate(R.layout.item_app_shortcut, appShortcutsContainer, false);
            android.widget.ImageView ico = row.findViewById(R.id.shortcutIco);
            TextView label = row.findViewById(R.id.shortcutLabel);
            ImageButton del = row.findViewById(R.id.shortcutDelete);
            String name = pkg;
            try {
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                name = pm.getApplicationLabel(ai).toString();
                ico.setImageDrawable(pm.getApplicationIcon(ai));
            } catch (Exception ignored) {
            }
            label.setText(name);
            del.setOnClickListener(v -> {
                java.util.List<String> l2 = AppShortcutStore.load(prefs);
                l2.remove(pkg);
                AppShortcutStore.save(prefs, l2);
                renderAppShortcuts();
            });
            appShortcutsContainer.addView(row);
        }
    }

    // -------------------------------------------------------------------------
    // Разделение экрана (split screen) — список пресетов
    // -------------------------------------------------------------------------
    private android.widget.LinearLayout splitPresetsContainer;

    private void initSplitScreen() {
        splitPresetsContainer = findViewById(R.id.splitPresetsContainer);
        renderSplitPresets();
    }

    /** Кнопка «＋ Добавить сплит». */
    public void onAddSplitPreset(View v) {
        java.util.List<SplitStore.Preset> list = SplitStore.load(prefs);
        list.add(new SplitStore.Preset());
        saveSplitPresets(list);
        renderSplitPresets();
    }

    /** Пресеты зеркалятся в dock/steering Settings.Global, поэтому одной записи JSON недостаточно. */
    private void saveSplitPresets(java.util.List<SplitStore.Preset> list) {
        SplitStore.save(prefs, list);
        SplitConfigSync.pushAll(this, prefs);
    }

    private void renderSplitPresets() {
        if (splitPresetsContainer == null) return;
        splitPresetsContainer.removeAllViews();
        final java.util.List<SplitStore.Preset> list = SplitStore.load(prefs);
        LayoutInflater inf = LayoutInflater.from(this);

        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            SplitStore.Preset ps = list.get(i);
            View row = inf.inflate(R.layout.item_split_preset, splitPresetsContainer, false);

            Button lb = row.findViewById(R.id.splitLeftBtn);
            Button rb = row.findViewById(R.id.splitRightBtn);
            Button del = row.findViewById(R.id.splitDeleteBtn);
            android.widget.Spinner sp = row.findViewById(R.id.splitRatioSpinner);

            lb.setText("Слева: " + (ps.ll.isEmpty() ? "не выбрано" : ps.ll));
            rb.setText("Справа: " + (ps.rl.isEmpty() ? "не выбрано" : ps.rl));

            lb.setOnClickListener(v -> showAppPicker("Приложение слева", (pkg, label) -> {
                java.util.List<SplitStore.Preset> l2 = SplitStore.load(prefs);
                if (idx < l2.size()) { l2.get(idx).l = pkg; l2.get(idx).ll = label; saveSplitPresets(l2); renderSplitPresets(); }
            }));
            rb.setOnClickListener(v -> showAppPicker("Приложение справа", (pkg, label) -> {
                java.util.List<SplitStore.Preset> l2 = SplitStore.load(prefs);
                if (idx < l2.size()) { l2.get(idx).r = pkg; l2.get(idx).rl = label; saveSplitPresets(l2); renderSplitPresets(); }
            }));

            android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
                    this, R.layout.spinner_ratio_item, SplitStore.RATIO_LABELS);
            ad.setDropDownViewResource(R.layout.spinner_ratio_dropdown);
            sp.setAdapter(ad);
            sp.setSelection(ps.ratio, false);
            sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                    java.util.List<SplitStore.Preset> l2 = SplitStore.load(prefs);
                    if (idx < l2.size() && l2.get(idx).ratio != pos) {
                        l2.get(idx).ratio = pos;
                        l2.get(idx).split = 0f;
                        saveSplitPresets(l2);
                    }
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });

            // Изменяемая пропорция. Снятие галки сбрасывает и сохранённую вручную долю — иначе пресет
            // остался бы с «кривым» соотношением, которое из фиксированного списка уже не выставить.
            Switch resizable = row.findViewById(R.id.splitResizableSwitch);
            if (resizable != null) {
                resizable.setChecked(ps.resizable);
                resizable.setOnCheckedChangeListener((b, checked) -> {
                    java.util.List<SplitStore.Preset> l2 = SplitStore.load(prefs);
                    if (idx < l2.size()) {
                        l2.get(idx).resizable = checked;
                        if (!checked) l2.get(idx).split = 0f;
                        saveSplitPresets(l2);
                    }
                });
            }

            del.setOnClickListener(v -> {
                java.util.List<SplitStore.Preset> l2 = SplitStore.load(prefs);
                if (idx < l2.size()) { l2.remove(idx); saveSplitPresets(l2); renderSplitPresets(); }
            });

            splitPresetsContainer.addView(row);
        }
    }

    // Значения DPI для пикера приложений (0 = авто = плотность экрана по умолчанию)
    private static final int[]    DPI_VALUES = {0, 120, 140, 160, 180, 200, 213, 240, 260, 280, 300, 320, 360};
    private static final String[] DPI_LABELS = {"Авто", "120", "140", "160", "180", "200", "213", "240", "260", "280", "300", "320", "360"};

    private int dpiIndex(int dpi) {
        for (int i = 0; i < DPI_VALUES.length; i++) if (DPI_VALUES[i] == dpi) return i;
        return 0; // авто
    }

    /**
     * Список сторонних приложений с пикером DPI на каждое. Значение сохраняется в {@link AppDpiStore}
     * (per-app) и применяется к окну этого приложения при открытии сплита. Пикер — тёмный спиннер
     * (как у соотношения), без белого фона.
     */
    private void initAppDpiList() {
        android.widget.LinearLayout container = findViewById(R.id.appDpiContainer);
        if (container == null) return;
        container.removeAllViews();
        android.content.pm.PackageManager pm = getPackageManager();
        LayoutInflater inf = LayoutInflater.from(this);

        // Сторонние (не системные) запускаемые приложения, отсортированы по имени
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (map.containsKey(pkg)) continue;
            try {
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue; // только сторонние
            } catch (Exception e) {
                continue;
            }
            map.put(pkg, ri.loadLabel(pm).toString());
        }
        final java.util.List<String> pkgs = new java.util.ArrayList<>(map.keySet());
        java.util.Collections.sort(pkgs, (a, b) -> map.get(a).compareToIgnoreCase(map.get(b)));

        for (String pkg : pkgs) {
            final String fpkg = pkg;
            View row = inf.inflate(R.layout.item_app_dpi, container, false);
            android.widget.ImageView ico = row.findViewById(R.id.appDpiIco);
            TextView label = row.findViewById(R.id.appDpiLabel);
            android.widget.Spinner sp = row.findViewById(R.id.appDpiSpinner);

            try { ico.setImageDrawable(pm.getApplicationIcon(pkg)); } catch (Exception ignored) {}
            label.setText(map.get(pkg));

            android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
                    this, R.layout.spinner_ratio_item, DPI_LABELS);
            ad.setDropDownViewResource(R.layout.spinner_ratio_dropdown);
            sp.setAdapter(ad);
            sp.setSelection(dpiIndex(AppDpiStore.get(prefs, pkg)), false);
            sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                    if (DPI_VALUES[pos] != AppDpiStore.get(prefs, fpkg)) AppDpiStore.set(prefs, fpkg, DPI_VALUES[pos]);
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });

            container.addView(row);
        }
    }

    /** Отправляет имя пакета + uid в SetModesService для выдачи app-op установки. */
    private void sendGrantInstall(String pkg) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance grantInstall $$$", "SetModesService не забинден");
            return;
        }
        int uid;
        try {
            uid = getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Exception e) {
            Log.w("$$$ Advance grantInstall $$$", "не найден uid для " + pkg);
            return;
        }
        try {
            Message m = Message.obtain(null, MSG_GRANT_INSTALL, uid, 0);
            Bundle b = new Bundle();
            b.putString("pkg", pkg);
            m.setData(b);
            GlobalVars.serviceMessenger.send(m);
            Log.i("$$$ Advance grantInstall $$$", "MSG_GRANT_INSTALL pkg=" + pkg + " uid=" + uid);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /** «Перезагрузить систему»: диалог подтверждения → MSG_REBOOT в Native (priv-app), тот зовёт PowerManager.reboot. */
    public void onButtonRebootSystem(View v) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Перезагрузка системы")
                .setMessage("Система (голова) будет перезагружена. Несохранённые действия могут прерваться. Продолжить?")
                .setPositiveButton("Перезагрузить", (d, w) -> {
                    if (GlobalVars.isBound && GlobalVars.serviceMessenger != null) {
                        try {
                            GlobalVars.serviceMessenger.send(Message.obtain(null, MSG_REBOOT));
                            Log.i("$$$ Advance reboot $$$", "MSG_REBOOT sent");
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    } else {
                        Log.w("$$$ Advance reboot $$$", "SetModesService не забинден");
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** «Логирование»: экран с тумблером записи логов Native, живой лентой и выгрузкой файла. */
    public void onButtonLogging(View v) {
        startActivity(new Intent(this, LoggingActivity.class));
    }

    /** Переключение разделов (0 главный экран, 1 настройки автомобиля, 2 приложения и разделение экрана,
     *  3 Apollo Tech, 4 собственные команды, 5 кнопки на руле, 6 другое). */
    private void setSection(int index) {
        currentSection = index;
        if (sectionTitle != null && index >= 0 && index < SECTION_TITLES.length)
            sectionTitle.setText(SECTION_TITLES[index]);
        if (pageMainScreen != null)      pageMainScreen.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        if (pageDriveModes != null)      pageDriveModes.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (pageSplitScreen != null)     pageSplitScreen.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (pageApolloTech != null)      pageApolloTech.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        if (pageCustomCommands != null)  pageCustomCommands.setVisibility(index == 4 ? View.VISIBLE : View.GONE);
        if (pageSteeringButtons != null) pageSteeringButtons.setVisibility(index == 5 ? View.VISIBLE : View.GONE);
        if (pageOther != null)           pageOther.setVisibility(index == 6 ? View.VISIBLE : View.GONE);
        if (navMainScreen != null)       navMainScreen.setSelected(index == 0);
        if (navDriveModes != null)       navDriveModes.setSelected(index == 1);
        if (navSplitScreen != null)      navSplitScreen.setSelected(index == 2);
        if (navApolloTech != null)       navApolloTech.setSelected(index == 3);
        if (navCustomCommands != null)   navCustomCommands.setSelected(index == 4);
        if (navSteeringButtons != null)  navSteeringButtons.setSelected(index == 5);
        if (navOther != null)            navOther.setSelected(index == 6);

        // Apollo-переключатели применяются немедленно отдельными guarded-командами.
        if (buttonApplyAdvance != null) {
            buttonApplyAdvance.setVisibility(index == 3 ? View.GONE : View.VISIBLE);
        }
        if (applyProgressAdvance != null) {
            applyProgressAdvance.setVisibility(index != 3 && applying ? View.VISIBLE : View.GONE);
        }
        if (index == 3) requestApolloState();
    }

    // -------------------------------------------------------------------------
    // Apollo Tech — безопасный UI поверх подтверждённого Native/VehicleState состояния.
    // Никаких локальных prefs: открытие страницы только читает, переключатели отправляют отдельные
    // команды, а окончательное положение всегда приходит в ACTION_APOLLO_TLC_UPDATE.
    // -------------------------------------------------------------------------

    private void initApolloTech() {
        switchApolloMaster = findViewById(R.id.switchApolloMaster);
        switchApolloTlc = findViewById(R.id.switchApolloTlc);
        switchApolloTrafficLights = findViewById(R.id.switchApolloTrafficLights);
        switchApolloTrafficSigns = findViewById(R.id.switchApolloTrafficSigns);
        apolloGreenSoundGroup = findViewById(R.id.apolloGreenSoundGroup);
        apolloGreenSoundContainer = findViewById(R.id.apolloGreenSoundContainer);
        buttonApolloForceOff = findViewById(R.id.buttonApolloForceOff);
        textApolloStatus = findViewById(R.id.textApolloStatus);
        textApolloDiagnostics = findViewById(R.id.textApolloDiagnostics);
        textApolloFullOnly = findViewById(R.id.textApolloFullOnly);
        apolloControls = findViewById(R.id.apolloControls);

        syncingApolloUi = true;
        try {
            if (switchApolloMaster != null) {
                switchApolloMaster.setChecked(false);
                switchApolloMaster.setEnabled(false);
            }
            if (switchApolloTlc != null) {
                switchApolloTlc.setChecked(false);
                switchApolloTlc.setEnabled(false);
            }
            if (switchApolloTrafficLights != null) {
                switchApolloTrafficLights.setChecked(false);
                switchApolloTrafficLights.setEnabled(false);
            }
            if (switchApolloTrafficSigns != null) {
                switchApolloTrafficSigns.setChecked(false);
                switchApolloTrafficSigns.setEnabled(false);
            }
            if (apolloGreenSoundGroup != null) {
                apolloGreenSoundGroup.clearCheck();
                apolloGreenSoundGroup.setEnabled(false);
            }
        } finally {
            syncingApolloUi = false;
        }

        if (switchApolloMaster != null) {
            switchApolloMaster.setOnCheckedChangeListener((button, checked) -> {
                if (syncingApolloUi) return;
                if (!canChangeApolloMaster(checked)) {
                    updateApolloUi();
                    return;
                }
                updateApolloUi();
                if (checked) showApolloMasterEnableDialog();
                else sendApolloCommand(MSG_APOLLO_SET_MASTER, false);
            });
        }
        if (switchApolloTlc != null) {
            switchApolloTlc.setOnCheckedChangeListener((button, checked) -> {
                if (syncingApolloUi) return;
                if (!canChangeApolloTlc(checked)) {
                    updateApolloUi();
                    return;
                }
                updateApolloUi();
                if (checked) showApolloTlcEnableDialog();
                else sendApolloCommand(MSG_APOLLO_SET_TLC, false);
            });
        }
        if (switchApolloTrafficLights != null) {
            switchApolloTrafficLights.setOnCheckedChangeListener((button, checked) -> {
                if (syncingApolloUi) return;
                if (!canChangeApolloTrafficLights()) {
                    updateApolloUi();
                    return;
                }
                updateApolloUi();
                sendApolloCommand(MSG_APOLLO_SET_GLA, checked);
            });
        }
        if (switchApolloTrafficSigns != null) {
            switchApolloTrafficSigns.setOnCheckedChangeListener((button, checked) -> {
                if (syncingApolloUi) return;
                if (!canChangeApolloTrafficSigns()) {
                    updateApolloUi();
                    return;
                }
                updateApolloUi();
                sendApolloCommand(MSG_APOLLO_SET_TSR, checked);
            });
        }
        if (apolloGreenSoundGroup != null) {
            apolloGreenSoundGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (syncingApolloUi || checkedId == -1) return;
                if (!canChangeApolloGreenSound()) {
                    updateApolloUi();
                    return;
                }
                boolean enabled = checkedId == R.id.apolloGreenSoundOn;
                sendApolloCommand(MSG_APOLLO_SET_GLA_SOUND, enabled);
            });
        }
        if (buttonApolloForceOff != null) {
            buttonApolloForceOff.setOnClickListener(v -> {
                if (canForceApolloMasterOff()) {
                    sendApolloCommand(MSG_APOLLO_SET_MASTER, false);
                }
            });
        }
        updateApolloUi();
    }

    /** Read-only запрос при открытии Apollo Tech: Messenger query + явный broadcast-request. */
    private void requestApolloState() {
        if (!BuildConfig.IS_FULL) {
            updateApolloUi();
            return;
        }
        if (!apolloHasState && textApolloStatus != null) {
            textApolloStatus.setText("Запрашиваем состояние Apollo Tech у Native…");
        }
        if (GlobalVars.isBound && GlobalVars.serviceMessenger != null) {
            try {
                GlobalVars.serviceMessenger.send(Message.obtain(null, MSG_APOLLO_QUERY));
            } catch (RemoteException e) {
                Log.w("$$$ Advance Apollo $$$", "Не удалось отправить MSG_APOLLO_QUERY", e);
            }
        }
        Intent request = new Intent(ACTION_REQUEST_APOLLO_TLC_UPDATE).setPackage(NATIVE_PACKAGE);
        sendBroadcast(request);
    }

    private void sendApolloCommand(int what, boolean enabled) {
        if (!BuildConfig.IS_FULL || !GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            apolloError = "Сервис Native не готов";
            updateApolloUi();
            return;
        }
        try {
            // Локально блокируем повторный тап. Checkbox не считается источником истины и немедленно
            // возвращается к последнему feedback; новое значение покажет только Native broadcast.
            apolloPending = true;
            apolloError = "";
            updateApolloUi();
            GlobalVars.serviceMessenger.send(Message.obtain(null, what, enabled ? 1 : 0, 0));
        } catch (RemoteException e) {
            apolloPending = false;
            apolloError = "Не удалось отправить команду в Native";
            updateApolloUi();
            Log.w("$$$ Advance Apollo $$$", "Ошибка команды what=" + what, e);
        }
    }

    private boolean isApolloServiceReady() {
        return GlobalVars.isBound && GlobalVars.serviceMessenger != null;
    }

    private boolean canChangeApolloMaster(boolean desiredEnabled) {
        return false;
    }

    /** Separate emergency path: an unreadable master must never remove the ability to force OFF. */
    private boolean canForceApolloMasterOff() {
        return BuildConfig.IS_FULL && apolloHasState && !apolloMasterKnown
                && isApolloServiceReady();
    }

    private boolean canChangeApolloTlc(boolean desiredEnabled) {
        return BuildConfig.IS_FULL && apolloHasState && isApolloServiceReady()
                && apolloCanConnected && apolloProfileSupported && apolloDirectTlcMode
                && (apolloPlcSwitch == 1 || apolloPlcSwitch == 2)
                && !apolloPending && apolloGear == 0;
    }

    private boolean canChangeApolloTrafficLights() {
        return BuildConfig.IS_FULL && apolloHasState && isApolloServiceReady()
                && apolloCanConnected && apolloProfileSupported && apolloDirectTlcMode
                && (apolloGlaSwitch == 1 || apolloGlaSwitch == 2)
                && !apolloPending;
    }

    private boolean canChangeApolloGreenSound() {
        return canChangeApolloTrafficLights() && apolloGlaSwitch == 2
                && (apolloGlaLightChangeSwitch == 1
                || apolloGlaLightChangeSwitch == 2);
    }

    private boolean canChangeApolloTrafficSigns() {
        return BuildConfig.IS_FULL && apolloHasState && isApolloServiceReady()
                && apolloCanConnected && apolloProfileSupported && apolloDirectTlcMode
                && (apolloTsrSwitch == 1 || apolloTsrSwitch == 2)
                && !apolloPending;
    }

    private boolean isApolloPlcStatusValid() {
        // В штатном binding 7 означает unavailable. Integer.MIN_VALUE — общий sentinel Native,
        // когда VehicleState ещё не прочитан; pinned Native принимает только диапазон 0..7.
        return apolloPlcStatus >= 0 && apolloPlcStatus < 7;
    }

    private boolean isApolloSnapshotValid() {
        return isApolloPlcStatusValid()
                && (apolloPlcSwitch == 1 || apolloPlcSwitch == 2)
                && (apolloAnpSwitch == 1 || apolloAnpSwitch == 2)
                // На точном H97X-профиле entitlement-поля равны -1 до первой master-активации.
                // Это допустимо только для master; TLC ниже всё равно требует подтверждённые 2/2.
                && (apolloTlcCapability == -1
                    || apolloTlcCapability == 1 || apolloTlcCapability == 2)
                && (apolloPlcCapabilitySa == -1
                    || apolloPlcCapabilitySa == 1 || apolloPlcCapabilitySa == 2);
    }

    private void updateApolloUi() {
        boolean full = BuildConfig.IS_FULL;
        if (textApolloFullOnly != null) {
            textApolloFullOnly.setVisibility(full ? View.GONE : View.VISIBLE);
        }
        if (apolloControls != null) apolloControls.setAlpha(full ? 1f : 0.45f);

        syncingApolloUi = true;
        try {
            if (switchApolloMaster != null) {
                switchApolloMaster.setChecked(false);
                switchApolloMaster.setEnabled(false);
            }
            if (switchApolloTlc != null) {
                switchApolloTlc.setChecked(apolloHasState && apolloPlcSwitch == 2);
                switchApolloTlc.setEnabled(canChangeApolloTlc(apolloPlcSwitch != 2));
            }
            if (switchApolloTrafficLights != null) {
                switchApolloTrafficLights.setChecked(
                        apolloHasState && apolloGlaSwitch == 2);
                switchApolloTrafficLights.setEnabled(canChangeApolloTrafficLights());
            }
            if (switchApolloTrafficSigns != null) {
                // TSR has the inverse OEM encoding: 1=enabled, 2=disabled.
                switchApolloTrafficSigns.setChecked(
                        apolloHasState && apolloTsrSwitch == 1);
                switchApolloTrafficSigns.setEnabled(canChangeApolloTrafficSigns());
            }
            if (apolloGreenSoundGroup != null) {
                if (apolloGlaLightChangeSwitch == 1) {
                    apolloGreenSoundGroup.check(R.id.apolloGreenSoundOff);
                } else if (apolloGlaLightChangeSwitch == 2) {
                    apolloGreenSoundGroup.check(R.id.apolloGreenSoundOn);
                } else {
                    apolloGreenSoundGroup.clearCheck();
                }
                boolean soundEnabled = canChangeApolloGreenSound();
                apolloGreenSoundGroup.setEnabled(soundEnabled);
                findViewById(R.id.apolloGreenSoundOff).setEnabled(soundEnabled);
                findViewById(R.id.apolloGreenSoundOn).setEnabled(soundEnabled);
            }
        } finally {
            syncingApolloUi = false;
        }

        if (buttonApolloForceOff != null) {
            buttonApolloForceOff.setVisibility(View.GONE);
            buttonApolloForceOff.setEnabled(false);
        }
        if (apolloGreenSoundContainer != null) {
            apolloGreenSoundContainer.setAlpha(
                    apolloHasState && apolloGlaSwitch == 2 ? 1f : 0.45f);
        }

        if (textApolloStatus != null) textApolloStatus.setText(buildApolloStatus(full));
        if (textApolloDiagnostics != null) textApolloDiagnostics.setText(buildApolloDiagnostics());
    }

    private String buildApolloStatus(boolean full) {
        if (!full) return "Функция недоступна в этой версии VoyahTune.";
        if (!apolloHasState) return "Получаем состояние автомобиля…";
        if (!apolloError.isEmpty()) return formatApolloError(apolloError);
        if (!isApolloServiceReady()) return "Нет связи с автомобилем.";
        if (apolloPending) return "Применяем настройку…";
        if (!apolloCanConnected) {
            return "Нет связи с автомобилем.";
        }
        if (!apolloProfileSupported || !apolloDirectTlcMode)
            return "Функция пока недоступна для этой версии автомобиля.";
        if (apolloPlcSwitch != 1 && apolloPlcSwitch != 2) {
            return "Не удалось определить состояние TLC.";
        }
        if ((apolloGlaSwitch != 1 && apolloGlaSwitch != 2)
                || (apolloGlaLightChangeSwitch != 1
                && apolloGlaLightChangeSwitch != 2)) {
            return "Не удалось определить состояние распознавания светофоров.";
        }
        if (apolloTsrSwitch != 1 && apolloTsrSwitch != 2) {
            return "Не удалось определить состояние распознавания дорожных знаков.";
        }
        if (apolloGear != 0) {
            return "Изменять TLC можно только на стоянке в положении P.";
        }
        return "Настройки синхронизированы с автомобилем.";
    }

    private String buildApolloDiagnostics() {
        if (!apolloHasState) {
            return "Диагностика: соединение, селектор передач и состояние TLC ещё неизвестны.";
        }
        return "Диагностика Native:\n"
                + "CAN (автомобильная шина): " + (apolloCanConnected ? "подключена" : "нет подключения")
                + " · прямой режим TLC: " + (apolloDirectTlcMode ? "доступен" : "недоступен")
                + " · ожидание ответа: " + (apolloPending ? "да" : "нет") + "\n"
                + "Селектор передач: " + formatApolloGear(apolloGear)
                + " · PLC_SWITCH (штатное состояние TLC): "
                + formatApolloSwitch(apolloPlcSwitch)
                + (apolloError.isEmpty() ? "" : "\nДиагностика: " + formatApolloError(apolloError));
    }

    private String formatApolloGear(int value) {
        if (value == 0) return "Parking/P (0)";
        if (value == -1) return "неизвестно (-1)";
        return String.valueOf(value);
    }

    private String formatApolloSwitch(int value) {
        if (value == 1) return "выкл (1)";
        if (value == 2) return "вкл (2)";
        return formatApolloValue(value);
    }

    private String formatApolloValue(int value) {
        return value < 0 ? "неизвестно" : String.valueOf(value);
    }

    /** Переводит внутренние fail-closed коды Native в понятное сообщение для водителя. */
    private String formatApolloError(String error) {
        switch (error) {
            case "Сервис Native не готов":
            case "Не удалось отправить команду в Native":
                return error + ".";
            case "unsupported_light":
                return "Функция недоступна в этой версии VoyahTune.";
            case "profile_check_pending":
                return "Проверяем совместимость с автомобилем…";
            case "profile_hash_mismatch":
            case "profile_vehicle_setting_hash_mismatch":
            case "profile_canbus_hash_mismatch":
            case "profile_apk_not_found":
            case "profile_vehicle_setting_apk_not_found":
            case "profile_canbus_apk_not_found":
            case "profile_hash_failed":
            case "profile_vehicle_setting_hash_failed":
            case "profile_canbus_hash_failed":
            case "profile_canbus_revalidation_pending":
            case "profile_vehicle_setting_hash_unavailable":
                return "Функция пока недоступна для этой версии автомобиля.";
            case "profile_heartbeat_missing":
            case "profile_heartbeat_future":
            case "profile_heartbeat_stale":
            case "profile_heartbeat_read_failed":
            case "profile_not_confirmed":
            case "profile_unsupported":
                return "Функция пока недоступна для этой версии автомобиля.";
            case "profile_runtime_mismatch":
            case "profile_callback_mismatch":
            case "profile_callback_malformed":
            case "profile_binder_descriptor_mismatch":
            case "profile_gear_parcel_mismatch":
                return "Функция пока недоступна для этой версии автомобиля.";
            case "can_disconnected":
            case "can_descriptor_failed":
                return "Нет связи с автомобилем.";
            case "callback_unavailable":
            case "callback_register_failed":
                return "Не удалось получить подтверждение состояния от автомобиля.";
            case "write_permission_missing":
                return "Функция установлена некорректно. Переустановите VoyahTune.";
            case "master_disabled":
                return "Основной переключатель Apollo Tech выключен.";
            case "write_pending":
                return "Предыдущая команда ещё ожидает подтверждения автомобиля.";
            case "state_read_failed":
            case "prewrite_read_failed":
            case "immediate_readback_failed":
            case "delayed_readback_failed":
                return "Не удалось подтвердить текущее состояние автомобиля; повторная запись не выполняется.";
            case "readback_mismatch":
                return "Автомобиль не подтвердил запрошенное состояние; повторная запись не выполняется.";
            case "gear_not_parking":
                return "Изменять TLC можно только на стоянке в положении P.";
            case "invalid_plc_switch":
            case "invalid_switch_state":
            case "invalid_plc_status":
            case "plc_status_error":
                return "Не удалось определить состояние TLC.";
            case "invalid_anp_switch":
                return "ANP (штатный навигационный пилот) вернул неизвестное состояние.";
            case "invalid_tlc_capability":
            case "invalid_plc_capability_sa":
            case "capability_disabled":
                return "Автомобиль не подтвердил необходимые разрешения доступности TLC.";
            case "anp_must_be_off":
                return "Сначала выключите ANP (штатный навигационный пилот) штатными средствами.";
            case "traffic_light_recognition_disabled":
                return "Сначала включите распознавание светофоров.";
            case "traffic_light_entitlement_rejected":
            case "traffic_light_entitlement_tx_failed":
            case "traffic_light_entitlement_disable_rejected":
            case "traffic_light_entitlement_disable_tx_failed":
                return "Не удалось полностью изменить настройку светофоров. Попробуйте ещё раз.";
            case "tx58_failed":
                return "Автомобиль не принял настройку. Попробуйте ещё раз.";
            case "invalid_argument":
                return "Получена некорректная команда; запись не выполнялась.";
            case "master_write_failed":
            case "master_clear_failed":
                return "Не удалось безопасно изменить основной переключатель Apollo Tech. "
                        + "Показано фактически прочитанное состояние; повторите выключение.";
            case "master_read_failed":
                return "Состояние основного переключателя Apollo Tech неизвестно. TLC заблокирован; "
                        + "используйте кнопку «Принудительно выключить Apollo».";
            case "request_receiver_failed":
                return "Не удалось запустить защищённый канал диагностики Apollo Tech.";
            default:
                return "Не удалось выполнить операцию. Попробуйте ещё раз.";
        }
    }

    private void showApolloMasterEnableDialog() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Включить Apollo Tech?")
                .setMessage("Штатному менеджеру Baidu Apollo будет передано состояние активной "
                        + "подписки, после чего он обновит пакет из 18 разрешений доступности. "
                        + "Это меняет доступность комплекта функций интеллектуального вождения. "
                        + "Используйте их только там, где это безопасно и разрешено. Продолжить?")
                .setPositiveButton("Включить", (dialog, which) -> {
                    if (canChangeApolloMaster(true)) sendApolloCommand(MSG_APOLLO_SET_MASTER, true);
                    else updateApolloUi();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showApolloTlcEnableDialog() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Включить TLC?")
                .setMessage("TLC помогает выполнять перестроение по запросу или подтверждению "
                        + "водителя. Водитель всегда отвечает за безопасность манёвра. Продолжить?")
                .setPositiveButton("Включить", (dialog, which) -> {
                    if (canChangeApolloTlc(true)) sendApolloCommand(MSG_APOLLO_SET_TLC, true);
                    else updateApolloUi();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showApolloAnpDependencyDialog() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Нельзя выключить TLC")
                .setMessage("ANP — штатное кодовое обозначение навигационного пилота — сейчас "
                        + "активен. Точная буквенная расшифровка ANP в приложении производителя "
                        + "отсутствует. Штатная логика автомобиля требует сначала выключить ANP. "
                        + "VoyahTune не изменяет ANP автоматически.")
                .setPositiveButton("Понятно", null)
                .show();
    }

    // -------------------------------------------------------------------------
    // Кнопки на руле — назначение действий на короткое/долгое нажатие.
    // Дефолт "none" = «Не менять» → штатное системное поведение (Frida-хук пропускает кнопку).
    // Идентификатор выбранного действия хранится в prefs и исполняется Native через STEER_ACTION.
    // -------------------------------------------------------------------------

    // {id, ярлык}. Для energy:<режимы> последовательное нажатие циклирует режимы по кругу
    // (Native хранит текущий и шлёт CAN). Режимы: EV=Electric, REV=Fuel, SREV=Save.
    static final String[][] STEER_ACTIONS = {
            {"none",               "Не менять"},
            {"open_voyahtune",     "Открыть VoyahTune"},
            {"energy:EV",          "Энергорежим: Electric"},
            {"energy:REV",         "Энергорежим: Fuel"},
            {"energy:SREV",        "Энергорежим: Save"},
            {"energy:EV,REV",      "Энергорежим: Electric → Fuel"},
            {"energy:EV,REV,SREV", "Энергорежим: Electric → Fuel → Save"},
            {"energy:EV,SREV",     "Энергорежим: Electric → Save"},
            {"energy:REV,SREV",    "Энергорежим: Fuel → Save"},
            {"drive:ECO",                "Режим езды: Eco"},
            {"drive:COMFORT",            "Режим езды: Comf"},
            {"drive:SPORT",              "Режим езды: Sport"},
            {"drive:OUTING",             "Режим езды: Outing"},
            {"drive:SNOW",               "Режим езды: Snow"},
            {"drive:INDIVIDUAL",         "Режим езды: Indiv"},
            {"drive:ECO,COMFORT",        "Режим езды: Eco → Comf"},
            {"drive:ECO,SPORT",          "Режим езды: Eco → Sport"},
            {"drive:ECO,OUTING",         "Режим езды: Eco → Outing"},
            {"drive:ECO,SNOW",           "Режим езды: Eco → Snow"},
            {"drive:ECO,INDIVIDUAL",     "Режим езды: Eco → Indiv"},
            {"drive:COMFORT,SPORT",      "Режим езды: Comf → Sport"},
            {"drive:COMFORT,OUTING",     "Режим езды: Comf → Outing"},
            {"drive:COMFORT,SNOW",       "Режим езды: Comf → Snow"},
            {"drive:COMFORT,INDIVIDUAL", "Режим езды: Comf → Indiv"},
            {"drive:SPORT,OUTING",       "Режим езды: Sport → Outing"},
            {"drive:SPORT,SNOW",         "Режим езды: Sport → Snow"},
            {"drive:SPORT,INDIVIDUAL",   "Режим езды: Sport → Indiv"},
            {"drive:OUTING,SNOW",        "Режим езды: Outing → Snow"},
            {"drive:OUTING,INDIVIDUAL",  "Режим езды: Outing → Indiv"},
            {"drive:SNOW,INDIVIDUAL",    "Режим езды: Snow → Indiv"},
            {"recycle:LOW",              "Рекуперация: Низкая"},
            {"recycle:MEDIUM",           "Рекуперация: Стандартная"},
            {"recycle:HIGH",             "Рекуперация: Высокая"},
            {"recycle:LOW,MEDIUM",       "Рекуперация: Низкая → Стандартная"},
            {"recycle:LOW,HIGH",         "Рекуперация: Низкая → Высокая"},
            {"recycle:MEDIUM,HIGH",      "Рекуперация: Стандартная → Высокая"},
            {"toggle_forced_ev",         "Force EV: вкл/выкл"},
            {"toggle_pedestrian_sound",  "Звук пешеходов: вкл/выкл"},
    };

    private void initSteeringButtons() {
        steerStarShortBtn = findViewById(R.id.steerStarShortBtn);
        steerStarLongBtn  = findViewById(R.id.steerStarLongBtn);
        steerDvrShortBtn  = findViewById(R.id.steerDvrShortBtn);
        steerDvrLongBtn   = findViewById(R.id.steerDvrLongBtn);
        steerVoiceShortBtn  = findViewById(R.id.steerVoiceShortBtn);
        steerVoiceLongBtn   = findViewById(R.id.steerVoiceLongBtn);
        steerPhoneShortBtn  = findViewById(R.id.steerPhoneShortBtn);
        steerPhoneLongBtn   = findViewById(R.id.steerPhoneLongBtn);
        refreshSteerButtons();
        pushSteerConfig();   // синхронизируем выбор в Native при открытии раздела
    }

    public void onPickSteerStarShort(View v) { pickSteerAction("steerStarShort", steerStarShortBtn); }
    public void onPickSteerStarLong(View v)  { pickSteerAction("steerStarLong",  steerStarLongBtn); }
    public void onPickSteerVoiceShort(View v) { pickSteerAction("steerVoiceShort", steerVoiceShortBtn); }
    public void onPickSteerVoiceLong(View v)  { pickSteerAction("steerVoiceLong",  steerVoiceLongBtn); }
    public void onPickSteerDvrShort(View v)  { pickSteerAction("steerDvrShort",  steerDvrShortBtn); }
    public void onPickSteerDvrLong(View v)   { pickSteerAction("steerDvrLong",   steerDvrLongBtn); }
    public void onPickSteerPhoneShort(View v) { pickSteerAction("steerPhoneShort", steerPhoneShortBtn); }
    public void onPickSteerPhoneLong(View v)  { pickSteerAction("steerPhoneLong",  steerPhoneLongBtn); }

    /** Диалог выбора действия для слота; сохраняет id в prefs, обновляет подпись кнопки. Помимо статических
     *  действий (STEER_ACTIONS) есть два динамических: «Открыть сплит…» и «Открыть приложение…» — они
     *  открывают под-пикер и сохраняют id вида «split:&lt;index&gt;» / «app:&lt;pkg&gt;». */
    private void pickSteerAction(String key, Button btn) {
        final int nStatic = STEER_ACTIONS.length;
        final CharSequence[] labels = new CharSequence[nStatic + 2];
        for (int i = 0; i < nStatic; i++) labels[i] = STEER_ACTIONS[i][1];
        labels[nStatic]     = "Открыть сплит…";
        labels[nStatic + 1] = "Открыть приложение…";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Действие")
                .setItems(labels, (d, which) -> {
                    if (which < nStatic) {
                        prefs.edit().putString(key, STEER_ACTIONS[which][0]).apply();
                        setSteerButtonText(btn, key);
                        pushSteerConfig();
                    } else if (which == nStatic) {
                        pickSteerSplit(key, btn);
                    } else {
                        pickSteerApp(key, btn);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Под-пикер «Открыть сплит»: список готовых пресетов → id «split:&lt;index&gt;». */
    private void pickSteerSplit(String key, Button btn) {
        final java.util.List<SplitStore.Preset> all = SplitStore.load(prefs);
        final java.util.List<Integer> readyIdx = new java.util.ArrayList<>();
        final java.util.List<CharSequence> labels = new java.util.ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            SplitStore.Preset ps = all.get(i);
            if (ps.ready()) {
                readyIdx.add(i);
                labels.add((ps.ll.isEmpty() ? ps.l : ps.ll) + "  /  " + (ps.rl.isEmpty() ? ps.r : ps.rl));
            }
        }
        if (readyIdx.isEmpty()) {
            com.google.android.material.snackbar.Snackbar.make(findViewById(R.id.main),
                    "Нет готовых сплитов — сначала настройте сплит в «Приложения и разделение экрана»",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Открыть сплит")
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                    prefs.edit().putString(key, "split:" + readyIdx.get(which)).apply();
                    setSteerButtonText(btn, key);
                    pushSteerConfig();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Под-пикер «Открыть приложение»: список приложений → id «app:&lt;pkg&gt;». */
    private void pickSteerApp(String key, Button btn) {
        showAppPicker("Открыть приложение", (pkg, label) -> {
            prefs.edit().putString(key, "app:" + pkg).apply();
            setSteerButtonText(btn, key);
            pushSteerConfig();
        });
    }

    private void refreshSteerButtons() {
        setSteerButtonText(steerStarShortBtn, "steerStarShort");
        setSteerButtonText(steerStarLongBtn,  "steerStarLong");
        setSteerButtonText(steerDvrShortBtn,  "steerDvrShort");
        setSteerButtonText(steerDvrLongBtn,   "steerDvrLong");
        setSteerButtonText(steerVoiceShortBtn,  "steerVoiceShort");
        setSteerButtonText(steerVoiceLongBtn,   "steerVoiceLong");
        setSteerButtonText(steerPhoneShortBtn,  "steerPhoneShort");
        setSteerButtonText(steerPhoneLongBtn,   "steerPhoneLong");
    }

    private void setSteerButtonText(Button b, String key) {
        if (b == null) return;
        b.setText(steerActionLabel(prefs.getString(key, "none")));
    }

    /** Человекочитаемая подпись действия: статические — из STEER_ACTIONS; «split:N» — из пресета сплита;
     *  «app:pkg» — имя приложения. */
    private String steerActionLabel(String id) {
        if (id == null || id.isEmpty()) return "Не менять";
        for (String[] a : STEER_ACTIONS) if (a[0].equals(id)) return a[1];
        if (id.startsWith("split:")) {
            try {
                int n = Integer.parseInt(id.substring("split:".length()));
                java.util.List<SplitStore.Preset> all = SplitStore.load(prefs);
                if (n >= 0 && n < all.size()) {
                    SplitStore.Preset ps = all.get(n);
                    return "Сплит: " + (ps.ll.isEmpty() ? ps.l : ps.ll) + " / " + (ps.rl.isEmpty() ? ps.r : ps.rl);
                }
            } catch (Exception ignored) {}
            return "Сплит (не найден)";
        }
        if (id.startsWith("app:")) {
            String pkg = id.substring("app:".length());
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                return "Приложение: " + pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception e) { return "Приложение: " + pkg; }
        }
        return "Не менять";
    }

    /** Зеркалим выбор действий кнопок в Native (он пишет их в Settings.Global — оттуда читает keymng2.js). */
    private void pushSteerConfig() {
        SplitConfigSync.pushSteering(this, prefs);
    }

    /** Зеркалим выбор «Системного дока» в Native (он пишет voyahtune_dock* в Settings.Global — оттуда
     *  читает launcherdock.js в процессе лаунчера и перерисовывает иконки/перехватывает клик). */
    private void pushDockConfig() {
        SplitConfigSync.pushDock(this, prefs);
    }


    /** Сегмент-контрол «Предупреждение пешеходов» (Со звуком/Без звука). Перенесён с главного. */
    private void initPedestrianSoundGroup() {
        RadioGroup group = findViewById(R.id.pedestrianSoundGroup);
        if (group == null) return;
        boolean disabled = prefs.getBoolean("disablePedestrianSound", false);
        group.check(disabled ? R.id.pedestrianSoundOn : R.id.pedestrianSoundOff);
        group.setOnCheckedChangeListener((g, checkedId) -> {
            if (syncingSettingUi) return;
            boolean off = (checkedId == R.id.pedestrianSoundOn);
            prefs.edit().putBoolean("disablePedestrianSound", off).apply();
            Log.i("$$$ Advance pedestrian $$$", off ? "DISABLED (muted)" : "ENABLED");
        });
    }

    /**
     * Сегмент-контрол «Forced EV». В отличие от звука пешеходов команду шлём СРАЗУ при переключении:
     * это режим тяги, пользователь ждёт немедленного эффекта, а не после «Применить».
     */
    private void initForcedEvGroup() {
        RadioGroup group = findViewById(R.id.forcedEvGroup);
        if (group == null) return;
        boolean on = prefs.getBoolean("forcedEv", false);
        group.check(on ? R.id.forcedEvOn : R.id.forcedEvOff);
        group.setOnCheckedChangeListener((g, checkedId) -> {
            if (syncingSettingUi) return;
            boolean enabled = (checkedId == R.id.forcedEvOn);
            prefs.edit().putBoolean("forcedEv", enabled).apply();
            sendForcedEv(enabled);
            Log.i("$$$ Advance forcedEV $$$", enabled ? "ON" : "OFF");
        });
    }

    /** Немедленно применить форсированный EV через SetModesService. */
    private void sendForcedEv(boolean on) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance forcedEV $$$", "SetModesService не забинден");
            return;
        }
        try {
            GlobalVars.serviceMessenger.send(Message.obtain(null, MSG_APPLY_FORCED_EV, on ? 1 : 0, 0));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
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


    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter("ru.big.town.anative.LUX_UPDATE");
        registerReceiver(luxReceiver, filter, RECEIVER_EXPORTED);
        registerReceiver(modeSyncReceiver, new IntentFilter("ru.big.town.anative.MODE_SYNCED"), RECEIVER_EXPORTED);
        registerReceiver(settingSyncReceiver, new IntentFilter("ru.big.town.anative.SETTING_SYNCED"),
                "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE", null, RECEIVER_EXPORTED);
        if (BuildConfig.IS_FULL) {
            registerReceiver(apolloReceiver, new IntentFilter(ACTION_APOLLO_TLC_UPDATE),
                    NATIVE_BIND_PERMISSION, null, RECEIVER_EXPORTED);
        }
        Intent req = new Intent("ru.big.town.anative.REQUEST_LUX_UPDATE");
        req.setPackage("ru.big.town.anative");
        sendBroadcast(req);
        if (currentSection == 3) requestApolloState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(luxReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(modeSyncReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(settingSyncReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(apolloReceiver); } catch (Exception ignored) {}
    }
}
