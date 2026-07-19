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

    // Навигация: 0 главный экран, 1 режимы+безопасность, 2 разделение экрана, 3 комфорт,
    //            4 команды, 5 кнопки на руле, 6 другое
    private TextView navMainScreen, navCustomCommands, navDriveModes, navSplitScreen, navComfort, navSteeringButtons, navOther;
    private View pageMainScreen, pageCustomCommands, pageDriveModes, pageSplitScreen, pageComfort, pageSteeringButtons, pageOther;
    // Заголовок раздела в верхней панели (на одной строке с «Применить»)
    private TextView sectionTitle;
    private static final String[] SECTION_TITLES = {
            "Главный экран", "Режимы вождения и безопасность", "Разделение экрана", "Комфорт",
            "Собственные команды", "Кнопки на руле", "Другое"
    };

    // Кнопки на руле (перенос с экрана «На кнопку»)
    private EditText steerEditor1, steerEditor2;
    private Button steerApply1, steerApply2, steerSave;
    static final int MSG_APPLY_STAR_BUTTON = 2;

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
        navMainScreen     = findViewById(R.id.navMainScreen);
        navCustomCommands = findViewById(R.id.navCustomCommands);
        navDriveModes     = findViewById(R.id.navDriveModes);
        navSplitScreen    = findViewById(R.id.navSplitScreen);
        navComfort        = findViewById(R.id.navComfort);
        navSteeringButtons = findViewById(R.id.navSteeringButtons);
        navOther          = findViewById(R.id.navOther);
        pageMainScreen     = findViewById(R.id.pageMainScreen);
        pageCustomCommands = findViewById(R.id.pageCustomCommands);
        pageDriveModes     = findViewById(R.id.pageDriveModes);
        pageSplitScreen    = findViewById(R.id.pageSplitScreen);
        pageComfort        = findViewById(R.id.pageComfort);
        pageSteeringButtons = findViewById(R.id.pageSteeringButtons);
        pageOther          = findViewById(R.id.pageOther);
        navMainScreen.setOnClickListener(v -> setSection(0));
        navDriveModes.setOnClickListener(v -> setSection(1));
        navSplitScreen.setOnClickListener(v -> setSection(2));
        navComfort.setOnClickListener(v -> setSection(3));
        navCustomCommands.setOnClickListener(v -> setSection(4));
        navSteeringButtons.setOnClickListener(v -> setSection(5));
        navOther.setOnClickListener(v -> setSection(6));
        setSection(0);

        // LIGHT: скрываем разделы «Разделение экрана» (2) и «Кнопки на руле» (5) — это split/VD и Frida-руль.
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
        if (BuildConfig.IS_FULL) {
            initSplitScreen();
            initAppDpiList();
        }

        // Раздел «Режимы вождения и безопасность» (перенесено с главного экрана)
        initModeRadios();
        initModeEnableToggles();
        initCheckBox34();
        initPedestrianSoundGroup();

        // Раздел «Комфорт»: автосвет + сервисный режим дворников
        initAutoLight();

        Switch switchWiperCold = findViewById(R.id.switchWiperCold);
        switchWiperCold.setChecked(prefs.getBoolean("wiperColdMode", false));
        switchWiperCold.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("wiperColdMode", checked).apply());

        // Раздел «Другое»: тоггл «Режим отладки»
        Switch switchDebugMode = findViewById(R.id.switchDebugMode);
        switchDebugMode.setChecked(prefs.getBoolean("debugMode", false));
        switchDebugMode.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("debugMode", checked).apply());

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
        SplitStore.save(prefs, list);
        renderSplitPresets();
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
                if (idx < l2.size()) { l2.get(idx).l = pkg; l2.get(idx).ll = label; SplitStore.save(prefs, l2); renderSplitPresets(); }
            }));
            rb.setOnClickListener(v -> showAppPicker("Приложение справа", (pkg, label) -> {
                java.util.List<SplitStore.Preset> l2 = SplitStore.load(prefs);
                if (idx < l2.size()) { l2.get(idx).r = pkg; l2.get(idx).rl = label; SplitStore.save(prefs, l2); renderSplitPresets(); }
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
                    if (idx < l2.size() && l2.get(idx).ratio != pos) { l2.get(idx).ratio = pos; SplitStore.save(prefs, l2); }
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });

            del.setOnClickListener(v -> {
                java.util.List<SplitStore.Preset> l2 = SplitStore.load(prefs);
                if (idx < l2.size()) { l2.remove(idx); SplitStore.save(prefs, l2); renderSplitPresets(); }
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

    /** Переключение разделов (0 главный экран, 1 режимы, 2 разделение экрана, 3 комфорт, 4 команды, 5 кнопки на руле, 6 другое). */
    private void setSection(int index) {
        if (sectionTitle != null && index >= 0 && index < SECTION_TITLES.length)
            sectionTitle.setText(SECTION_TITLES[index]);
        if (pageMainScreen != null)      pageMainScreen.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        if (pageDriveModes != null)      pageDriveModes.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (pageSplitScreen != null)     pageSplitScreen.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (pageComfort != null)         pageComfort.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        if (pageCustomCommands != null)  pageCustomCommands.setVisibility(index == 4 ? View.VISIBLE : View.GONE);
        if (pageSteeringButtons != null) pageSteeringButtons.setVisibility(index == 5 ? View.VISIBLE : View.GONE);
        if (pageOther != null)           pageOther.setVisibility(index == 6 ? View.VISIBLE : View.GONE);
        if (navMainScreen != null)       navMainScreen.setSelected(index == 0);
        if (navDriveModes != null)       navDriveModes.setSelected(index == 1);
        if (navSplitScreen != null)      navSplitScreen.setSelected(index == 2);
        if (navComfort != null)          navComfort.setSelected(index == 3);
        if (navCustomCommands != null)   navCustomCommands.setSelected(index == 4);
        if (navSteeringButtons != null)  navSteeringButtons.setSelected(index == 5);
        if (navOther != null)            navOther.setSelected(index == 6);
    }

    // -------------------------------------------------------------------------
    // Кнопки на руле (перенос с экрана «На кнопку»)
    // -------------------------------------------------------------------------

    private void initSteeringButtons() {
        steerEditor1 = findViewById(R.id.rawCanCodesStarButton1);
        steerEditor2 = findViewById(R.id.rawCanCodesStarButton2);
        steerApply1  = findViewById(R.id.buttonApplyStarButton1);
        steerApply2  = findViewById(R.id.buttonApplyStarButton2);
        steerSave    = findViewById(R.id.buttonSaveStarButton);

        if (steerEditor1 != null) {
            steerEditor1.setText(prefs.getString("customCommandStarButton1", ""));
            steerEditor1.addTextChangedListener(new HexFormatter(steerEditor1, steerApply1));
        }
        if (steerEditor2 != null) {
            steerEditor2.setText(prefs.getString("customCommandStarButton2", ""));
            steerEditor2.addTextChangedListener(new HexFormatter(steerEditor2, steerApply2));
        }
    }

    /** Форматирует hex-команды (пробелы + строки по 10 байт) и валидирует по длине % 31. */
    private class HexFormatter implements TextWatcher {
        private final EditText editor;
        private final Button applyBtn;
        private boolean isFormatting = false;

        HexFormatter(EditText editor, Button applyBtn) {
            this.editor = editor;
            this.applyBtn = applyBtn;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (isFormatting) return;
            isFormatting = true;

            String filtered = s.toString().toLowerCase().replaceAll("[^0-9a-f,\n]", "");
            StringBuilder formatted = new StringBuilder();
            for (String line : filtered.split("\n")) {
                for (int j = 0; j < line.length(); j++) {
                    if (j % 2 == 0) formatted.append(" ");
                    formatted.append(line.charAt(j));
                    if (j >= 19) formatted.append("\n");
                }
            }

            boolean valid = formatted.length() % 31 == 0;
            editor.setBackgroundColor(valid ? Color.WHITE : 0xffffafaf);
            if (applyBtn != null) {
                applyBtn.setEnabled(valid);
                applyBtn.setTextColor(valid ? Color.WHITE : Color.GRAY);
            }
            if (steerSave != null) {
                steerSave.setEnabled(valid);
                steerSave.setTextColor(valid ? Color.WHITE : Color.GRAY);
            }

            editor.removeTextChangedListener(this);
            editor.setText(formatted.toString());
            editor.setSelection(formatted.length());
            editor.addTextChangedListener(this);
            isFormatting = false;
        }
    }

    public void onButtonClickApplyStarButton1(View v) { sendStarButton(1); }
    public void onButtonClickApplyStarButton2(View v) { sendStarButton(2); }
    public void onButtonClickCleanStarButton1(View v) { if (steerEditor1 != null) steerEditor1.setText(""); }
    public void onButtonClickCleanStarButton2(View v) { if (steerEditor2 != null) steerEditor2.setText(""); }

    public void onButtonClickSaveStarButton(View v) {
        prefs.edit()
                .putString("customCommandStarButton1", steerEditor1 != null ? steerEditor1.getText().toString() : "")
                .putString("customCommandStarButton2", steerEditor2 != null ? steerEditor2.getText().toString() : "")
                .apply();
        Log.i("$$$ Advance steer $$$", "saved star button commands");
    }

    /** Отправляет команду руля (which=1|2) в SetModesService через мессенджер MainActivity. */
    private void sendStarButton(int which) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance steer $$$", "SetModesService не забинден");
            return;
        }
        try {
            Message msg = Message.obtain(null, MSG_APPLY_STAR_BUTTON, which, 0);
            msg.replyTo = GlobalVars.clientMessenger;
            GlobalVars.serviceMessenger.send(msg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
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
