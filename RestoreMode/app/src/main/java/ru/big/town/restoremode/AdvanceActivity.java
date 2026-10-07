package ru.big.town.restoremode;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class AdvanceActivity extends AppCompatActivity {
    static final String EXTRA_SECTION = "settingsSection";
    static final int SECTION_VOICE = 7;
    static final int SECTION_SCENARIOS = 8;
    private SettingsList settingsList;
    private View bindingRoot;
    private String commandDraft;
    private int commandCountDraft;
    private final java.util.Map<Integer, SettingsList.Row> staticRows = new java.util.HashMap<>();
    private VoiceSettingsPage voiceSettings;
    private ScenarioSettingsPage scenarioSettings;
    private EditText canCommandsEditor;
    private ImageButton buttonBack;
    private NumberPicker pickerCustomCommandCount;

    private final List<ImageButton> deleteButtons = new ArrayList<>();

    private TextView navMainScreen,
            navCustomCommands,
            navDriveModes,
            navSplitScreen,
            navApolloTech,
            navSteeringButtons,
            navOther,
            navVoiceControl,
            navScenarios;

    private TextView sectionTitle;

    private static final String PREF_SHOW_CUSTOM_COMMANDS = "showCustomCommands";
    private SettingsSection currentSection = SettingsSection.MAIN;

    private static final long SYSTEM_METRICS_INTERVAL_MS = SystemMetricsReader.INTERVAL_MS;
    private TextView textRamStatus, textCpuStatus, textHookStatus;
    private boolean activityResumed;
    private volatile boolean systemMetricsActive;
    private volatile long systemMetricsGeneration;
    private final SystemMetricsReader systemMetricsReader = new SystemMetricsReader(this);
    private final ExecutorService systemMetricsExecutor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "VoyahTune-system-metrics");
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    });

    private SettingsList steeringActions;
    private int selectedSteeringTab = R.id.settingsStarTab;

    private SharedPreferences preferences;

    private RadioGroup autoLightGroup;
    private TextView textSensorLevel;
    private CheckBox checkBox34;

    static final int MSG_AUTO_LIGHT_ENABLE = 10;
    static final int MSG_AUTO_LIGHT_DISABLE = 11;
    static final int MSG_APPLY_DRIVE_MODES = 1;
    static final int MSG_RESULT = 4;
    static final int MSG_REBOOT = 22;
    static final int MSG_FLOATING_BACK = 24;
    static final int MSG_FLOATING_BACK_SIDE = 25;
    static final int MSG_GRANT_INSTALL = 26;
    static final int MSG_CLOSE_ALL = 27;
    static final int MSG_SET_THEME = 28;
    static final int MSG_APPLY_SUSPENSION_MAINTENANCE = 37;
    static final int MSG_APPLY_FORCED_EV = 35;
    private static final String NATIVE_PACKAGE = "ru.big.town.anative";
    private static final int LIGHT_DIAGNOSTICS_WATCH = 1;
    private static final int LIGHT_DIAGNOSTICS_UPDATE = 2;
    private static final int LIGHT_DIAGNOSTICS_UNKNOWN = Integer.MIN_VALUE;
    private static final String[] LIGHT_DIAGNOSTICS_LABELS = {
        "SWReason",
        "RSM · внешняя освещённость",
        "RSM · освещённость впереди",
        "RSM · ИК-освещённость",
        "CarSignal · уровень света",
        "CarSignal · PAS уровень света",
        "Android · освещённость (лк)"
    };
    private final TextView[] lightDiagnosticsRows = new TextView[7];
    private boolean lightDiagnosticsActive;
    private boolean lightDiagnosticsBound;
    private int lightDiagnosticsSession;
    private SensorManager lightSensorManager;
    private final SensorEventListener androidLightListener =
            new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (lightDiagnosticsActive
                            && event.values.length > 0
                            && lightDiagnosticsRows[6] != null) {
                        lightDiagnosticsRows[6].setText(
                                LIGHT_DIAGNOSTICS_LABELS[6]
                                        + ": "
                                        + String.format(
                                                Locale.getDefault(), "%.1f", event.values[0]));
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };
    private final Messenger lightDiagnosticsClient =
            new Messenger(
                    new Handler(Looper.getMainLooper()) {
                        @Override
                        public void handleMessage(Message message) {
                            if (message.what == LIGHT_DIAGNOSTICS_UPDATE) {
                                int[] values = message.getData().getIntArray("values");
                                if (values != null
                                        && values.length == 6
                                        && message.arg1 == lightDiagnosticsSession
                                        && lightDiagnosticsActive) {
                                    showLightDiagnostics(values);
                                }
                            } else {
                                super.handleMessage(message);
                            }
                        }
                    });
    private final ServiceConnection lightDiagnosticsConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    if (!lightDiagnosticsActive || !lightDiagnosticsBound) {
                        return;
                    }
                    Message watch = Message.obtain(null, LIGHT_DIAGNOSTICS_WATCH);
                    watch.arg1 = lightDiagnosticsSession;
                    watch.replyTo = lightDiagnosticsClient;
                    try {
                        new Messenger(binder).send(watch);
                    } catch (RemoteException e) {
                        Log.w("LightDiagnostics", "Watch failed", e);
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    int[] unknown = new int[6];
                    java.util.Arrays.fill(unknown, LIGHT_DIAGNOSTICS_UNKNOWN);
                    showLightDiagnostics(unknown);
                }
            };

    private static final String ACTION_BATTERY_HEAT_AUTO_CHANGED =
            "ru.big.town.anative.BATTERY_HEAT_AUTO_CHANGED";
    private static final String EXTRA_BATTERY_HEAT_AUTO_ENABLED = "autoEnabled";
    private static final String ACTION_MODE_REMEMBER_CHANGED =
            "ru.big.town.anative.MODE_REMEMBER_CHANGED";
    private static final String EXTRA_MODE_KEY = "modeKey";
    private static final String EXTRA_REMEMBER_LAST = "rememberLast";

    // Apollo Tech keeps only the individual feature targets.
    private Switch switchApolloTlc,
            switchApolloTrafficLights,
            switchApolloTrafficSigns,
            switchApolloSpeedSigns,
            switchApolloSpeedWarning;
    private RadioGroup apolloSpeedModeGroup;
    private View apolloSpeedOptionsContainer;
    private RadioGroup apolloGreenSoundGroup;
    private View apolloGreenSoundContainer;

    private Button buttonApplyAdvance;
    private ProgressBar applyProgressAdvance;
    private boolean applying = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable applyTimeout = () -> setApplying(false);
    private final Runnable systemMetricsTick = this::sampleSystemMetrics;

    private final Messenger applyClient =
            new Messenger(
                    new Handler(Looper.getMainLooper()) {
                        @Override
                        public void handleMessage(Message message) {
                            if (message.what == MSG_RESULT) {
                                setApplying(false);
                            } else {
                                super.handleMessage(message);
                            }
                        }
                    });

    private final BroadcastReceiver luxReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int sensorLevel = intent.getIntExtra("sensorLevel", -1);
                    if (textSensorLevel != null) {
                        textSensorLevel.setText(
                                sensorLevel >= 0 ? "Датчик: " + sensorLevel : "Датчик: —");
                    }
                }
            };

    private boolean syncingModeUi;
    private final BroadcastReceiver modeSyncReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String mode = intent.getStringExtra("mode");
                    if (mode == null || mode.isEmpty()) {
                        return;
                    }
                    String modeKey = intent.getStringExtra("modeKey");
                    if (modeKey == null) {
                        modeKey =
                                intent.getBooleanExtra("isEnergy", false) ? "energy" : "driveMode";
                    }
                    String rememberKey =
                            "energy".equals(modeKey)
                                    ? "energyRememberLast"
                                    : "recycle".equals(modeKey)
                                            ? "recycleRememberLast"
                                            : "driveRememberLast";
                    if (!preferences.getBoolean(rememberKey, true)) {
                        return;
                    }
                    int groupId =
                            "energy".equals(modeKey)
                                    ? R.id.energy_modes_group
                                    : "recycle".equals(modeKey)
                                            ? R.id.recycle_modes_group
                                            : R.id.drive_modes_group;
                    RadioGroup g = settingView(groupId);
                    syncingModeUi = true;
                    try {
                        if (g != null) {
                            checkRadioByTag(g, mode);
                        }
                    } finally {
                        syncingModeUi = false;
                    }
                }
            };

    private boolean syncingSettingUi;
    private final BroadcastReceiver settingSyncReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String key = intent.getStringExtra("key");
                    if (key == null || !intent.hasExtra("value")) {
                        return;
                    }
                    boolean value = intent.getBooleanExtra("value", false);
                    preferences.edit().putBoolean(key, value).apply();
                    syncingSettingUi = true;
                    try {
                        if ("forcedEv".equals(key)) {
                            RadioGroup group = settingView(R.id.forcedEvGroup);
                            if (group != null) {
                                group.check(value ? R.id.forcedEvOn : R.id.forcedEvOff);
                            }
                        } else if ("autoLight".equals(key)) {
                            if (autoLightGroup != null) {
                                autoLightGroup.check(value ? R.id.autoLightOn : R.id.autoLightOff);
                            }
                        } else if ("suspensionMaintenance".equals(key)) {
                            Switch toggle = settingView(R.id.switchSuspensionMaintenance);
                            if (toggle != null) {
                                toggle.setChecked(value);
                            }
                        } else if ("disablePedestrianSound".equals(key)) {
                            RadioGroup group = settingView(R.id.pedestrianSoundGroup);
                            if (group != null) {
                                group.check(
                                        value ? R.id.pedestrianSoundOn : R.id.pedestrianSoundOff);
                            }
                        }
                    } finally {
                        syncingSettingUi = false;
                    }
                }
            };

    static final String[][] EXAMPLE_COMMANDS = {
        {"64 08 80 00 00 00 00 00 00 03", "обогрев руля вкл"},
        {"64 08 40 00 00 00 00 00 00 03", "обогрев руля выкл"},
        {"65 08 00 00 c1 c0 20 00 00 00", "обогрев заднего стекла вкл"},
        {"65 08 00 00 c1 c0 10 00 00 00", "обогрев заднего стекла выкл"},
        {"7a 08 00 00 00 00 01 00 00 00", "автодальний вкл"},
        {"7a 08 00 00 00 00 02 00 00 00", "автодальний выкл"},
        {"68 08 02 00 00 f0 2c 54 08 00", "форсе EV вкл"},
        {"68 08 02 00 00 f0 2c 24 08 00", "форсе EV выкл"},
    };

    public void onButtonClickFinish(View v) {
        finishWithCustomCommands();
    }

    private void finishWithCustomCommands() {
        if (!saveCustomCommands()) {
            return;
        }
        Intent intent = new Intent();
        intent.putExtra("customCommand", commandDraft);
        intent.putExtra("customCommandCount", commandCountDraft);
        setResult(RESULT_OK, intent);
        finish();
    }

    private boolean saveCustomCommands() {
        if (!commandsValid()) {
            Log.w("$$$ Advance commands $$$", "Команды не сохранены: неверный формат");
            return false;
        }
        preferences
                .edit()
                .putString("customCommand", commandDraft)
                .putInt("customCommandCount", commandCountDraft)
                .apply();
        return true;
    }

    public void onButtonClickClean(View v) {
        commandDraft = "";
        if (canCommandsEditor != null) {
            canCommandsEditor.setText("");
        }
    }

    private void refreshCanStatus() {
        TextView status = settingView(R.id.settingsCanStatus);
        if (status == null || canCommandsEditor == null) {
            return;
        }
        boolean valid =
                java.util.Arrays.stream(commandDraft.split("\\n"))
                        .allMatch(
                                line ->
                                        line.trim().isEmpty()
                                                || SteeringCanCommandPolicy.isValid(line));
        status.setText(
                commandDraft.trim().isEmpty()
                        ? "Пока нет команд"
                        : valid
                                ? "Формат корректен"
                                : "Проверьте строки: в каждой должно быть ровно 10 байт");
        status.setTextColor(valid ? 0xffa3d3bb : 0xfff4b5b5);
    }

    private void buildExampleButtons() {
        LinearLayout container = settingView(R.id.examplesContainer);
        if (container == null) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String[] pair : EXAMPLE_COMMANDS) {
            final String hex = pair[0];
            final String label = pair[1];
            View row = inflater.inflate(R.layout.item_command, container, false);
            Button commandButton = row.findViewById(R.id.cmdButton);
            ImageButton deleteButton = row.findViewById(R.id.cmdDelete);
            android.text.SpannableString example =
                    new android.text.SpannableString(label + "    ＋\n" + hex);
            int hexStart = example.toString().indexOf('\n') + 1;
            example.setSpan(
                    new android.text.style.AbsoluteSizeSpan(12, true),
                    hexStart,
                    example.length(),
                    0);
            example.setSpan(
                    new android.text.style.ForegroundColorSpan(0xff8b9fb9),
                    hexStart,
                    example.length(),
                    0);
            example.setSpan(
                    new android.text.style.TypefaceSpan("monospace"),
                    hexStart,
                    example.length(),
                    0);
            commandButton.setText(example);
            commandButton.setOnClickListener(v -> insertCommand(hex));
            deleteButton.setTag(hex.replaceAll("[^0-9a-fA-F]", "").toLowerCase());
            deleteButton.setOnClickListener(v -> removeCommand(hex));
            deleteButtons.add(deleteButton);
            SettingsDesign.styleTree(row);
            container.addView(row);
        }
        updateDeleteButtons();
    }

    private void updateDeleteButtons() {
        if (canCommandsEditor == null) {
            return;
        }
        Set<String> present = new HashSet<>();
        for (String line : commandDraft.split("\n")) {
            String normalizedCommand = line.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
            if (!normalizedCommand.isEmpty()) {
                present.add(normalizedCommand);
            }
        }
        for (ImageButton deleteButton : deleteButtons) {
            String target = (String) deleteButton.getTag();
            boolean enabled = target != null && present.contains(target);
            deleteButton.setEnabled(enabled);
            deleteButton.setAlpha(enabled ? 1f : 0.3f);
        }
    }

    private void insertCommand(String hex) {
        String commands = commandDraft;
        if (commands.length() > 0 && !commands.endsWith("\n")) {
            commands = commands + "\n";
        }
        canCommandsEditor.setText(commands + hex + "\n");
        canCommandsEditor.setSelection(canCommandsEditor.getText().length());
    }

    private void removeCommand(String hex) {
        String target = hex.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
        String[] lines = commandDraft.split("\n");
        StringBuilder builder = new StringBuilder();
        boolean removed = false;
        for (String line : lines) {
            String normalizedCommand = line.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
            if (normalizedCommand.isEmpty()) {
                continue;
            }
            if (!removed && normalizedCommand.equals(target)) {
                removed = true;
                continue;
            }
            builder.append(normalizedCommand).append("\n");
        }
        canCommandsEditor.setText(builder.toString());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_advance);
        applyWindowInsets();
        preferences = getSharedPreferences("DrivePreferences", MODE_PRIVATE);
        settingsList = settingView(R.id.settingsList);
        settingsList.onRelease(this::releaseSettingsRow);
        settingsList.onVisibilityChanged(
                () ->
                        settingsList.post(
                                () -> {
                                    updateSystemMetricsPolling();
                                    updateLightDiagnosticsBinding();
                                }));
        if (savedInstanceState != null) {
            settingsList.restoreDrafts(savedInstanceState.getBundle("settingsDrafts"));
        }
        if (savedInstanceState != null) {
            selectedSteeringTab = savedInstanceState.getInt("steeringTab", R.id.settingsStarTab);
        }
        Intent intent = getIntent();
        commandDraft =
                savedInstanceState != null
                        ? savedInstanceState.getString("commandDraft", "")
                        : intent.getStringExtra("customCommand");
        if (commandDraft == null) {
            commandDraft = preferences.getString("customCommand", "");
        }
        commandCountDraft =
                savedInstanceState != null
                        ? savedInstanceState.getInt("commandCount", 1)
                        : intent.getIntExtra(
                                "customCommandCount", preferences.getInt("customCommandCount", 1));
        buttonApplyAdvance = settingView(R.id.buttonApplyAdvance);
        applyProgressAdvance = settingView(R.id.applyProgressAdvance);
        buttonBack = settingView(R.id.buttonBack);
        sectionTitle = settingView(R.id.sectionTitle);
        getOnBackPressedDispatcher()
                .addCallback(
                        this,
                        new OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                finishWithCustomCommands();
                            }
                        });
        navMainScreen = settingView(R.id.navMainScreen);
        navCustomCommands = settingView(R.id.navCustomCommands);
        navDriveModes = settingView(R.id.navDriveModes);
        navSplitScreen = settingView(R.id.navSplitScreen);
        navApolloTech = settingView(R.id.navApolloTech);
        navSteeringButtons = settingView(R.id.navSteeringButtons);
        navOther = settingView(R.id.navOther);
        navVoiceControl = settingView(R.id.navVoiceControl);
        navScenarios = settingView(R.id.navScenarios);
        navMainScreen.setOnClickListener(v -> setSection(SettingsSection.MAIN));
        navDriveModes.setOnClickListener(v -> setSection(SettingsSection.VEHICLE));
        navSplitScreen.setOnClickListener(v -> setSection(SettingsSection.APPS));
        navCustomCommands.setOnClickListener(v -> setSection(SettingsSection.CAN));
        navApolloTech.setOnClickListener(v -> setSection(SettingsSection.APOLLO));
        navSteeringButtons.setOnClickListener(v -> setSection(SettingsSection.STEERING));
        navOther.setOnClickListener(v -> setSection(SettingsSection.OTHER));
        navVoiceControl.setOnClickListener(v -> setSection(SettingsSection.VOICE));
        navScenarios.setOnClickListener(v -> setSection(SettingsSection.SCENARIOS));
        navCustomCommands.setVisibility(
                preferences.getBoolean(PREF_SHOW_CUSTOM_COMMANDS, false)
                        ? View.VISIBLE
                        : View.GONE);
        SettingsDesign.install(this);
        int initial =
                savedInstanceState != null
                        ? savedInstanceState.getInt("settingsSection", 0)
                        : intent.getIntExtra(EXTRA_SECTION, 0);
        setSection(SettingsSection.fromIdentifier(initial));
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("commandDraft", commandDraft);
        state.putInt("commandCount", commandCountDraft);
        state.putInt("settingsSection", currentSection.identifier);
        state.putInt("steeringTab", selectedSteeringTab);
        state.putBundle("settingsDrafts", settingsList.saveDrafts());
        super.onSaveInstanceState(state);
    }

    private <T extends View> T settingView(int id) {
        return bindingRoot == null ? super.findViewById(id) : bindingRoot.findViewById(id);
    }

    private View inflateSettingsRow(int layout, android.view.ViewGroup parent) {
        View root = LayoutInflater.from(this).inflate(layout, parent, false);
        bindingRoot = root;
        try {
            bindSettingsRow();
            SettingsDesign.bindRow(this, root);
        } finally {
            bindingRoot = null;
        }
        if (layout == R.layout.settings_apps_fullscreen_description) {
            return appSelectionPanel(root, true, false);
        }
        if (layout == R.layout.settings_apps_add_fullscreen_app) {
            return appSelectionPanel(root, false, true);
        }
        if (layout == R.layout.settings_main_add_shortcut) {
            return appSelectionPanel(root, AppShortcutStore.load(preferences).isEmpty(), true);
        }
        return root;
    }

    private void bindSettingsRow() {
        switch (currentSection) {
            case MAIN:
                {
                    bindMainWidgetSwitches();
                    bindEnergyAppearance();
                    bindTripHistory();
                    bindMainGrid();
                    bindShowSwitch(R.id.switchShowTaskManagerTile, "showTaskManagerTile", true);
                    View addDial = settingView(R.id.buttonAddDialWidget);
                    if (addDial != null) {
                        addDial.setOnClickListener(view -> addDialWidget());
                    }
                    break;
                }
            case VEHICLE:
                {
                    bindBatteryHeating();
                    bindWiperColdMode();
                    bindMediaPause();
                    initModeRadios();
                    initModeEnableToggles();
                    initModeRememberLastToggles();
                    initFragranceSettings();
                    initPedestrianSoundGroup();
                    initForcedEvGroup();
                    if (settingView(R.id.checkBox34) != null) {
                        initCheckBox34();
                    }
                    if (settingView(R.id.autoLightGroup) != null) {
                        initAutoLight();
                    }
                    if (settingView(R.id.switchSuspensionMaintenance) != null) {
                        initSuspensionMaintenance();
                    }
                    break;
                }
            case APPS:
                {
                    if (settingView(R.id.dockOverrideBlock) != null) {
                        initDockOverride();
                    }
                    TextView splitCount = settingView(R.id.settingsSplitCount);
                    if (splitCount != null) {
                        splitCount.setText("Сплитов: " + SplitStore.load(preferences).size());
                    }
                    break;
                }
            case APOLLO:
                {
                    initApolloTech();
                    break;
                }
            case CAN:
                {
                    if (settingView(R.id.rawCanCodes) != null) {
                        bindCommandEditor();
                    }
                    break;
                }
            case STEERING:
                {
                    if (settingView(R.id.settingsSteeringTabs) != null) {
                        initSteeringButtons();
                    }
                    break;
                }
            case OTHER:
                {
                    bindUpdates();
                    bindDiagnostics();
                    bindKeyboard();
                    bindCustomCommandsVisibility();
                    bindAutoLaunch();
                    bindFloatingBack();
                    bindTheme();
                    bindLocationPermission();
                    bindFloatingBackPosition();
                    if (settingView(R.id.textEngPassword) != null) {
                        showEngineeringPassword();
                    }
                    if (settingView(R.id.textRamStatus) != null) {
                        textRamStatus = settingView(R.id.textRamStatus);
                        textCpuStatus = settingView(R.id.textCpuStatus);
                        textHookStatus = settingView(R.id.textHookStatus);
                    }
                    break;
                }
            default:
                break;
        }
    }

    private void bindMainWidgetSwitches() {
        bindShowSwitch(R.id.switchShowTripTimer, "showTripTimer", true);
        bindShowSwitch(R.id.switchShowPowerHold, "showPowerHold", true);
        bindShowSwitch(R.id.switchShowWashMode, "showWashMode", true);
        bindShowSwitch(R.id.switchShowAutoLight, "showAutoLight", true);
        bindShowSwitch(R.id.switchShowPedestrian, "showPedestrian", true);
        bindShowSwitch(R.id.switchShowBatteryHeat, "showBatteryHeat", true);
        bindShowSwitch(R.id.switchShowVoiceCommand, "showVoiceCommand", false);
        bindShowSwitch(R.id.switchShowScenariosCard, "showScenariosCard", true);
        bindShowSwitch(R.id.switchShowForcedEv, "showForcedEv", false);
        bindShowSwitch(R.id.switchShowSuspensionMaintenance, "showSuspensionMaintenance", false);
        bindShowSwitch(
                R.id.switchShowLaunchAppsWidget,
                "showLaunchAppsWidget",
                false,
                R.id.launchAppsSizeRow);
        bindTileSizeSpinners(
                R.id.launchAppsSettingWidth,
                R.id.launchAppsSettingHeight,
                TileSizeStore.LAUNCH_APPS_WIDGET_ID,
                TileSizeStore.LAUNCH_APPS_DEFAULT_WIDTH,
                TileSizeStore.LAUNCH_APPS_DEFAULT_HEIGHT);
        bindShowSwitch(
                R.id.switchShowSuspensionWidget,
                "showSuspensionWidget",
                false,
                R.id.suspensionSizeRow);
        bindTileSizeSpinners(
                R.id.suspensionSettingWidth,
                R.id.suspensionSettingHeight,
                TileSizeStore.SUSPENSION_WIDGET_ID,
                TileSizeStore.SUSPENSION_DEFAULT_WIDTH,
                TileSizeStore.SUSPENSION_DEFAULT_HEIGHT);
        bindShowSwitch(R.id.switchShowCpu, "show_cpuWidget", false, R.id.CpuSizeRow);
        bindTileSizeSpinners(
                R.id.CpuSettingWidth, R.id.CpuSettingHeight, SystemWidgetLayout.CPU, 1, 1);
        bindShowSwitch(R.id.switchShowRam, "show_ramWidget", false, R.id.RamSizeRow);
        bindTileSizeSpinners(
                R.id.RamSettingWidth, R.id.RamSettingHeight, SystemWidgetLayout.RAM, 1, 1);
        bindShowSwitch(R.id.switchShowClearMemory, "show_clearMemoryWidget", false, 0);
        bindShowSwitch(R.id.switchShowEnergy, "show_energyWidget", false, R.id.EnergySizeRow);
        bindTileSizeSpinners(
                R.id.EnergySettingWidth, R.id.EnergySettingHeight, "energyWidget", 8, 4);
        bindShowSwitch(
                R.id.switchShowEnergyConsumption,
                "show_energyConsumptionWidget",
                false,
                R.id.EnergyConsumptionSizeRow);
        bindTileSizeSpinners(
                R.id.EnergyConsumptionSettingWidth,
                R.id.EnergyConsumptionSettingHeight,
                "energyConsumptionWidget",
                2,
                2);
        bindShowSwitch(
                R.id.switchShowEnergyTrip, "show_energyTripWidget", false, R.id.EnergyTripSizeRow);
        bindTileSizeSpinners(
                R.id.EnergyTripSettingWidth,
                R.id.EnergyTripSettingHeight,
                "energyTripWidget",
                8,
                2);
        bindShowSwitch(
                R.id.switchShowTirePressure,
                "show_tirePressureWidget",
                false,
                R.id.TirePressureSizeRow);
        bindTileSizeSpinners(
                R.id.TirePressureSettingWidth,
                R.id.TirePressureSettingHeight,
                "tirePressureWidget",
                4,
                4);
        bindShowSwitch(R.id.switchShowOdometer, "show_odometerWidget", false, R.id.OdometerSizeRow);
        bindTileSizeSpinners(
                R.id.OdometerSettingWidth, R.id.OdometerSettingHeight, "odometerWidget", 4, 1);
    }

    private void bindEnergyAppearance() {
        if (settingView(R.id.energyCarColor) == null) {
            return;
        }
        android.widget.Spinner carColor = settingView(R.id.energyCarColor);
        android.widget.ArrayAdapter<String> carColors =
                new android.widget.ArrayAdapter<>(
                        this, R.layout.settings_spinner_item, EnergyWidgetView.COLOR_NAMES);
        carColors.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        carColor.setAdapter(carColors);
        carColor.setSelection(
                java.util.Arrays.asList(EnergyWidgetView.COLORS)
                        .indexOf(
                                EnergyWidgetView.color(
                                        preferences.getString("energyCarColor", "burgundy"))));
        carColor.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        preferences
                                .edit()
                                .putString("energyCarColor", EnergyWidgetView.COLORS[position])
                                .apply();
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
        bindEnergyCapacity(
                R.id.energyBatteryCapacity,
                ru.big.town.common.EnergyWidgetSettings.BATTERY_KEY,
                43);
        bindEnergyCapacity(
                R.id.energyTankCapacity, ru.big.town.common.EnergyWidgetSettings.TANK_KEY, 56);
    }

    private void bindTripHistory() {
        if (settingView(R.id.switchSaveTripHistory) == null) {
            return;
        }
        Switch switchSaveHistory = settingView(R.id.switchSaveTripHistory);
        switchSaveHistory.setChecked(preferences.getBoolean("saveTripHistory", true));
        switchSaveHistory.setOnCheckedChangeListener(
                (button, checked) -> {
                    preferences.edit().putBoolean("saveTripHistory", checked).apply();
                    Intent intent =
                            new Intent("ru.big.town.anative.TRIP_HISTORY")
                                    .setPackage("ru.big.town.anative");
                    intent.putExtra("enabled", checked);
                    sendBroadcast(intent);
                });
    }

    private void bindBatteryHeating() {
        if (settingView(R.id.switchBatteryHeatAuto) == null) {
            return;
        }
        Switch switchBatteryHeat = settingView(R.id.switchBatteryHeatAuto);
        if (switchBatteryHeat != null) {
            switchBatteryHeat.setChecked(preferences.getBoolean("batteryHeatAuto", false));
            switchBatteryHeat.setOnCheckedChangeListener(
                    (b, checked) -> {
                        preferences.edit().putBoolean("batteryHeatAuto", checked).apply();
                        Intent changed =
                                new Intent(ACTION_BATTERY_HEAT_AUTO_CHANGED)
                                        .setPackage(NATIVE_PACKAGE)
                                        .putExtra(EXTRA_BATTERY_HEAT_AUTO_ENABLED, checked);
                        sendBroadcast(changed);
                    });
        }
    }

    private void bindWiperColdMode() {
        if (settingView(R.id.switchWiperCold) == null) {
            return;
        }
        Switch switchWiperCold = settingView(R.id.switchWiperCold);
        switchWiperCold.setChecked(preferences.getBoolean("wiperColdMode", false));
        switchWiperCold.setOnCheckedChangeListener(
                (b, checked) -> preferences.edit().putBoolean("wiperColdMode", checked).apply());
    }

    private void bindMediaPause() {
        if (settingView(R.id.switchPauseMediaOnDoor) == null) {
            return;
        }
        Switch switchPauseMedia = settingView(R.id.switchPauseMediaOnDoor);
        if (switchPauseMedia != null) {
            switchPauseMedia.setChecked(preferences.getBoolean("pauseMediaOnDoor", false));
            switchPauseMedia.setOnCheckedChangeListener(
                    (b, checked) ->
                            preferences.edit().putBoolean("pauseMediaOnDoor", checked).apply());
        }
    }

    private void bindUpdates() {
        if (settingView(R.id.textAppVersion) == null) {
            return;
        }
        TextView textAppVersion = settingView(R.id.textAppVersion);
        textAppVersion.setText(BuildConfig.VERSION_NAME);
        settingView(R.id.buttonOpenUpdates)
                .setOnClickListener(
                        v -> {
                            boolean embedded =
                                    ru.big.town.common.InfrastructureProfile.read(this)
                                            == ru.big.town.common.InfrastructureProfile.OD;
                            Intent updates =
                                    new Intent(Intent.ACTION_MAIN)
                                            .setComponent(
                                                    embedded
                                                            ? new android.content.ComponentName(
                                                                    this, OtaActivity.class)
                                                            : new android.content.ComponentName(
                                                                    "ru.big.town.updater",
                                                                    "ru.big.town.updater.MainActivity"))
                                            .addFlags(
                                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                                            | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                            .putExtra(
                                                    "ru.big.town.updater.OPEN_INITIAL_SCREEN",
                                                    true);
                            try {
                                startActivity(updates);
                            } catch (android.content.ActivityNotFoundException
                                    | SecurityException unavailable) {
                                android.widget.Toast.makeText(
                                                this,
                                                "Обновления недоступны. Установите релиз с"
                                                        + " поддержкой OTA через USB с компьютера.",
                                                android.widget.Toast.LENGTH_LONG)
                                        .show();
                            }
                        });
    }

    private void bindDiagnostics() {
        if (settingView(R.id.switchDebugMode) == null) {
            return;
        }
        Switch switchDebugMode = settingView(R.id.switchDebugMode);
        View debugInformationBlock = settingView(R.id.debugInformationBlock);
        LinearLayout lightRows = settingView(R.id.debugLightSensorRows);
        for (int i = 0; i < lightDiagnosticsRows.length; i++) {
            TextView row = new TextView(this);
            row.setTextColor(Color.WHITE);
            row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 20);
            row.setPadding(0, 4, 0, 4);
            SettingsDesign.styleTree(row);
            lightRows.addView(row);
            lightDiagnosticsRows[i] = row;
        }
        resetLightDiagnostics();
        switchDebugMode.setChecked(preferences.getBoolean("debugMode", false));
        debugInformationBlock.setVisibility(switchDebugMode.isChecked() ? View.VISIBLE : View.GONE);
        switchDebugMode.setOnCheckedChangeListener(
                (b, checked) -> {
                    preferences.edit().putBoolean("debugMode", checked).apply();
                    debugInformationBlock.setVisibility(checked ? View.VISIBLE : View.GONE);
                    updateLightDiagnosticsBinding();
                });
    }

    private void bindMainGrid() {
        if (settingView(R.id.switchFullscreenGrid) == null) {
            return;
        }
        Switch switchFullscreenGrid = settingView(R.id.switchFullscreenGrid);
        NumberPicker pickerFullscreenGridColumns = settingView(R.id.pickerFullscreenGridColumns);
        pickerFullscreenGridColumns.setMinValue(0);
        pickerFullscreenGridColumns.setMaxValue(12);
        pickerFullscreenGridColumns.setTextColor(0xffffffff);
        pickerFullscreenGridColumns.setContentDescription("Колонок с растянутым верхним рядом");
        pickerFullscreenGridColumns.setValue(preferences.getInt("fullscreenGridColumns", 8));
        pickerFullscreenGridColumns.setOnValueChangedListener(
                (picker, oldValue, newValue) ->
                        preferences.edit().putInt("fullscreenGridColumns", newValue).apply());

        switchFullscreenGrid.setChecked(preferences.getBoolean("fullscreenGrid", false));

        pickerFullscreenGridColumns.setEnabled(switchFullscreenGrid.isChecked());
        switchFullscreenGrid.setOnCheckedChangeListener(
                (b, checked) -> {
                    preferences.edit().putBoolean("fullscreenGrid", checked).apply();
                    pickerFullscreenGridColumns.setEnabled(checked);
                });

        NumberPicker tileSpacing = settingView(R.id.pickerTileSpacing);
        tileSpacing.setMinValue(0);
        tileSpacing.setMaxValue(24);
        tileSpacing.setValue(Math.max(0, Math.min(24, preferences.getInt("tileSpacingDp", 4))));
        tileSpacing.setOnValueChangedListener(
                (picker, oldValue, newValue) ->
                        preferences.edit().putInt("tileSpacingDp", newValue).apply());
    }

    private void bindKeyboard() {
        if (settingView(R.id.switchKeyboardEnglish) == null) {
            return;
        }
        Switch switchKeyboardEnglish = settingView(R.id.switchKeyboardEnglish);
        Switch switchKeyboardRussian = settingView(R.id.switchKeyboardRussian);
        if (switchKeyboardEnglish != null && switchKeyboardRussian != null) {
            String keyboardMode =
                    SplitConfigSync.normalizeKeyboardMode(
                            preferences.getString("keyboardMode", "off"));
            switchKeyboardEnglish.setChecked("en".equals(keyboardMode));
            switchKeyboardRussian.setChecked("ru".equals(keyboardMode));
            final boolean[] updatingKeyboardSwitches = {false};
            switchKeyboardEnglish.setOnCheckedChangeListener(
                    (button, checked) -> {
                        if (updatingKeyboardSwitches[0]) {
                            return;
                        }
                        updatingKeyboardSwitches[0] = true;
                        if (checked) {
                            switchKeyboardRussian.setChecked(false);
                        }
                        String mode =
                                checked ? "en" : (switchKeyboardRussian.isChecked() ? "ru" : "off");
                        preferences.edit().putString("keyboardMode", mode).apply();
                        SplitConfigSync.pushKeyboard(this, preferences);
                        updatingKeyboardSwitches[0] = false;
                    });
            switchKeyboardRussian.setOnCheckedChangeListener(
                    (button, checked) -> {
                        if (updatingKeyboardSwitches[0]) {
                            return;
                        }
                        updatingKeyboardSwitches[0] = true;
                        if (checked) {
                            switchKeyboardEnglish.setChecked(false);
                        }
                        String mode =
                                checked ? "ru" : (switchKeyboardEnglish.isChecked() ? "en" : "off");
                        preferences.edit().putString("keyboardMode", mode).apply();
                        SplitConfigSync.pushKeyboard(this, preferences);
                        updatingKeyboardSwitches[0] = false;
                    });
        }
    }

    private void bindCustomCommandsVisibility() {
        if (settingView(R.id.switchShowCustomCommands) == null) {
            return;
        }
        Switch switchShowCustomCommands = settingView(R.id.switchShowCustomCommands);
        switchShowCustomCommands.setChecked(
                preferences.getBoolean(PREF_SHOW_CUSTOM_COMMANDS, false));
        switchShowCustomCommands.setOnCheckedChangeListener(
                (b, checked) -> {
                    preferences.edit().putBoolean(PREF_SHOW_CUSTOM_COMMANDS, checked).apply();
                    navCustomCommands.setVisibility(checked ? View.VISIBLE : View.GONE);
                });
    }

    private void bindAutoLaunch() {
        if (settingView(R.id.switchAutoLaunch) == null) {
            return;
        }
        Switch switchAutoLaunch = settingView(R.id.switchAutoLaunch);
        switchAutoLaunch.setChecked(preferences.getBoolean("autoLaunchOnWake", false));
        switchAutoLaunch.setOnCheckedChangeListener(
                (b, checked) -> preferences.edit().putBoolean("autoLaunchOnWake", checked).apply());
    }

    private void bindFloatingBack() {
        if (settingView(R.id.switchFloatingBack) == null) {
            return;
        }
        Switch switchFloatingBack = settingView(R.id.switchFloatingBack);
        switchFloatingBack.setChecked(preferences.getBoolean("floatingBackButton", false));
        switchFloatingBack.setOnCheckedChangeListener(
                (b, checked) -> {
                    preferences.edit().putBoolean("floatingBackButton", checked).apply();
                    sendFloatingBack(checked);
                });
    }

    private void bindTheme() {
        if (settingView(R.id.themeOverrideGroup) == null) {
            return;
        }
        RadioGroup themeGroup = settingView(R.id.themeOverrideGroup);
        if (themeGroup != null) {
            checkRadioByTag(themeGroup, String.valueOf(preferences.getInt("themeOverride", 0)));
            themeGroup.setOnCheckedChangeListener(
                    (g, id) -> {
                        View c = settingView(id);
                        if (c != null && c.getTag() != null) {
                            int mode = Integer.parseInt(c.getTag().toString());
                            preferences.edit().putInt("themeOverride", mode).apply();
                            sendTheme(mode);
                        }
                    });
        }
    }

    private void bindLocationPermission() {
        if (settingView(R.id.buttonTripLocationPermission) == null) {
            return;
        }
        settingView(R.id.buttonTripLocationPermission)
                .setOnClickListener(v -> TripLocationPermission.openSettings(this));
        settingView(R.id.buttonRequestTripLocation)
                .setOnClickListener(v -> TripLocationPermission.request(this));
        settingView(R.id.buttonDisableTripLocation)
                .setOnClickListener(v -> TripLocationPermission.disable(this));
        settingsList.post(() -> TripLocationPermission.refresh(this));
    }

    private void bindFloatingBackPosition() {
        if (settingView(R.id.floatingBackSideGroup) == null) {
            return;
        }
        RadioGroup sideGroup = settingView(R.id.floatingBackSideGroup);
        if (sideGroup != null) {
            checkRadioByTag(sideGroup, String.valueOf(preferences.getInt("floatingBackSide", 0)));
            sideGroup.setOnCheckedChangeListener(
                    (g, id) -> {
                        View c = settingView(id);
                        if (c != null && c.getTag() != null) {
                            int side = Integer.parseInt(c.getTag().toString());
                            preferences.edit().putInt("floatingBackSide", side).apply();
                            sendFloatingBackSide(side);
                        }
                    });
        }
    }

    private void bindCommandEditor() {
        canCommandsEditor = settingView(R.id.rawCanCodes);
        pickerCustomCommandCount = settingView(R.id.pickerCustomCommandCount);
        pickerCustomCommandCount.setMinValue(1);
        pickerCustomCommandCount.setMaxValue(10);
        pickerCustomCommandCount.setTextColor(0xffffffff);
        pickerCustomCommandCount.setValue(commandCountDraft);
        pickerCustomCommandCount.setOnValueChangedListener(
                (picker, before, after) -> commandCountDraft = after);
        canCommandsEditor.setText(commandDraft);
        canCommandsEditor.addTextChangedListener(
                new TextWatcher() {
                    private boolean formatting;

                    @Override
                    public void beforeTextChanged(
                            CharSequence text, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(
                            CharSequence text, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable value) {
                        if (formatting) {
                            return;
                        }
                        String formatted = formatCommandLines(value.toString());
                        commandDraft = formatted;
                        if (!formatted.contentEquals(value)) {
                            formatting = true;
                            value.replace(0, value.length(), formatted);
                            formatting = false;
                        }
                        canCommandsEditor.setBackgroundResource(
                                commandsValid()
                                        ? R.drawable.settings_code
                                        : R.drawable.settings_code_invalid);
                        refreshCanStatus();
                        updateDeleteButtons();
                    }
                });
        settingView(R.id.settingsValidateCan).setOnClickListener(v -> refreshCanStatus());
        buildExampleButtons();
        refreshCanStatus();
    }

    private static String formatCommandLines(String input) {
        StringBuilder formatted = new StringBuilder();
        String[] lines = input.split("\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (lineIndex > 0) {
                formatted.append('\n');
            }
            String compact = SteeringCanCommandPolicy.compact(lines[lineIndex]);
            for (int start = 0;
                    start < compact.length();
                    start += SteeringCanCommandPolicy.HEX_LENGTH) {
                if (start > 0) {
                    formatted.append('\n');
                }
                int end = Math.min(start + SteeringCanCommandPolicy.HEX_LENGTH, compact.length());
                formatted.append(SteeringCanCommandPolicy.format(compact.substring(start, end)));
            }
        }
        return formatted.toString();
    }

    private boolean commandsValid() {
        return java.util.Arrays.stream(commandDraft.split("\n"))
                .allMatch(line -> line.trim().isEmpty() || SteeringCanCommandPolicy.isValid(line));
    }

    // Asynchronous updates must never retain controls recycled by the viewport.
    private void releaseSettingsRow(View root) {
        if (switchApolloTlc != null
                && root.findViewById(switchApolloTlc.getId()) == switchApolloTlc) {
            switchApolloTlc = null;
        }
        if (switchApolloTrafficLights != null
                && root.findViewById(switchApolloTrafficLights.getId())
                        == switchApolloTrafficLights) {
            switchApolloTrafficLights = null;
        }
        if (switchApolloTrafficSigns != null
                && root.findViewById(switchApolloTrafficSigns.getId())
                        == switchApolloTrafficSigns) {
            switchApolloTrafficSigns = null;
        }
        if (switchApolloSpeedSigns != null
                && root.findViewById(switchApolloSpeedSigns.getId()) == switchApolloSpeedSigns) {
            switchApolloSpeedSigns = null;
        }
        if (apolloSpeedOptionsContainer != null
                && root.findViewById(apolloSpeedOptionsContainer.getId())
                        == apolloSpeedOptionsContainer) {
            apolloSpeedOptionsContainer = null;
        }
        if (apolloSpeedModeGroup != null
                && root.findViewById(apolloSpeedModeGroup.getId()) == apolloSpeedModeGroup) {
            apolloSpeedModeGroup = null;
        }
        if (switchApolloSpeedWarning != null
                && root.findViewById(switchApolloSpeedWarning.getId())
                        == switchApolloSpeedWarning) {
            switchApolloSpeedWarning = null;
        }
        if (apolloGreenSoundGroup != null
                && root.findViewById(apolloGreenSoundGroup.getId()) == apolloGreenSoundGroup) {
            apolloGreenSoundGroup = null;
        }
        if (apolloGreenSoundContainer != null
                && root.findViewById(apolloGreenSoundContainer.getId())
                        == apolloGreenSoundContainer) {
            apolloGreenSoundContainer = null;
        }
        if (textApolloStatus != null
                && root.findViewById(textApolloStatus.getId()) == textApolloStatus) {
            textApolloStatus = null;
        }
        if (canCommandsEditor != null
                && root.findViewById(canCommandsEditor.getId()) == canCommandsEditor) {
            canCommandsEditor = null;
        }
        if (pickerCustomCommandCount != null
                && root.findViewById(pickerCustomCommandCount.getId())
                        == pickerCustomCommandCount) {
            pickerCustomCommandCount = null;
        }
        if (autoLightGroup != null && root.findViewById(autoLightGroup.getId()) == autoLightGroup) {
            autoLightGroup = null;
        }
        if (textSensorLevel != null
                && root.findViewById(textSensorLevel.getId()) == textSensorLevel) {
            textSensorLevel = null;
        }
        if (checkBox34 != null && root.findViewById(checkBox34.getId()) == checkBox34) {
            checkBox34 = null;
        }
        if (textRamStatus != null && root.findViewById(textRamStatus.getId()) == textRamStatus) {
            textRamStatus = null;
        }
        if (textCpuStatus != null && root.findViewById(textCpuStatus.getId()) == textCpuStatus) {
            textCpuStatus = null;
        }
        if (textHookStatus != null && root.findViewById(textHookStatus.getId()) == textHookStatus) {
            textHookStatus = null;
        }
        if (steeringActions != null && isDescendant(root, steeringActions)) {
            steeringActions.setAdapter(null);
            steeringActions = null;
        }
        if (dockApp1Btn != null && isDescendant(root, dockApp1Btn)) {
            dockApp1Btn = null;
        }
        if (dockApp2Btn != null && isDescendant(root, dockApp2Btn)) {
            dockApp2Btn = null;
        }
        if (dockSplit1Btn != null && isDescendant(root, dockSplit1Btn)) {
            dockSplit1Btn = null;
        }
        if (dockSplit2Btn != null && isDescendant(root, dockSplit2Btn)) {
            dockSplit2Btn = null;
        }
        deleteButtons.removeIf(button -> isDescendant(root, button));
        for (int i = 0; i < lightDiagnosticsRows.length; i++) {
            if (lightDiagnosticsRows[i] != null && isDescendant(root, lightDiagnosticsRows[i])) {
                lightDiagnosticsRows[i] = null;
            }
        }
        settingsList.post(
                () -> {
                    updateSystemMetricsPolling();
                    updateLightDiagnosticsBinding();
                });
    }

    private static boolean isDescendant(View root, View child) {
        for (android.view.ViewParent parent = child.getParent();
                parent instanceof View;
                parent = parent.getParent()) {
            if (parent == root) {
                return true;
            }
        }
        return child == root;
    }

    private void addDialWidget() {
        List<DialWidgetStore.Entry> entries = DialWidgetStore.load(preferences);
        if (entries.size() >= DialWidgetStore.MAX_COUNT) {
            android.widget.Toast.makeText(
                            this, "Достигнут лимит 100 карточек", android.widget.Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        entries.add(new DialWidgetStore.Entry());
        DialWidgetStore.save(preferences, entries);
        TileOrderStore.sync(preferences, getPackageManager());
        refreshSettingsRows();
    }

    // The OEM dock overlays content without reporting window insets.
    private void applyWindowInsets() {
        final float density = getResources().getDisplayMetrics().density;
        final int nativeDock = Math.round(density * 145f);
        View root = settingView(R.id.main);
        if (root == null) {
            return;
        }
        ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (v, insets) -> {
                    Insets builder = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    int top = builder.top;
                    if (top == 0) {
                        int id =
                                getResources()
                                        .getIdentifier("status_bar_height", "dimen", "android");
                        if (id > 0) {
                            top = getResources().getDimensionPixelSize(id);
                        }
                    }
                    v.setPadding(nativeDock + builder.left, top, builder.right, builder.bottom);
                    return insets;
                });
    }

    public void onButtonClickApply(View v) {
        if (currentSection == SettingsSection.VOICE
                || currentSection == SettingsSection.SCENARIOS) {
            // These pages persist immediately; applying must not send vehicle modes.
            android.widget.Toast.makeText(
                            this,
                            currentSection == SettingsSection.SCENARIOS
                                    ? "Сценарии сохранены"
                                    : "Настройки голосового управления сохранены",
                            android.widget.Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        if (applying) {
            return;
        }

        if (!saveCustomCommands()) {
            return;
        }
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance apply $$$", "SetModesService не забинден");
            return;
        }
        try {
            Message message = Message.obtain(null, MSG_APPLY_DRIVE_MODES);
            message.replyTo = applyClient;
            GlobalVars.serviceMessenger.send(message);
            setApplying(true);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void setApplying(boolean on) {
        applying = on;
        boolean immediate =
                currentSection == SettingsSection.VOICE
                        || currentSection == SettingsSection.SCENARIOS;
        if (buttonApplyAdvance != null) {
            buttonApplyAdvance.setEnabled(immediate || !on);
        }
        if (applyProgressAdvance != null) {
            applyProgressAdvance.setVisibility(on && !immediate ? View.VISIBLE : View.GONE);
        }
        uiHandler.removeCallbacks(applyTimeout);
        if (on) {
            uiHandler.postDelayed(applyTimeout, 12000);
        }
    }

    private void bindShowSwitch(int switchId, String key, boolean defaultValue) {
        bindShowSwitch(switchId, key, defaultValue, 0);
    }

    private void bindShowSwitch(int switchId, String key, boolean defaultValue, int dependentId) {
        Switch visibilitySwitch = settingView(switchId);
        if (visibilitySwitch == null) {
            return;
        }
        View dependent = dependentId == 0 ? null : settingView(dependentId);
        boolean checked = preferences.getBoolean(key, defaultValue);
        visibilitySwitch.setChecked(checked);
        if (dependent != null) {
            dependent.setVisibility(checked ? View.VISIBLE : View.GONE);
        }
        visibilitySwitch.setOnCheckedChangeListener(
                (b, on) -> {
                    preferences.edit().putBoolean(key, on).apply();
                    if (dependent != null) {
                        dependent.setVisibility(on ? View.VISIBLE : View.GONE);
                    }
                });
    }

    private void bindTileSizeSpinners(
            int widthSpinnerId,
            int heightSpinnerId,
            String widgetId,
            int defaultWidth,
            int defaultHeight) {
        android.widget.Spinner widthSpinner = settingView(widthSpinnerId);
        android.widget.Spinner heightSpinner = settingView(heightSpinnerId);
        if (widthSpinner == null || heightSpinner == null) {
            return;
        }
        if (SystemWidgetLayout.isWidget(widgetId)) {
            bindEnergySize(widthSpinner, widgetId, true, 1, 1, 2, null);
            bindEnergySize(heightSpinner, widgetId, false, 1, 1, 1, null);
            heightSpinner.setEnabled(false);
            return;
        }
        if (EnergyWidgetLayout.isWidget(widgetId)) {
            Runnable bindHeight =
                    () -> {
                        int columns = TileSizeStore.width(preferences, widgetId, defaultWidth);
                        bindEnergySize(
                                heightSpinner,
                                widgetId,
                                false,
                                defaultHeight,
                                EnergyWidgetLayout.minHeight(widgetId, columns),
                                EnergyWidgetLayout.maxHeight(widgetId, columns),
                                null);
                    };
            bindHeight.run();
            bindEnergySize(
                    widthSpinner,
                    widgetId,
                    true,
                    defaultWidth,
                    EnergyWidgetLayout.minWidth(widgetId),
                    EnergyWidgetLayout.maxWidth(widgetId),
                    bindHeight);
            return;
        }

        String[] widths = {
            "1 ячейка",
            "2 ячейки",
            "3 ячейки",
            "4 ячейки",
            "5 ячеек",
            "6 ячеек",
            "7 ячеек",
            "8 ячеек",
            "9 ячеек",
            "10 ячеек",
            "11 ячеек",
            "12 ячеек"
        };
        android.widget.ArrayAdapter<String> widthAdapter =
                new android.widget.ArrayAdapter<>(this, R.layout.settings_spinner_item, widths);
        widthAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        widthSpinner.setAdapter(widthAdapter);
        widthSpinner.setSelection(TileSizeStore.width(preferences, widgetId, defaultWidth) - 1);
        widthSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        if (TileSizeStore.width(preferences, widgetId, defaultWidth)
                                != position + 1) {
                            TileSizeStore.setWidth(preferences, widgetId, position + 1);
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });

        String[] heights = {"1 ячейка", "2 ячейки", "3 ячейки", "4 ячейки", "5 ячеек"};
        android.widget.ArrayAdapter<String> heightAdapter =
                new android.widget.ArrayAdapter<>(this, R.layout.settings_spinner_item, heights);
        heightAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        heightSpinner.setAdapter(heightAdapter);
        heightSpinner.setSelection(TileSizeStore.height(preferences, widgetId, defaultHeight) - 1);
        heightSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        if (TileSizeStore.height(preferences, widgetId, defaultHeight)
                                != position + 1) {
                            TileSizeStore.setHeight(preferences, widgetId, position + 1);
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
    }

    private void bindEnergySize(
            android.widget.Spinner spinner,
            String id,
            boolean width,
            int fallback,
            int min,
            int max,
            Runnable onChange) {
        String[] labels = new String[max - min + 1];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = String.valueOf(min + i);
        }
        android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<>(this, R.layout.settings_spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        spinner.setOnItemSelectedListener(null);
        spinner.setAdapter(adapter);
        spinner.setSelection(
                (width
                                ? TileSizeStore.width(preferences, id, fallback)
                                : TileSizeStore.height(preferences, id, fallback))
                        - min);
        spinner.setEnabled(max > min);
        spinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long itemId) {
                        int value = min + position;
                        if (width) {
                            TileSizeStore.setWidth(preferences, id, value);
                        } else {
                            TileSizeStore.setHeight(preferences, id, value);
                        }
                        if (onChange != null) {
                            onChange.run();
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
    }

    private void bindEnergyCapacity(int fieldId, String key, float fallback) {
        android.widget.EditText field = settingView(fieldId);
        if (field == null) {
            return;
        }
        field.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        float saved = EnergyWidgetPreferences.capacity(preferences, key, fallback);
        settingsList.bindDraft(
                field, "capacity:" + key, new java.text.DecimalFormat("0.#").format(saved));
        field.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(android.text.Editable value) {
                        float parsed =
                                ru.big.town.common.EnergyWidgetSettings.parseCapacity(
                                        value.toString());
                        if (!Float.isFinite(parsed)) {
                            field.setError("Число больше 0, до 1 знака после запятой");
                            return;
                        }
                        field.setError(null);
                        preferences.edit().putFloat(key, parsed).apply();
                        if (GlobalVars.isBound && GlobalVars.serviceMessenger != null) {
                            try {
                                EnergyWidgetPreferences.sync(
                                        GlobalVars.serviceMessenger, preferences);
                            } catch (RemoteException e) {
                                Log.w(
                                        "EnergyWidgets",
                                        "Capacity settings will sync on reconnect",
                                        e);
                            }
                        }
                    }
                });
    }

    private void sendFloatingBack(boolean enable) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance floatBack $$$", "SetModesService не забинден");
            return;
        }
        try {
            GlobalVars.serviceMessenger.send(
                    Message.obtain(null, MSG_FLOATING_BACK, enable ? 1 : 0, 0));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

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

    static String engineeringPassword(java.util.Calendar beijingNow) {
        String year =
                String.format(java.util.Locale.US, "%04d", beijingNow.get(java.util.Calendar.YEAR));
        String monthDay =
                String.format(
                        java.util.Locale.US,
                        "%02d%02d",
                        beijingNow.get(java.util.Calendar.MONTH) + 1,
                        beijingNow.get(java.util.Calendar.DAY_OF_MONTH));
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            builder.append((year.charAt(i) - '0') + (monthDay.charAt(i) - '0'));
        }
        return builder.toString();
    }

    private void showEngineeringPassword() {
        TextView password = settingView(R.id.textEngPassword);
        TextView passwordDate = settingView(R.id.textEngPasswordDate);
        if (password == null) {
            return;
        }
        try {
            java.util.Calendar beijing =
                    java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            password.setText(engineeringPassword(beijing));
            if (passwordDate != null) {
                passwordDate.setText(
                        String.format(
                                java.util.Locale.US,
                                "дата расчёта: %02d.%02d.%04d по Пекину",
                                beijing.get(java.util.Calendar.DAY_OF_MONTH),
                                beijing.get(java.util.Calendar.MONTH) + 1,
                                beijing.get(java.util.Calendar.YEAR)));
            }
        } catch (Exception e) {
            password.setText("—");
            if (passwordDate != null) {
                passwordDate.setText("не удалось определить дату машины");
            }
        }
    }

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

    public void onButtonCloseAll(View v) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        this, R.style.SettingsDialog)
                .setTitle("Закрыть приложения")
                .setMessage(
                        "Все открытые сторонние приложения будут полностью закрыты и при следующем"
                            + " запуске откроются с нуля. Системные приложения не затрагиваются."
                            + " Приложения, зафиксированные в «Диспетчере задач», останутся"
                            + " открытыми. Продолжить?")
                .setPositiveButton(
                        "Закрыть",
                        (d, w) -> {
                            boolean ok = false;
                            if (GlobalVars.isBound && GlobalVars.serviceMessenger != null) {
                                try {
                                    GlobalVars.serviceMessenger.send(
                                            Message.obtain(null, MSG_CLOSE_ALL));
                                    ok = true;
                                } catch (RemoteException e) {
                                    e.printStackTrace();
                                }
                            }
                            com.google.android.material.snackbar.Snackbar.make(
                                            settingView(R.id.main),
                                            ok ? "Команда закрытия отправлена" : "Сервис не готов",
                                            com.google.android.material.snackbar.Snackbar
                                                    .LENGTH_LONG)
                                    .show();
                            Log.i("$$$ Advance closeAll $$$", "MSG_CLOSE_ALL sent=" + ok);
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private Button dockApp1Btn, dockApp2Btn;
    private Button dockSplit1Btn, dockSplit2Btn;

    private void initDockOverride() {
        dockApp1Btn = settingView(R.id.buttonDockApp1);
        dockApp2Btn = settingView(R.id.buttonDockApp2);
        dockSplit1Btn = settingView(R.id.buttonDockSplit1);
        dockSplit2Btn = settingView(R.id.buttonDockSplit2);
        refreshDockButtons();
        settingView(R.id.settingsResetDock1).setOnClickListener(v -> clearDockApp(1));
        settingView(R.id.settingsResetDock2).setOnClickListener(v -> clearDockApp(2));
        if (dockApp1Btn != null) {
            dockApp1Btn.setOnLongClickListener(
                    v -> {
                        clearDockApp(1);
                        return true;
                    });
        }
        if (dockApp2Btn != null) {
            dockApp2Btn.setOnLongClickListener(
                    v -> {
                        clearDockApp(2);
                        return true;
                    });
        }
    }

    public void onPickDockApp1(View v) {
        pickDockApp(1);
    }

    public void onPickDockApp2(View v) {
        pickDockApp(2);
    }

    public void onPickDockSplit1(View v) {
        pickDockLongPress(1);
    }

    public void onPickDockSplit2(View v) {
        pickDockLongPress(2);
    }

    private void pickDockLongPress(int slot) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        this, R.style.SettingsDialog)
                .setTitle("Долгое нажатие · приложение " + slot)
                .setItems(
                        new String[] {
                            "Открыть сплит",
                            "Открыть в медиакарточке приборной панели",
                            "Не назначено"
                        },
                        (dialog, which) -> {
                            if (which == 0) {
                                pickDockSplit(slot);
                            } else {
                                preferences
                                        .edit()
                                        .putString(
                                                "dockOverride" + slot + "LongAction",
                                                which == 1 ? "cluster" : "none")
                                        .apply();
                                refreshDockButtons();
                                pushDockConfig();
                            }
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickDockApp(int slot) {
        showAppPicker(
                "Приложение " + slot + " в доке",
                (packageName, label) -> {
                    preferences
                            .edit()
                            .putString("dockOverride" + slot, packageName)
                            .putString("dockOverride" + slot + "Label", label)
                            .apply();
                    refreshDockButtons();
                    pushDockConfig();
                });
    }

    private void pickDockSplit(int slot) {
        final java.util.List<SplitStore.Preset> all = SplitStore.load(preferences);
        final java.util.List<Integer> readyIdx = new java.util.ArrayList<>();
        final java.util.List<CharSequence> labels = new java.util.ArrayList<>();
        labels.add("Нет (только открыть приложение)");
        for (int i = 0; i < all.size(); i++) {
            SplitStore.Preset preset = all.get(i);
            if (preset.ready()) {
                readyIdx.add(i);
                labels.add(
                        (preset.ll.isEmpty() ? preset.l : preset.ll)
                                + "  /  "
                                + (preset.rl.isEmpty() ? preset.r : preset.rl));
            }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        this, R.style.SettingsDialog)
                .setTitle("Сплит по долгому нажатию (слот " + slot + ")")
                .setItems(
                        labels.toArray(new CharSequence[0]),
                        (d, which) -> {
                            if (which == 0) {
                                preferences
                                        .edit()
                                        .putString("dockOverride" + slot + "LongAction", "none")
                                        .remove("dockOverride" + slot + "Split")
                                        .remove("dockOverride" + slot + "SplitLabel")
                                        .apply();
                            } else {
                                int presetIndex = readyIdx.get(which - 1);
                                preferences
                                        .edit()
                                        .putString("dockOverride" + slot + "LongAction", "split")
                                        .putInt("dockOverride" + slot + "Split", presetIndex)
                                        .putString(
                                                "dockOverride" + slot + "SplitLabel",
                                                labels.get(which).toString())
                                        .apply();
                            }
                            refreshDockButtons();
                            pushDockConfig();
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void clearDockApp(int slot) {
        preferences
                .edit()
                .remove("dockOverride" + slot)
                .remove("dockOverride" + slot + "Label")
                .remove("dockOverride" + slot + "LongAction")
                .remove("dockOverride" + slot + "Split")
                .remove("dockOverride" + slot + "SplitLabel")
                .apply();
        refreshDockButtons();
        pushDockConfig();
        com.google.android.material.snackbar.Snackbar.make(
                        settingView(R.id.main),
                        "Слот " + slot + " сброшен",
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show();
    }

    private void refreshDockButtons() {
        setDockButtonText(dockApp1Btn, 1);
        setDockButtonText(dockApp2Btn, 2);
        setDockSplitButton(dockSplit1Btn, 1);
        setDockSplitButton(dockSplit2Btn, 2);
    }

    private void setDockButtonText(Button b, int slot) {
        if (b == null) {
            return;
        }
        String label = preferences.getString("dockOverride" + slot + "Label", "");
        b.setText(label.isEmpty() ? "Не выбрано" : label);
    }

    private void setDockSplitButton(Button b, int slot) {
        if (b == null) {
            return;
        }
        boolean hasApp = !preferences.getString("dockOverride" + slot, "").isEmpty();
        b.setVisibility(View.VISIBLE);
        b.setEnabled(hasApp);
        String action = DockLongPressAction.resolve(preferences, slot);
        String label = "не назначено";
        if ("cluster".equals(action)) {
            label = "медиакарточка приборной панели";
        } else if ("split".equals(action)) {
            label =
                    "сплит · "
                            + preferences.getString(
                                    "dockOverride" + slot + "SplitLabel", "не выбран");
        }
        b.setText(label);
    }

    interface AppPicked {
        void onPicked(String packageName, String label);
    }

    private void showAppPicker(String title, AppPicked callback) {
        android.content.pm.PackageManager packageManager = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps =
                packageManager.queryIntentActivities(launcher, 0);

        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (android.content.pm.ResolveInfo ri : apps) {
            String packageName = ri.activityInfo.packageName;
            if (!map.containsKey(packageName)) {
                map.put(packageName, ri.loadLabel(packageManager).toString());
            }
        }

        final java.util.List<String> pkgs = new java.util.ArrayList<>(map.keySet());

        java.util.Collections.sort(
                pkgs,
                (a, b) -> {
                    boolean aIsCom = isSystemApp(a);
                    boolean bIsCom = isSystemApp(b);

                    if (aIsCom && !bIsCom) {
                        return 1;
                    }
                    if (!aIsCom && bIsCom) {
                        return -1;
                    }

                    return map.get(a).compareToIgnoreCase(map.get(b));
                });

        final CharSequence[] items = new CharSequence[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) {
            items[i] = map.get(pkgs.get(i)) + "  ·  " + pkgs.get(i);
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        this, R.style.SettingsDialog)
                .setTitle(title)
                .setItems(
                        items,
                        (d, which) -> callback.onPicked(pkgs.get(which), map.get(pkgs.get(which))))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private boolean isSystemApp(String packageName) {
        return packageName.startsWith("com.qinggan")
                || packageName.startsWith("com.bz")
                || packageName.startsWith("com.android")
                || packageName.startsWith("com.tencent")
                || packageName.startsWith("com.huawei")
                || packageName.startsWith("com.mega")
                || packageName.startsWith("com.thunder")
                || packageName.startsWith("com.pateo")
                || packageName.startsWith("com.baidu")
                || packageName.startsWith("com.richauto");
    }

    public void onButtonGrantInstall(View v) {
        showAppPicker(
                "Выдать права на установку",
                (packageName, label) -> {
                    sendGrantInstall(packageName);
                    com.google.android.material.snackbar.Snackbar.make(
                                    settingView(R.id.main),
                                    "Право на установку выдано: " + label,
                                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                            .show();
                });
    }

    public void onAddAppShortcut(View v) {
        showAppPicker(
                "Добавить приложение",
                (packageName, label) -> {
                    java.util.List<String> list = AppShortcutStore.load(preferences);
                    if (!list.contains(packageName)) {
                        list.add(packageName);
                        AppShortcutStore.save(preferences, list);

                        TileOrderStore.sync(preferences, getPackageManager());
                        renderAppShortcuts();
                    }
                });
    }

    private void renderAppShortcuts() {
        refreshSettingsRows();
    }

    public void onAddAppWidget(View v) {
        if (AppWidgetStore.load(preferences).size() >= AppWidgetStore.MAX_WIDGETS) {
            com.google.android.material.snackbar.Snackbar.make(
                            settingView(R.id.main),
                            "Можно создать не более 20 виджетов",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .show();
            return;
        }
        showAppPicker(
                "Приложение для виджета",
                (packageName, label) -> {
                    AppWidgetStore.add(preferences, packageName);
                    TileOrderStore.sync(preferences, getPackageManager());
                    renderAppWidgets();
                });
    }

    private void renderAppWidgets() {
        refreshSettingsRows();
    }

    private View appWidgetProfileRow(
            LinearLayout parent, AppWidgetStore.Entry entry, int profileIndex) {
        android.content.pm.PackageManager packageManager = getPackageManager();
        AppWidgetStore.Profile profile = entry.profiles.get(profileIndex);
        View profileView =
                LayoutInflater.from(this).inflate(R.layout.item_app_widget_profile, parent, false);
        android.widget.ImageView icon = profileView.findViewById(R.id.appWidgetProfileIcon);
        TextView label = profileView.findViewById(R.id.appWidgetProfileLabel);
        android.widget.Spinner dpi = profileView.findViewById(R.id.appWidgetProfileDpi);
        ImageButton remove = profileView.findViewById(R.id.appWidgetProfileDelete);
        try {
            android.content.pm.ApplicationInfo info =
                    packageManager.getApplicationInfo(profile.packageName, 0);
            icon.setImageDrawable(packageManager.getApplicationIcon(info));
            label.setText(packageManager.getApplicationLabel(info));
        } catch (Exception ignored) {
            label.setText(profile.packageName);
        }
        String[] labels = new String[AppWidgetStore.DPI_VALUES.length];
        int selected = 0;
        for (int dpiIndex = 0; dpiIndex < AppWidgetStore.DPI_VALUES.length; dpiIndex++) {
            int value = AppWidgetStore.DPI_VALUES[dpiIndex];
            labels[dpiIndex] = value == 0 ? "Авто" : String.valueOf(value);
            if (value == AppWidgetStore.normalizeDpi(profile.dpi)) {
                selected = dpiIndex;
            }
        }
        android.widget.ArrayAdapter<String> dpiAdapter =
                new android.widget.ArrayAdapter<>(this, R.layout.settings_spinner_item, labels);
        dpiAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        dpi.setAdapter(dpiAdapter);
        dpi.setSelection(selected);
        dpi.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        int value = AppWidgetStore.DPI_VALUES[position];
                        if (profile.dpi != value) {
                            profile.dpi = value;
                            AppWidgetStore.update(preferences, entry);
                            TileOrderStore.sync(preferences, getPackageManager());
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
        remove.setVisibility(entry.profiles.size() > 1 ? View.VISIBLE : View.GONE);
        remove.setOnClickListener(
                v -> {
                    AppWidgetStore.removeProfile(entry, profileIndex);
                    AppWidgetStore.update(preferences, entry);
                    TileOrderStore.sync(preferences, getPackageManager());
                    renderAppWidgets();
                });
        SettingsDesign.styleTree(profileView);
        return profileView;
    }

    public void onAddFullscreenApp(View v) {
        showAppPicker(
                "Добавить полноэкранное приложение",
                (packageName, label) -> {
                    if (packageName.startsWith("ru.big.town")) {
                        com.google.android.material.snackbar.Snackbar.make(
                                        settingView(R.id.main),
                                        "Экраны VoyahTune используют собственную системную"
                                                + " раскладку",
                                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                .show();
                        return;
                    }
                    java.util.List<String> packages = FullscreenAppStore.load(preferences);
                    if (!packages.contains(packageName)) {
                        packages.add(packageName);
                        saveFullscreenApps(packages);
                    }
                });
    }

    private void saveFullscreenApps(java.util.List<String> packages) {
        FullscreenAppStore.save(preferences, packages);
        SplitConfigSync.pushFullscreenApps(this, preferences);
        renderFullscreenApps();
    }

    private void renderFullscreenApps() {
        refreshSettingsRows();
    }

    public void onAddSplitPreset(View v) {
        java.util.List<SplitStore.Preset> list = SplitStore.load(preferences);
        list.add(new SplitStore.Preset());
        saveSplitPresets(list);
        renderSplitPresets();
    }

    // Native mirrors presets into the dock and steering hook settings.
    private void saveSplitPresets(java.util.List<SplitStore.Preset> list) {
        SplitStore.save(preferences, list);

        TileOrderStore.sync(preferences, getPackageManager());
        SplitConfigSync.pushAll(this, preferences);
    }

    private void renderSplitPresets() {
        refreshSettingsRows();
    }

    private static final int[] DPI_VALUES = {
        0, 120, 140, 160, 180, 200, 213, 240, 260, 280, 300, 320, 360
    };
    private static final String[] DPI_LABELS = {
        "Авто", "120", "140", "160", "180", "200", "213", "240", "260", "280", "300", "320", "360"
    };

    private int dpiIndex(int dpi) {
        for (int i = 0; i < DPI_VALUES.length; i++) {
            if (DPI_VALUES[i] == dpi) {
                return i;
            }
        }
        return 0;
    }

    private final java.util.LinkedHashMap<String, String> appLabels =
            new java.util.LinkedHashMap<>();
    private boolean appsLoading, appsLoaded;

    private void loadAppMetadata() {
        if (appsLoading || appsLoaded) {
            return;
        }
        appsLoading = true;
        systemMetricsExecutor.execute(
                () -> {
                    java.util.Map<String, String> labels = new java.util.HashMap<>();
                    android.content.pm.PackageManager packageManager = getPackageManager();
                    Intent launcher =
                            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                    for (android.content.pm.ResolveInfo info :
                            packageManager.queryIntentActivities(launcher, 0)) {
                        if ((info.activityInfo.applicationInfo.flags
                                        & android.content.pm.ApplicationInfo.FLAG_SYSTEM)
                                == 0) {
                            labels.put(
                                    info.activityInfo.packageName,
                                    info.loadLabel(packageManager).toString());
                        }
                    }
                    List<String> packages = new ArrayList<>(labels.keySet());
                    packages.sort((a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
                    uiHandler.post(
                            () -> {
                                if (isDestroyed()) {
                                    return;
                                }
                                for (String packageName : packages) {
                                    appLabels.put(packageName, labels.get(packageName));
                                }
                                appsLoaded = true;
                                appsLoading = false;
                                if (currentSection == SettingsSection.APPS) {
                                    refreshSettingsRows();
                                }
                            });
                });
    }

    private void sendGrantInstall(String packageName) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance grantInstall $$$", "SetModesService не забинден");
            return;
        }
        int uid;
        try {
            uid = getPackageManager().getApplicationInfo(packageName, 0).uid;
        } catch (Exception e) {
            Log.w("$$$ Advance grantInstall $$$", "не найден uid для " + packageName);
            return;
        }
        try {
            Message message = Message.obtain(null, MSG_GRANT_INSTALL, uid, 0);
            Bundle data = new Bundle();
            data.putString("pkg", packageName);
            message.setData(data);
            GlobalVars.serviceMessenger.send(message);
            Log.i(
                    "$$$ Advance grantInstall $$$",
                    "MSG_GRANT_INSTALL pkg=" + packageName + " uid=" + uid);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void onButtonRebootSystem(View v) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        this, R.style.SettingsDialog)
                .setTitle("Перезагрузка системы")
                .setMessage(
                        "Система (голова) будет перезагружена. Несохранённые действия могут "
                                + "прерваться. Продолжить?")
                .setPositiveButton(
                        "Перезагрузить",
                        (d, w) -> {
                            if (GlobalVars.isBound && GlobalVars.serviceMessenger != null) {
                                try {
                                    GlobalVars.serviceMessenger.send(
                                            Message.obtain(null, MSG_REBOOT));
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

    public void onButtonLogging(View v) {
        startActivity(new Intent(this, LoggingActivity.class));
    }

    private void setSection(SettingsSection section) {
        currentSection = section;
        ((TextView) settingView(R.id.settingsEyebrow)).setText(section.category);
        sectionTitle.setText(section.title);
        for (SettingsSection candidate : SettingsSection.values()) {
            settingView(candidate.navigationId).setSelected(candidate == section);
        }
        showSection(true);
        setApplying(applying);
        updateSystemMetricsPolling();
        updateLightDiagnosticsBinding();
    }

    private void showSection(boolean resetScroll) {
        settingsList.setTag(currentSection);
        if (currentSection == SettingsSection.VOICE) {
            if (voiceSettings == null) {
                voiceSettings =
                        new VoiceSettingsPage(
                                this, settingsList, preferences, this::refreshSteerActions);
            }
            voiceSettings.refresh(resetScroll);
        } else if (currentSection == SettingsSection.SCENARIOS) {
            if (scenarioSettings == null) {
                scenarioSettings =
                        new ScenarioSettingsPage(
                                this,
                                settingsList,
                                preferences,
                                () -> {
                                    SplitConfigSync.pushScenarios(this, preferences);
                                    VoiceCommands.invalidate();
                                });
            }
            scenarioSettings.refresh(resetScroll);
        } else {
            settingsList.submit(sectionRows(currentSection), resetScroll);
        }
    }

    private void refreshSettingsRows() {
        settingsList.post(() -> showSection(false));
    }

    private void updateLightDiagnosticsBinding() {
        boolean shouldBind =
                activityResumed
                        && currentSection == SettingsSection.OTHER
                        && lightDiagnosticsRows[0] != null
                        && lightDiagnosticsRows[0].isShown()
                        && preferences.getBoolean("debugMode", false)
                        && !isFinishing();
        if (shouldBind == lightDiagnosticsActive) {
            return;
        }
        lightDiagnosticsActive = shouldBind;
        if (shouldBind) {
            ++lightDiagnosticsSession;
            resetLightDiagnostics();
            lightSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (lightSensorManager != null) {
                Sensor light = lightSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
                if (light != null) {
                    lightSensorManager.registerListener(
                            androidLightListener, light, SensorManager.SENSOR_DELAY_NORMAL);
                }
            }
            Intent intent =
                    new Intent()
                            .setClassName(
                                    NATIVE_PACKAGE, "ru.big.town.anative.LightDiagnosticsService");
            try {
                lightDiagnosticsBound =
                        bindService(intent, lightDiagnosticsConnection, BIND_AUTO_CREATE);
            } catch (SecurityException | IllegalArgumentException e) {
                Log.w("LightDiagnostics", "Native diagnostics unavailable", e);
            }
        } else {
            boolean wasBound = lightDiagnosticsBound;
            lightDiagnosticsBound = false;
            if (lightSensorManager != null) {
                lightSensorManager.unregisterListener(androidLightListener);
            }
            lightSensorManager = null;
            if (wasBound) {
                try {
                    unbindService(lightDiagnosticsConnection);
                } catch (IllegalArgumentException ignored) {
                }
            }
            resetLightDiagnostics();
        }
    }

    private void resetLightDiagnostics() {
        int[] unknown = new int[lightDiagnosticsRows.length];
        java.util.Arrays.fill(unknown, LIGHT_DIAGNOSTICS_UNKNOWN);
        showLightDiagnostics(unknown);
    }

    private void showLightDiagnostics(int[] values) {
        for (int i = 0; i < values.length; i++) {
            if (lightDiagnosticsRows[i] == null) {
                continue;
            }
            String value =
                    values[i] == LIGHT_DIAGNOSTICS_UNKNOWN
                            ? "—"
                            : i == 0
                                    ? values[i] + " — " + swReasonDescription(values[i])
                                    : Integer.toString(values[i]);
            lightDiagnosticsRows[i].setText(LIGHT_DIAGNOSTICS_LABELS[i] + ": " + value);
        }
    }

    private static String swReasonDescription(int value) {
        switch (value) {
            case 0:
                return "день";
            case 1:
                return "другое";
            case 2:
                return "темно";
            case 3:
                return "тоннель";
            case 4:
                return "начало темноты";
            default:
                return "неизвестно";
        }
    }

    private void updateSystemMetricsPolling() {
        boolean shouldRun =
                activityResumed
                        && currentSection == SettingsSection.OTHER
                        && textRamStatus != null
                        && textRamStatus.isShown()
                        && !isFinishing();
        if (shouldRun == systemMetricsActive) {
            return;
        }
        systemMetricsActive = shouldRun;
        ++systemMetricsGeneration;
        uiHandler.removeCallbacks(systemMetricsTick);
        if (shouldRun) {
            if (textRamStatus != null) {
                textRamStatus.setText("Используется: …\nДоступно: …");
            }
            if (textCpuStatus != null) {
                textCpuStatus.setText("Измерение…");
            }
            if (textHookStatus != null) {
                textHookStatus.setText("Чтение состояния…");
            }
            uiHandler.post(systemMetricsTick);
        }
    }

    private void sampleSystemMetrics() {
        if (!systemMetricsActive || currentSection != SettingsSection.OTHER) {
            return;
        }
        final long generation = systemMetricsGeneration;
        try {
            systemMetricsExecutor.execute(
                    () -> {
                        final SystemMetricsReader.Snapshot snapshot =
                                systemMetricsReader.read(generation);
                        String hookPayload =
                                getSharedPreferences(
                                                HookStatusContract.PREFERENCES_NAME,
                                                Context.MODE_PRIVATE)
                                        .getString(HookStatusContract.PAYLOAD_KEY, null);
                        final String hookStatus = HookStatusContract.renderForUi(hookPayload);
                        uiHandler.post(
                                () -> {
                                    if (!systemMetricsActive
                                            || currentSection != SettingsSection.OTHER
                                            || generation != systemMetricsGeneration) {
                                        return;
                                    }
                                    if (textRamStatus != null) {
                                        textRamStatus.setText(
                                                !Float.isFinite(snapshot.ramPercent)
                                                        ? "Недоступно"
                                                        : "Используется: "
                                                                + android.text.format.Formatter
                                                                        .formatFileSize(
                                                                                this,
                                                                                snapshot.usedMemoryBytes)
                                                                + " из "
                                                                + android.text.format.Formatter
                                                                        .formatFileSize(
                                                                                this,
                                                                                snapshot.totalMemoryBytes)
                                                                + "\nДоступно: "
                                                                + android.text.format.Formatter
                                                                        .formatFileSize(
                                                                                this,
                                                                                snapshot.availableMemoryBytes));
                                    }
                                    if (textCpuStatus != null) {
                                        textCpuStatus.setText(
                                                !snapshot.cpuReadable
                                                        ? "Недоступно"
                                                        : Float.isNaN(snapshot.cpuPercent)
                                                                ? "Измерение…"
                                                                : String.format(
                                                                        Locale.getDefault(),
                                                                        "%.0f%%",
                                                                        snapshot.cpuPercent));
                                    }
                                    if (textHookStatus != null) {
                                        textHookStatus.setText(hookStatus);
                                    }
                                    uiHandler.postDelayed(
                                            systemMetricsTick, SYSTEM_METRICS_INTERVAL_MS);
                                });
                    });
        } catch (RejectedExecutionException ignored) {
        }
    }

    // Apollo Tech — persisted targets for individual vehicle features.

    private TextView textApolloStatus;

    private void initApolloTech() {
        if (settingView(R.id.switchApolloTlc) != null) {
            switchApolloTlc = settingView(R.id.switchApolloTlc);
        }
        if (settingView(R.id.switchApolloTrafficLights) != null) {
            switchApolloTrafficLights = settingView(R.id.switchApolloTrafficLights);
        }
        if (settingView(R.id.switchApolloTrafficSigns) != null) {
            switchApolloTrafficSigns = settingView(R.id.switchApolloTrafficSigns);
        }
        if (settingView(R.id.switchApolloSpeedSigns) != null) {
            switchApolloSpeedSigns = settingView(R.id.switchApolloSpeedSigns);
        }
        boolean od = ru.big.town.common.InfrastructureProfile.read(this).usesAccHooks();
        if (settingView(R.id.apolloSpeedOptionsContainer) != null) {
            apolloSpeedOptionsContainer = settingView(R.id.apolloSpeedOptionsContainer);
        }
        if (apolloSpeedOptionsContainer != null) {
            apolloSpeedOptionsContainer.setVisibility(od ? View.VISIBLE : View.GONE);
        }
        if (settingView(R.id.apolloSpeedModeGroup) != null) {
            apolloSpeedModeGroup = settingView(R.id.apolloSpeedModeGroup);
        }
        if (settingView(R.id.switchApolloSpeedWarning) != null) {
            switchApolloSpeedWarning = settingView(R.id.switchApolloSpeedWarning);
        }
        if (od && settingView(R.id.apolloSpeedModeGroup) != null) {
            int mode = ApolloSettings.speedMode(preferences);
            apolloSpeedModeGroup.check(
                    mode == ApolloSettings.AUTO_CORRECTION
                            ? R.id.apolloSpeedAutomatic
                            : mode == ApolloSettings.CONFIRM_CORRECTION
                                    ? R.id.apolloSpeedConfirm
                                    : R.id.apolloSpeedRecognition);
            apolloSpeedModeGroup.setOnCheckedChangeListener(
                    (group, checkedId) -> {
                        if (!switchApolloSpeedSigns.isChecked()) {
                            return;
                        }
                        int selected;
                        if (checkedId == R.id.apolloSpeedAutomatic) {
                            selected = ApolloSettings.AUTO_CORRECTION;
                        } else if (checkedId == R.id.apolloSpeedConfirm) {
                            selected = ApolloSettings.CONFIRM_CORRECTION;
                        } else if (checkedId == R.id.apolloSpeedRecognition) {
                            selected = ApolloSettings.RECOGNITION_ONLY;
                        } else {
                            return;
                        }
                        preferences
                                .edit()
                                .putInt(ApolloSettings.SPEED_MODE, selected)
                                .remove(ApolloSettings.CRUISE_SPEED_ADJUSTMENT)
                                .apply();
                        applyApolloTargets();
                    });
            bindApolloSwitch(switchApolloSpeedWarning, ApolloSettings.SPEED_WARNING);
        }
        if (settingView(R.id.apolloGreenSoundGroup) != null) {
            apolloGreenSoundGroup = settingView(R.id.apolloGreenSoundGroup);
        }
        if (settingView(R.id.apolloGreenSoundContainer) != null) {
            apolloGreenSoundContainer = settingView(R.id.apolloGreenSoundContainer);
        }
        if (settingView(R.id.textApolloStatus) != null) {
            textApolloStatus = settingView(R.id.textApolloStatus);
        }
        bindApolloSwitch(switchApolloTlc, ApolloSettings.TLC);
        bindApolloSwitch(switchApolloTrafficSigns, ApolloSettings.TRAFFIC_SIGNS);
        bindApolloSwitch(switchApolloSpeedSigns, ApolloSettings.SPEED_SIGNS);
        bindApolloSwitch(switchApolloTrafficLights, ApolloSettings.TRAFFIC_LIGHTS);

        boolean greenSound =
                preferences.getBoolean(ApolloSettings.GREEN_SOUND, ApolloSettings.DEFAULT_ENABLED);
        if (settingView(R.id.apolloGreenSoundGroup) != null) {
            apolloGreenSoundGroup.check(
                    greenSound ? R.id.apolloGreenSoundOn : R.id.apolloGreenSoundOff);
            apolloGreenSoundGroup.setOnCheckedChangeListener(
                    (group, checkedId) -> {
                        if (checkedId != R.id.apolloGreenSoundOn
                                && checkedId != R.id.apolloGreenSoundOff) {
                            return;
                        }
                        preferences
                                .edit()
                                .putBoolean(
                                        ApolloSettings.GREEN_SOUND,
                                        checkedId == R.id.apolloGreenSoundOn)
                                .apply();
                        applyApolloTargets();
                    });
        }
        updateApolloUi();
    }

    private void bindApolloSwitch(Switch target, String preference) {
        if (target == null
                || (bindingRoot != null && bindingRoot.findViewById(target.getId()) != target)) {
            return;
        }
        target.setChecked(preferences.getBoolean(preference, ApolloSettings.DEFAULT_ENABLED));
        target.setEnabled(true);
        target.setOnCheckedChangeListener(
                (button, checked) -> {
                    preferences.edit().putBoolean(preference, checked).apply();
                    applyApolloTargets();
                    if (ApolloSettings.TRAFFIC_LIGHTS.equals(preference)
                            || ApolloSettings.SPEED_SIGNS.equals(preference)) {
                        updateApolloUi();
                    }
                });
    }

    private void applyApolloTargets() {
        if (!ru.big.town.common.InfrastructureProfile.read(this).usesAccHooks()) {
            return;
        }
        try {
            startForegroundService(
                    new Intent("ru.big.town.anative.APPLY_APOLLO")
                            .setClassName(
                                    "ru.big.town.anative", "ru.big.town.anative.SetModesService"));
        } catch (RuntimeException e) {
            Log.w("ApolloSettings", "Saved targets; immediate apply unavailable", e);
        }
    }

    private void updateApolloUi() {
        boolean speedSignsEnabled =
                preferences.getBoolean(ApolloSettings.SPEED_SIGNS, ApolloSettings.DEFAULT_ENABLED);
        if (apolloSpeedOptionsContainer != null) {
            apolloSpeedOptionsContainer.setAlpha(speedSignsEnabled ? 1f : 0.45f);
        }
        if (apolloSpeedModeGroup != null) {
            apolloSpeedModeGroup.setEnabled(speedSignsEnabled);
            for (int i = 0; i < apolloSpeedModeGroup.getChildCount(); i++) {
                apolloSpeedModeGroup.getChildAt(i).setEnabled(speedSignsEnabled);
            }
        }
        if (switchApolloSpeedWarning != null) {
            switchApolloSpeedWarning.setEnabled(speedSignsEnabled);
        }
        boolean trafficLightsEnabled =
                preferences.getBoolean(
                        ApolloSettings.TRAFFIC_LIGHTS, ApolloSettings.DEFAULT_ENABLED);
        if (apolloGreenSoundGroup != null) {
            apolloGreenSoundGroup.setEnabled(trafficLightsEnabled);
        }
        if (apolloGreenSoundContainer != null && apolloGreenSoundGroup != null) {
            apolloGreenSoundContainer.setAlpha(trafficLightsEnabled ? 1f : 0.45f);
            for (int i = 0; i < apolloGreenSoundGroup.getChildCount(); i++) {
                apolloGreenSoundGroup.getChildAt(i).setEnabled(trafficLightsEnabled);
            }
        }

        if (textApolloStatus != null) {
            textApolloStatus.setText(
                    ru.big.town.common.InfrastructureProfile.read(this).usesAccHooks()
                            ? "Применение при изменении, кнопкой «Применить» и при пробуждении "
                                    + "автомобиля."
                            : "Применение кнопкой «Применить» и через 10 секунд после "
                                    + "пробуждения.");
        }
    }

    static final String[][] STEER_ACTIONS = {
        {"none", "Не менять"},
        {"open_voyahtune", "Открыть VoyahTune"},
        {"system_back", "Системное действие: Назад"},
        {"energy:EV", "Энергорежим: Electric"},
        {"energy:REV", "Энергорежим: Fuel"},
        {"energy:SREV", "Энергорежим: Save"},
        {"energy:EV,REV", "Энергорежим: Electric → Fuel"},
        {"energy:EV,REV,SREV", "Энергорежим: Electric → Fuel → Save"},
        {"energy:EV,SREV", "Энергорежим: Electric → Save"},
        {"energy:REV,SREV", "Энергорежим: Fuel → Save"},
        {"drive:ECO", "Режим езды: Eco"},
        {"drive:COMFORT", "Режим езды: Comf"},
        {"drive:SPORT", "Режим езды: Sport"},
        {"drive:OUTING", "Режим езды: Outing"},
        {"drive:SNOW", "Режим езды: Snow"},
        {"drive:INDIVIDUAL", "Режим езды: Indiv"},
        {"drive:ECO,COMFORT", "Режим езды: Eco → Comf"},
        {"drive:ECO,SPORT", "Режим езды: Eco → Sport"},
        {"drive:ECO,OUTING", "Режим езды: Eco → Outing"},
        {"drive:ECO,SNOW", "Режим езды: Eco → Snow"},
        {"drive:ECO,INDIVIDUAL", "Режим езды: Eco → Indiv"},
        {"drive:COMFORT,SPORT", "Режим езды: Comf → Sport"},
        {"drive:COMFORT,OUTING", "Режим езды: Comf → Outing"},
        {"drive:COMFORT,SNOW", "Режим езды: Comf → Snow"},
        {"drive:COMFORT,INDIVIDUAL", "Режим езды: Comf → Indiv"},
        {"drive:SPORT,OUTING", "Режим езды: Sport → Outing"},
        {"drive:SPORT,SNOW", "Режим езды: Sport → Snow"},
        {"drive:SPORT,INDIVIDUAL", "Режим езды: Sport → Indiv"},
        {"drive:OUTING,SNOW", "Режим езды: Outing → Snow"},
        {"drive:OUTING,INDIVIDUAL", "Режим езды: Outing → Indiv"},
        {"drive:SNOW,INDIVIDUAL", "Режим езды: Snow → Indiv"},
        {"recycle:LOW", "Рекуперация: Низкая"},
        {"recycle:MEDIUM", "Рекуперация: Стандартная"},
        {"recycle:HIGH", "Рекуперация: Высокая"},
        {"recycle:LOW,MEDIUM", "Рекуперация: Низкая → Стандартная"},
        {"recycle:LOW,HIGH", "Рекуперация: Низкая → Высокая"},
        {"recycle:MEDIUM,HIGH", "Рекуперация: Стандартная → Высокая"},
        {"toggle_suspension_maintenance", "Сервисный режим подвески: вкл/выкл"},
        {"toggle_forced_ev", "Force EV: вкл/выкл"},
        {"toggle_pedestrian_sound", "Звук пешеходов: вкл/выкл"},
        {"toggle_headlights", "Фары: выкл/ближний"},
        {"toggle_headlights_auto", "Фары: ближний/авто"},
    };

    private void initSteeringButtons() {
        steeringActions = settingView(R.id.settingsSteeringActions);
        steeringActions.setNestedScrollingEnabled(false);
        RadioGroup tabs = settingView(R.id.settingsSteeringTabs);
        tabs.check(selectedSteeringTab);
        View row = bindingRoot;
        tabs.setOnCheckedChangeListener(
                (group, selected) -> {
                    selectedSteeringTab = selected;
                    updateSteeringSelection(row);
                    refreshSteerActions();
                });
        updateSteeringSelection(row);
        refreshSteerActions();
    }

    private void updateSteeringSelection(View row) {
        boolean left = selectedSteeringTab == R.id.settingsStarTab;
        android.widget.ImageView wheel = row.findViewById(R.id.settingsSteeringWheel);
        TextView caption = row.findViewById(R.id.settingsSteeringCaption);
        TextView selected = row.findViewById(selectedSteeringTab);
        ((TextView) row.findViewById(R.id.settingsSteeringSelectedTitle))
                .setText(selected.getText());
        wheel.setImageResource(
                left ? R.drawable.settings_wheel_left : R.drawable.settings_wheel_right);
        caption.setText(
                left
                        ? "Левый блок · звёздочка"
                        : "Правый блок · "
                                + (selectedSteeringTab == R.id.settingsDvrTab
                                        ? "DVR"
                                        : selectedSteeringTab == R.id.settingsVoiceTab
                                                ? "голосовой помощник"
                                                : "трубка"));
        wheel.setContentDescription(caption.getText());
    }

    public void onPickSteerStarShort(View v) {
        pickSteerAction("steerStarShort");
    }

    public void onPickSteerStarLong(View v) {
        pickSteerAction("steerStarLong");
    }

    public void onPickSteerVoiceShort(View v) {
        pickSteerAction("steerVoiceShort");
    }

    public void onPickSteerVoiceLong(View v) {
        pickSteerAction("steerVoiceLong");
    }

    public void onPickSteerDvrShort(View v) {
        pickSteerAction("steerDvrShort");
    }

    public void onPickSteerDvrLong(View v) {
        pickSteerAction("steerDvrLong");
    }

    public void onPickSteerPhoneShort(View v) {
        pickSteerAction("steerPhoneShort");
    }

    public void onPickSteerPhoneLong(View v) {
        pickSteerAction("steerPhoneLong");
    }

    private void pickSteerAction(String key) {
        if (voiceOwnsSlot(key)) {
            return;
        }
        final int staticCount = STEER_ACTIONS.length - 1;
        final CharSequence[] labels = new CharSequence[staticCount + 4];
        for (int i = 0; i < staticCount; i++) {
            labels[i] = STEER_ACTIONS[i + 1][1];
        }
        labels[staticCount] = "Открыть сплит…";
        labels[staticCount + 1] = "Открыть приложение…";
        labels[staticCount + 2] = "Набрать номер…";
        labels[staticCount + 3] = "Своя CAN-команда…";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        this, R.style.SettingsDialog)
                .setTitle("Добавить действие")
                .setItems(
                        labels,
                        (d, which) -> {
                            if (which < staticCount) {
                                appendSteerAction(key, STEER_ACTIONS[which + 1][0]);
                            } else if (which == staticCount) {
                                pickSteerSplit(key);
                            } else if (which == staticCount + 1) {
                                pickSteerApp(key);
                            } else if (which == staticCount + 2) {
                                pickSteerDial(key);
                            } else {
                                showCustomSteerCommandDialog(key);
                            }
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickSteerDial(String key) {
        List<DialWidgetStore.Entry> entries = DialWidgetStore.load(preferences);
        if (entries.isEmpty()) {
            android.widget.Toast.makeText(
                            this,
                            "Сначала создайте карточку набора номера",
                            android.widget.Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        CharSequence[] labels = new CharSequence[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            DialWidgetStore.Entry entry = entries.get(i);
            labels[i] = (entry.name.isEmpty() ? "Без имени" : entry.name) + " — " + entry.number;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        this, R.style.SettingsDialog)
                .setTitle("Выбрать номер для кнопки руля")
                .setItems(
                        labels,
                        (dialog, which) -> {
                            String number = entries.get(which).number.replaceAll("[^0-9]", "");
                            if (number.length() >= 4 && number.length() <= 10) {
                                if (number.length() == 10) {
                                    number = "8" + number;
                                }
                                appendSteerAction(key, "call:" + number);
                            }
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickSteerSplit(String key) {
        final java.util.List<SplitStore.Preset> all = SplitStore.load(preferences);
        final java.util.List<Integer> readyIdx = new java.util.ArrayList<>();
        final java.util.List<CharSequence> labels = new java.util.ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            SplitStore.Preset preset = all.get(i);
            if (preset.ready()) {
                readyIdx.add(i);
                labels.add(
                        (preset.ll.isEmpty() ? preset.l : preset.ll)
                                + "  /  "
                                + (preset.rl.isEmpty() ? preset.r : preset.rl));
            }
        }
        if (readyIdx.isEmpty()) {
            com.google.android.material.snackbar.Snackbar.make(
                            settingView(R.id.main),
                            "Нет готовых сплитов — сначала настройте сплит в «Приложения и "
                                    + "разделение экрана»",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .show();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        this, R.style.SettingsDialog)
                .setTitle("Открыть сплит")
                .setItems(
                        labels.toArray(new CharSequence[0]),
                        (d, which) -> appendSteerAction(key, "split:" + readyIdx.get(which)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickSteerApp(String key) {
        showAppPicker(
                "Открыть приложение",
                (packageName, label) -> appendSteerAction(key, "app:" + packageName));
    }

    private void showCustomSteerCommandDialog(String key) {
        View content =
                LayoutInflater.from(this)
                        .inflate(R.layout.dialog_steering_can_command, null, false);
        EditText editor = content.findViewById(R.id.steerCanCommandInput);
        TextView error = content.findViewById(R.id.steerCanCommandError);
        AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                this, R.style.SettingsDialog)
                        .setTitle("Своя CAN-команда")
                        .setView(content)
                        .setPositiveButton("Добавить", null)
                        .setNegativeButton("Отмена", null)
                        .create();
        dialog.setOnShowListener(
                ignored -> {
                    Button add = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                    TextWatcher watcher =
                            new TextWatcher() {
                                private boolean formatting;

                                @Override
                                public void beforeTextChanged(
                                        CharSequence s, int start, int count, int after) {}

                                @Override
                                public void onTextChanged(
                                        CharSequence s, int start, int before, int count) {
                                    if (formatting) {
                                        return;
                                    }
                                    String formatted =
                                            SteeringCanCommandPolicy.format(s.toString());
                                    if (!formatted.contentEquals(s)) {
                                        formatting = true;
                                        editor.setText(formatted);
                                        editor.setSelection(formatted.length());
                                        formatting = false;
                                    }
                                    updateCustomCanValidation(editor, error, add);
                                }

                                @Override
                                public void afterTextChanged(Editable s) {}
                            };
                    editor.addTextChangedListener(watcher);
                    updateCustomCanValidation(editor, error, add);
                    add.setOnClickListener(
                            v -> {
                                if (!SteeringCanCommandPolicy.isValid(
                                        editor.getText().toString())) {
                                    return;
                                }
                                appendSteerAction(
                                        key,
                                        SteeringCanCommandPolicy.actionId(
                                                editor.getText().toString()));
                                dialog.dismiss();
                            });
                    editor.requestFocus();
                });
        dialog.show();
    }

    private void updateCustomCanValidation(EditText editor, TextView error, Button add) {
        String compact = SteeringCanCommandPolicy.compact(editor.getText().toString());
        boolean valid = compact.length() == SteeringCanCommandPolicy.HEX_LENGTH;
        editor.setBackgroundColor(valid ? Color.WHITE : 0xffffafaf);
        error.setText(
                valid
                        ? "Команда готова"
                        : "Нужно 20 hex-символов (10 байт). Сейчас: " + compact.length());
        error.setTextColor(valid ? 0xff8bc9a3 : 0xffff8a80);
        add.setEnabled(valid);
        add.setAlpha(valid ? 1f : 0.4f);
    }

    private void appendSteerAction(String key, String action) {
        if (voiceOwnsSlot(key)) {
            return;
        }
        List<String> actions = SteeringActionStore.load(preferences, key);
        actions.add(action);
        SteeringActionStore.save(preferences, key, actions);
        refreshSteerActions();
        pushSteerConfig();
    }

    private boolean voiceOwnsSlot(String key) {
        return VoiceSteeringPolicy.ownsSlot(
                preferences.getBoolean(VoiceCommands.ENABLED, false),
                preferences.getString(VoiceSteeringPolicy.PRESS_KEY, VoiceSteeringPolicy.LONG),
                key);
    }

    private void refreshSteerActions() {
        if (steeringActions == null) {
            return;
        }
        String button =
                selectedSteeringTab == R.id.settingsStarTab
                        ? "steerStar"
                        : selectedSteeringTab == R.id.settingsDvrTab
                                ? "steerDvr"
                                : selectedSteeringTab == R.id.settingsVoiceTab
                                        ? "steerVoice"
                                        : "steerPhone";
        String shortKey = button + "Short";
        String longKey = button + "Long";
        List<String> shortActions = SteeringActionStore.load(preferences, shortKey);
        List<String> longActions = SteeringActionStore.load(preferences, longKey);
        int count =
                Math.max(
                        1,
                        Math.max(
                                voiceOwnsSlot(shortKey) ? 1 : shortActions.size(),
                                voiceOwnsSlot(longKey) ? 1 : longActions.size()));
        List<SettingsList.Row> rows = new ArrayList<>();
        rows.add(
                new SettingsList.Row(
                        button + ":header",
                        parent ->
                                steeringPair(
                                        steeringHeading("Короткое нажатие", "Касание"),
                                        steeringHeading("Долгое нажатие", "~0,6 с"))));
        for (int index = 0; index < count; index++) {
            final int actionIndex = index;
            rows.add(
                    new SettingsList.Row(
                            button + ":action:" + index,
                            parent ->
                                    steeringPair(
                                            steeringActionFragment(
                                                    parent, shortKey, shortActions, actionIndex),
                                            steeringActionFragment(
                                                    parent, longKey, longActions, actionIndex))));
        }
        rows.add(
                new SettingsList.Row(
                        button + ":footer",
                        parent -> steeringPair(steeringFooter(shortKey), steeringFooter(longKey))));
        steeringActions.submit(rows, false);
    }

    private SettingsGrid steeringPair(View shortPress, View longPress) {
        SettingsGrid grid = new SettingsGrid(this, null);
        grid.addView(shortPress);
        grid.addView(longPress);
        return grid;
    }

    private View steeringHeading(String title, String hint) {
        SettingsComponents components = new SettingsComponents(this);
        LinearLayout panel =
                components.column(
                        components.text(title, 22, getColor(R.color.settings_text)),
                        components.text(hint, 15, getColor(R.color.settings_muted)));
        int padding = SettingsDesign.dp(panel, 25);
        panel.setPadding(padding, padding, padding, SettingsDesign.dp(panel, 20));
        return widgetFragment(panel, true, false);
    }

    private View steeringActionFragment(
            LinearLayout parent, String key, List<String> actions, int index) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsDesign.dp(panel, 25);
        panel.setPadding(padding, 0, padding, 0);
        if (voiceOwnsSlot(key)) {
            if (index == 0) {
                View reserved =
                        LayoutInflater.from(this)
                                .inflate(R.layout.settings_steering_reserved, panel, false);
                reserved.findViewById(R.id.settingsOpenVoice)
                        .setOnClickListener(view -> setSection(SettingsSection.VOICE));
                panel.addView(reserved);
            }
        } else if (actions.isEmpty() && index == 0) {
            TextView empty = new TextView(this);
            empty.setText("Штатное поведение\nДействия не назначены");
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, SettingsDesign.dp(empty, 22), 0, SettingsDesign.dp(empty, 22));
            empty.setTextColor(0xff8b9cb4);
            empty.setTextSize(18f);
            panel.addView(empty);
        } else if (index < actions.size()) {
            panel.addView(steeringActionRow(parent, key, actions, index));
        }
        SettingsDesign.styleTree(panel);
        return widgetFragment(panel, false, false);
    }

    private View steeringFooter(String key) {
        LinearLayout panel = new LinearLayout(this);
        int padding = SettingsDesign.dp(panel, 25);
        panel.setPadding(padding, SettingsDesign.dp(panel, 16), padding, padding);
        if (!voiceOwnsSlot(key)) {
            Button add = new com.google.android.material.button.MaterialButton(this);
            add.setText("+ Добавить действие");
            add.setTag("settings.add");
            add.setOnClickListener(view -> pickSteerAction(key));
            panel.addView(add, new LinearLayout.LayoutParams(-1, -2));
        }
        SettingsDesign.styleTree(panel);
        return widgetFragment(panel, false, true);
    }

    private View steeringActionRow(
            LinearLayout parent, String key, List<String> actions, int index) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_steering_action, parent, false);
        TextView label = row.findViewById(R.id.steerActionLabel);
        ImageButton delete = row.findViewById(R.id.steerActionDelete);
        label.setText(steerActionLabel(actions.get(index)));
        ((TextView) row.findViewById(R.id.settingsSteerIndex)).setText(String.valueOf(index + 1));
        View up = row.findViewById(R.id.settingsSteerUp),
                down = row.findViewById(R.id.settingsSteerDown);
        up.setEnabled(index > 0);
        down.setEnabled(index + 1 < actions.size());
        up.setOnClickListener(v -> moveSteerAction(key, index, -1));
        down.setOnClickListener(v -> moveSteerAction(key, index, 1));
        delete.setOnClickListener(
                v -> {
                    List<String> current = SteeringActionStore.load(preferences, key);
                    if (index < 0 || index >= current.size()) {
                        return;
                    }
                    current.remove(index);
                    SteeringActionStore.save(preferences, key, current);
                    refreshSteerActions();
                    pushSteerConfig();
                });
        SettingsDesign.styleTree(row);
        return row;
    }

    private void moveSteerAction(String key, int index, int delta) {
        List<String> actions = SteeringActionStore.load(preferences, key);
        int target = index + delta;
        if (index < 0 || index >= actions.size() || target < 0 || target >= actions.size()) {
            return;
        }
        java.util.Collections.swap(actions, index, target);
        SteeringActionStore.save(preferences, key, actions);
        refreshSteerActions();
        pushSteerConfig();
    }

    private String steerActionLabel(String id) {
        if (id == null || id.isEmpty()) {
            return "Не менять";
        }
        for (String[] a : STEER_ACTIONS) {
            if (a[0].equals(id)) {
                return a[1];
            }
        }
        if (id.startsWith("split:")) {
            try {
                int n = Integer.parseInt(id.substring("split:".length()));
                java.util.List<SplitStore.Preset> all = SplitStore.load(preferences);
                if (n >= 0 && n < all.size()) {
                    SplitStore.Preset preset = all.get(n);
                    return "Сплит: "
                            + (preset.ll.isEmpty() ? preset.l : preset.ll)
                            + " / "
                            + (preset.rl.isEmpty() ? preset.r : preset.rl);
                }
            } catch (Exception ignored) {
            }
            return "Сплит (не найден)";
        }
        if (id.startsWith("app:")) {
            String packageName = id.substring("app:".length());
            try {
                android.content.pm.PackageManager packageManager = getPackageManager();
                return "Приложение: "
                        + packageManager
                                .getApplicationLabel(
                                        packageManager.getApplicationInfo(packageName, 0))
                                .toString();
            } catch (Exception e) {
                return "Приложение: " + packageName;
            }
        }
        if (id.startsWith("call:")) {
            return "Набрать номер: " + id.substring("call:".length());
        }
        if (id.startsWith("can:")) {
            String command = id.substring("can:".length());
            return command.length() == SteeringCanCommandPolicy.HEX_LENGTH
                    ? "Своя команда: " + SteeringCanCommandPolicy.format(command)
                    : "Своя команда (неверный формат)";
        }
        return "Неизвестное действие: " + id;
    }

    private void pushSteerConfig() {
        SplitConfigSync.pushSteering(this, preferences);
    }

    private void pushDockConfig() {
        SplitConfigSync.pushDock(this, preferences);
    }

    private void initPedestrianSoundGroup() {
        RadioGroup group = settingView(R.id.pedestrianSoundGroup);
        if (group == null) {
            return;
        }
        boolean disabled = preferences.getBoolean("disablePedestrianSound", false);
        group.check(disabled ? R.id.pedestrianSoundOn : R.id.pedestrianSoundOff);
        group.setOnCheckedChangeListener(
                (g, checkedId) -> {
                    if (syncingSettingUi) {
                        return;
                    }
                    boolean off = (checkedId == R.id.pedestrianSoundOn);
                    preferences.edit().putBoolean("disablePedestrianSound", off).apply();
                    Log.i("$$$ Advance pedestrian $$$", off ? "DISABLED (muted)" : "ENABLED");
                });
    }

    private void initSuspensionMaintenance() {
        Switch toggle = settingView(R.id.switchSuspensionMaintenance);
        toggle.setChecked(preferences.getBoolean("suspensionMaintenance", false));
        toggle.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (syncingSettingUi) {
                        return;
                    }
                    preferences.edit().putBoolean("suspensionMaintenance", enabled).apply();
                    if (GlobalVars.isBound && GlobalVars.serviceMessenger != null) {
                        try {
                            GlobalVars.serviceMessenger.send(
                                    Message.obtain(
                                            null,
                                            MSG_APPLY_SUSPENSION_MAINTENANCE,
                                            enabled ? 1 : 0,
                                            0));
                        } catch (RemoteException e) {
                            Log.w("VoyahSuspension", "Service unavailable", e);
                        }
                    }
                });
    }

    private void initForcedEvGroup() {
        RadioGroup group = settingView(R.id.forcedEvGroup);
        if (group == null) {
            return;
        }
        boolean on = preferences.getBoolean("forcedEv", false);
        group.check(on ? R.id.forcedEvOn : R.id.forcedEvOff);
        group.setOnCheckedChangeListener(
                (g, checkedId) -> {
                    if (syncingSettingUi) {
                        return;
                    }
                    boolean enabled = (checkedId == R.id.forcedEvOn);
                    preferences.edit().putBoolean("forcedEv", enabled).apply();
                    sendForcedEv(enabled);
                    Log.i("$$$ Advance forcedEV $$$", enabled ? "ON" : "OFF");
                });
    }

    private void sendForcedEv(boolean on) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance forcedEV $$$", "SetModesService не забинден");
            return;
        }
        try {
            GlobalVars.serviceMessenger.send(
                    Message.obtain(null, MSG_APPLY_FORCED_EV, on ? 1 : 0, 0));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void initModeRadios() {
        RadioGroup drive = settingView(R.id.drive_modes_group);
        RadioGroup energy = settingView(R.id.energy_modes_group);
        View intelligent = settingView(R.id.SMART);
        if (intelligent != null) {
            intelligent.setVisibility(
                    preferences.getBoolean("checkBox34", false) ? View.GONE : View.VISIBLE);
        }
        RadioGroup recycle = settingView(R.id.recycle_modes_group);
        checkRadioByTag(drive, preferences.getString("driveMode", "INDIVIDUAL"));
        checkRadioByTag(energy, preferences.getString("energy", "SREV"));
        checkRadioByTag(recycle, preferences.getString("recycle", "LOW"));
        if (drive != null) {
            drive.setOnCheckedChangeListener((g, id) -> saveRadio("driveMode", id));
        }
        // Selecting the already checked pinned profile also relinquishes a widget override.
        if (drive != null) {
            for (int i = 0; i < drive.getChildCount(); i++) {
                View child = drive.getChildAt(i);
                if (child instanceof RadioButton) {
                    child.setOnClickListener(v -> saveRadio("driveMode", v.getId()));
                }
            }
        }
        if (energy != null) {
            energy.setOnCheckedChangeListener((g, id) -> saveRadio("energy", id));
        }
        if (recycle != null) {
            recycle.setOnCheckedChangeListener((g, id) -> saveRadio("recycle", id));
        }
    }

    private void checkRadioByTag(RadioGroup group, String value) {
        if (group == null || value == null) {
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof RadioButton && value.equals(child.getTag())) {
                ((RadioButton) child).setChecked(true);
                return;
            }
        }
    }

    private void saveRadio(String key, int checkedId) {
        if (syncingModeUi) {
            return;
        }
        View v = settingView(checkedId);
        if (v != null && v.getTag() != null) {
            if ("driveMode".equals(key)) {
                DriveSelectionPreferences.select(
                        preferences,
                        v.getTag().toString(),
                        ru.big.town.common.DriveSelectionPolicy.SETTINGS);
            } else if ("energy".equals(key)) {
                DriveSelectionPreferences.selectEnergy(preferences, v.getTag().toString(), true);
            } else {
                preferences.edit().putString(key, v.getTag().toString()).apply();
            }
            Log.i("$$$ Advance mode $$$", key + "=" + v.getTag());
        }
    }

    private void initModeEnableToggles() {
        setupEnableSwitch(R.id.switchDriveMode, R.id.drive_modes_group, "driveEnabled");
        setupEnableSwitch(R.id.switchEnergy, R.id.energy_modes_group, "energyEnabled");
        setupEnableSwitch(R.id.switchRecycle, R.id.recycle_modes_group, "recycleEnabled");
    }

    private void initModeRememberLastToggles() {
        bindRememberLastSwitch(R.id.switchDriveRememberLast, "driveRememberLast", "driveMode");
        bindRememberLastSwitch(R.id.switchEnergyRememberLast, "energyRememberLast", "energy");
        bindRememberLastSwitch(R.id.switchRecycleRememberLast, "recycleRememberLast", "recycle");
    }

    /**
     * Remember-last is opt-out: an absent preference (including an upgraded installation) is on.
     * Native also receives the change immediately so already-running vehicle feedback cannot move
     * the selector after the user explicitly switches this off.
     */
    private void bindRememberLastSwitch(int switchId, String prefKey, String modeKey) {
        Switch visibilitySwitch = settingView(switchId);
        if (visibilitySwitch == null) {
            return;
        }
        visibilitySwitch.setChecked(preferences.getBoolean(prefKey, true));
        visibilitySwitch.setOnCheckedChangeListener(
                (button, checked) -> {
                    preferences.edit().putBoolean(prefKey, checked).apply();
                    Intent changed =
                            new Intent(ACTION_MODE_REMEMBER_CHANGED)
                                    .setPackage(NATIVE_PACKAGE)
                                    .putExtra(EXTRA_MODE_KEY, modeKey)
                                    .putExtra(EXTRA_REMEMBER_LAST, checked);
                    sendBroadcast(changed);
                });
    }

    private void initFragranceSettings() {
        Switch enabledSwitch = settingView(R.id.switchFragrance);
        RadioGroup tasteGroup = settingView(R.id.fragranceTasteGroup);
        RadioGroup durationGroup = settingView(R.id.fragranceDurationGroup);
        RadioGroup intensityGroup = settingView(R.id.fragranceIntensityGroup);

        int taste =
                FragranceSettings.normalizeTaste(
                        preferences.getInt(
                                FragranceSettings.TASTE, FragranceSettings.DEFAULT_TASTE));
        int duration =
                FragranceSettings.normalizeDuration(
                        preferences.getInt(
                                FragranceSettings.DURATION, FragranceSettings.DEFAULT_DURATION));
        int intensity =
                FragranceSettings.normalizeIntensity(
                        preferences.getInt(
                                FragranceSettings.INTENSITY, FragranceSettings.DEFAULT_INTENSITY));
        checkRadioByTag(tasteGroup, String.valueOf(taste));
        checkRadioByTag(durationGroup, String.valueOf(duration));
        checkRadioByTag(intensityGroup, String.valueOf(intensity));

        bindIntRadio(tasteGroup, FragranceSettings.TASTE);
        bindIntRadio(durationGroup, FragranceSettings.DURATION);
        bindIntRadio(intensityGroup, FragranceSettings.INTENSITY);

        boolean enabled =
                preferences.getBoolean(
                        FragranceSettings.ENABLED, FragranceSettings.DEFAULT_ENABLED);
        if (enabledSwitch != null) {
            enabledSwitch.setChecked(enabled);
            enabledSwitch.setOnCheckedChangeListener(
                    (button, checked) -> {
                        preferences.edit().putBoolean(FragranceSettings.ENABLED, checked).apply();
                        applyFragranceEnabled(checked);
                    });
        }
        applyFragranceEnabled(enabled);
    }

    private void bindIntRadio(RadioGroup group, String key) {
        if (group == null) {
            return;
        }
        group.setOnCheckedChangeListener(
                (g, checkedId) -> {
                    View selected = settingView(checkedId);
                    if (selected == null || selected.getTag() == null) {
                        return;
                    }
                    try {
                        preferences
                                .edit()
                                .putInt(key, Integer.parseInt(selected.getTag().toString()))
                                .apply();
                    } catch (NumberFormatException e) {
                        Log.e("$$$ Advance fragrance $$$", "Invalid " + key + " tag", e);
                    }
                });
    }

    private void applyFragranceEnabled(boolean enabled) {
        applyModeToggle(R.id.fragranceTasteGroup, enabled);
        applyModeToggle(R.id.fragranceDurationGroup, enabled);
        applyModeToggle(R.id.fragranceIntensityGroup, enabled);
    }

    private void setupEnableSwitch(int switchId, int groupId, String key) {
        Switch visibilitySwitch = settingView(switchId);
        if (visibilitySwitch == null) {
            return;
        }
        boolean enabled = preferences.getBoolean(key, false);
        visibilitySwitch.setChecked(enabled);
        applyModeToggle(groupId, enabled);
        visibilitySwitch.setOnCheckedChangeListener(
                (commandButton, checked) -> {
                    preferences.edit().putBoolean(key, checked).apply();
                    applyModeToggle(groupId, checked);
                });
    }

    private void applyModeToggle(int groupId, boolean enabled) {
        RadioGroup group = settingView(groupId);
        if (group == null) {
            return;
        }
        group.setAlpha(enabled ? 1.0f : 0.4f);
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setEnabled(enabled);
            group.getChildAt(i).setClickable(enabled);
        }
    }

    private void initCheckBox34() {
        checkBox34 = settingView(R.id.checkBox34);
        checkBox34.setChecked(preferences.getBoolean("checkBox34", false));
        checkBox34.setText("");
    }

    public void onCheckBox34Click(View view) {
        boolean hidden = ((CheckBox) view).isChecked();
        preferences.edit().putBoolean("checkBox34", hidden).apply();
        View intelligent = findViewById(R.id.SMART);
        if (intelligent != null) {
            intelligent.setVisibility(hidden ? View.GONE : View.VISIBLE);
        }
    }

    private void initAutoLight() {
        autoLightGroup = settingView(R.id.autoLightGroup);
        textSensorLevel = settingView(R.id.textSensorLevel);
        if (autoLightGroup == null) {
            return;
        }

        boolean on = preferences.getBoolean("autoLight", false);
        autoLightGroup.check(on ? R.id.autoLightOn : R.id.autoLightOff);
        autoLightGroup.setOnCheckedChangeListener(
                (group, checkedId) -> {
                    if (syncingSettingUi) {
                        return;
                    }
                    boolean enabled = (checkedId == R.id.autoLightOn);
                    preferences.edit().putBoolean("autoLight", enabled).apply();
                    sendAutoLightMessage(enabled);
                    if (!enabled && textSensorLevel != null) {
                        textSensorLevel.setText("Датчик: —");
                    }
                    Log.i("$$$ Advance autolight $$$", enabled ? "ON" : "OFF");
                });
    }

    private void sendAutoLightMessage(boolean enable) {
        int what = enable ? MSG_AUTO_LIGHT_ENABLE : MSG_AUTO_LIGHT_DISABLE;
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w(
                    "$$$ Advance autolight $$$",
                    "SetModesService не забинден — состояние применится позже");
            return;
        }
        try {
            GlobalVars.serviceMessenger.send(Message.obtain(null, what));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        TripLocationPermission.result(this, request);
        if (voiceSettings != null) {
            voiceSettings.onPermissionResult(request, grants);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TripLocationPermission.refresh(this);
        TripLocationPermission.sync(this);
        activityResumed = true;
        refreshSteerActions();
        if (currentSection == SettingsSection.VOICE && voiceSettings != null) {
            voiceSettings.refresh();
        }
        if (autoLightGroup != null) {
            syncingSettingUi = true;
            autoLightGroup.check(
                    preferences.getBoolean("autoLight", false)
                            ? R.id.autoLightOn
                            : R.id.autoLightOff);
            syncingSettingUi = false;
        }
        updateSystemMetricsPolling();
        updateLightDiagnosticsBinding();
        IntentFilter filter = new IntentFilter("ru.big.town.anative.LUX_UPDATE");
        registerReceiver(luxReceiver, filter, RECEIVER_EXPORTED);
        registerReceiver(
                modeSyncReceiver,
                new IntentFilter("ru.big.town.anative.MODE_SYNCED"),
                RECEIVER_EXPORTED);
        registerReceiver(
                settingSyncReceiver,
                new IntentFilter("ru.big.town.anative.SETTING_SYNCED"),
                "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE",
                null,
                RECEIVER_EXPORTED);
        Intent req = new Intent("ru.big.town.anative.REQUEST_LUX_UPDATE");
        req.setPackage("ru.big.town.anative");
        sendBroadcast(req);
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        updateSystemMetricsPolling();
        updateLightDiagnosticsBinding();
        super.onPause();
        try {
            unregisterReceiver(luxReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(modeSyncReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(settingSyncReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        activityResumed = false;
        systemMetricsActive = false;
        ++systemMetricsGeneration;
        uiHandler.removeCallbacks(systemMetricsTick);
        systemMetricsExecutor.shutdownNow();
        if (voiceSettings != null) {
            voiceSettings.close();
        }
        super.onDestroy();
    }

    private View dialRow(LinearLayout parent, DialWidgetStore.Entry entry) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View row = inflater.inflate(R.layout.item_dial_widget_setting, parent, false);
        EditText name = row.findViewById(R.id.dialSettingName);
        EditText number = row.findViewById(R.id.dialSettingNumber);
        settingsList.bindDraft(name, "dialName:" + entry.id, entry.name);
        settingsList.bindDraft(number, "dialNumber:" + entry.id, entry.number);
        row.findViewById(R.id.dialSettingSave)
                .setOnClickListener(
                        v -> {
                            String valueName = name.getText().toString().trim();
                            String valueNumber =
                                    number.getText().toString().replaceAll("[^0-9]", "");
                            if (valueName.isEmpty()) {
                                name.setError("Введите имя");
                                return;
                            }
                            if (valueNumber.length() < 4 || valueNumber.length() > 10) {
                                number.setError("Введите от 4 до 10 цифр");
                                return;
                            }
                            List<DialWidgetStore.Entry> entries = DialWidgetStore.load(preferences);
                            for (DialWidgetStore.Entry current : entries) {
                                if (current.id.equals(entry.id)) {
                                    current.name = valueName;
                                    current.number = valueNumber;
                                    break;
                                }
                            }
                            DialWidgetStore.save(preferences, entries);
                            TileOrderStore.sync(preferences, getPackageManager());
                            android.widget.Toast.makeText(
                                            this,
                                            "Карточка сохранена",
                                            android.widget.Toast.LENGTH_SHORT)
                                    .show();
                        });
        row.findViewById(R.id.dialSettingDelete)
                .setOnClickListener(
                        v -> {
                            List<DialWidgetStore.Entry> entries = DialWidgetStore.load(preferences);
                            entries.removeIf(current -> current.id.equals(entry.id));
                            DialWidgetStore.save(preferences, entries);
                            TileOrderStore.sync(preferences, getPackageManager());
                            refreshSettingsRows();
                        });
        SettingsDesign.styleTree(row);

        return row;
    }

    private View shortcutRow(LinearLayout parent, String packageName) {
        LayoutInflater inflater = LayoutInflater.from(this);
        android.content.pm.PackageManager packageManager = getPackageManager();
        View row = inflater.inflate(R.layout.item_app_shortcut, parent, false);
        android.widget.ImageView icon = row.findViewById(R.id.shortcutIco);
        TextView label = row.findViewById(R.id.shortcutLabel);
        ImageButton deleteButton = row.findViewById(R.id.shortcutDelete);
        String name = packageName;
        try {
            android.content.pm.ApplicationInfo applicationInfo =
                    packageManager.getApplicationInfo(packageName, 0);
            name = packageManager.getApplicationLabel(applicationInfo).toString();
            icon.setImageDrawable(packageManager.getApplicationIcon(applicationInfo));
        } catch (Exception ignored) {
        }
        label.setText(name);
        deleteButton.setOnClickListener(
                v -> {
                    java.util.List<String> updatedPackages = AppShortcutStore.load(preferences);
                    updatedPackages.remove(packageName);
                    AppShortcutStore.save(preferences, updatedPackages);

                    TileOrderStore.sync(preferences, getPackageManager());
                    renderAppShortcuts();
                });
        SettingsDesign.styleTree(row);

        return row;
    }

    private View appWidgetRow(LinearLayout parent, AppWidgetStore.Entry entry) {
        LayoutInflater inflater = LayoutInflater.from(this);
        android.content.pm.PackageManager packageManager = getPackageManager();
        entry.ensureProfiles();
        View row = inflater.inflate(R.layout.settings_main_app_widget_header, parent, false);
        android.widget.ImageView icon = row.findViewById(R.id.appWidgetSettingIcon);
        TextView label = row.findViewById(R.id.appWidgetSettingLabel);
        ImageButton delete = row.findViewById(R.id.appWidgetSettingDelete);
        android.widget.Spinner widthSpinner = row.findViewById(R.id.appWidgetSettingWidth);
        android.widget.Spinner heightSpinner = row.findViewById(R.id.appWidgetSettingHeight);
        android.widget.Switch autoStart = row.findViewById(R.id.appWidgetSettingAutoStart);
        String name = entry.packageName;
        try {
            android.content.pm.ApplicationInfo info =
                    packageManager.getApplicationInfo(entry.packageName, 0);
            name = packageManager.getApplicationLabel(info).toString();
            icon.setImageDrawable(packageManager.getApplicationIcon(info));
        } catch (Exception ignored) {
        }
        label.setText("Виджет " + AppWidgetStore.designation(preferences, entry.id) + ": " + name);
        ((TextView) row.findViewById(R.id.settingsAppWidgetSubtitle))
                .setText(entry.profiles.size() + " приложений · запуск внутри карточки");
        android.widget.ArrayAdapter<String> widthAdapter =
                new android.widget.ArrayAdapter<>(
                        this,
                        R.layout.settings_spinner_item,
                        new String[] {
                            "1 ячейка",
                            "2 ячейки",
                            "3 ячейки",
                            "4 ячейки",
                            "5 ячеек",
                            "6 ячеек",
                            "7 ячеек",
                            "8 ячеек",
                            "9 ячеек",
                            "10 ячеек",
                            "11 ячеек",
                            "12 ячеек"
                        });
        widthAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        widthSpinner.setAdapter(widthAdapter);
        widthSpinner.setSelection(AppWidgetStore.clampWidth(entry.width) - 1);
        widthSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        int value = position + 1;
                        if (entry.width != value) {
                            entry.width = value;
                            AppWidgetStore.update(preferences, entry);
                            TileOrderStore.sync(preferences, getPackageManager());
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
        android.widget.ArrayAdapter<String> heightAdapter =
                new android.widget.ArrayAdapter<>(
                        this,
                        R.layout.settings_spinner_item,
                        new String[] {"1 ячейка", "2 ячейки", "3 ячейки", "4 ячейки", "5 ячеек"});
        heightAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        heightSpinner.setAdapter(heightAdapter);
        heightSpinner.setSelection(AppWidgetStore.clampHeight(entry.height) - 1);
        heightSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        int value = position + 1;
                        if (entry.height != value) {
                            entry.height = value;
                            AppWidgetStore.update(preferences, entry);
                            TileOrderStore.sync(preferences, getPackageManager());
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
        autoStart.setChecked(entry.autoStart);

        View delayContainer = row.findViewById(R.id.appWidgetDelayContainer);
        android.widget.SeekBar delaySeek = row.findViewById(R.id.appWidgetSettingDelay);
        TextView delayText = row.findViewById(R.id.appWidgetSettingDelayText);

        delayContainer.setVisibility(entry.autoStart ? View.VISIBLE : View.GONE);
        delaySeek.setProgress(entry.autoStartDelay - 1);
        delayText.setText(entry.autoStartDelay + " сек");

        delaySeek.setOnSeekBarChangeListener(
                new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                        int value = progress + 1;
                        delayText.setText(value + " сек");
                        if (fromUser) {
                            entry.autoStartDelay = value;
                            AppWidgetStore.update(preferences, entry);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
                });

        autoStart.setOnCheckedChangeListener(
                (button, checked) -> {
                    entry.autoStart = checked;
                    delayContainer.setVisibility(checked ? View.VISIBLE : View.GONE);
                    AppWidgetStore.update(preferences, entry);
                    TileOrderStore.sync(preferences, getPackageManager());
                });
        delete.setContentDescription("Убрать виджет приложения");
        delete.setOnClickListener(
                v -> {
                    AppWidgetStore.remove(preferences, entry.id);
                    TileOrderStore.sync(preferences, getPackageManager());
                    renderAppWidgets();
                });
        SettingsDesign.styleTree(row);

        return row;
    }

    private View appWidgetFooterRow(LinearLayout parent, AppWidgetStore.Entry entry) {
        View row =
                LayoutInflater.from(this)
                        .inflate(R.layout.settings_main_app_widget_footer, parent, false);
        Button addProfile = row.findViewById(R.id.appWidgetAddProfile);
        addProfile.setOnClickListener(
                v ->
                        showAppPicker(
                                "Добавить приложение в виджет",
                                (packageName, pickedLabel) -> {
                                    AppWidgetStore.addProfile(
                                            entry, packageName, AppWidgetStore.DEFAULT_DPI);
                                    AppWidgetStore.update(preferences, entry);
                                    TileOrderStore.sync(preferences, getPackageManager());
                                    renderAppWidgets();
                                }));
        SettingsDesign.styleTree(row);
        return row;
    }

    private View widgetFragment(View content, boolean first, boolean last) {
        content.setBackground(
                new SettingsPanelDrawable(
                        getResources().getDisplayMetrics().density,
                        getColor(R.color.settings_surface),
                        getColor(R.color.settings_border),
                        first,
                        last));
        return content;
    }

    private View widgetProfileFragment(LinearLayout parent, AppWidgetStore.Entry entry, int index) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsDesign.dp(panel, 25);
        panel.setPadding(padding, 0, padding, 0);
        if (index < entry.profiles.size()) {
            panel.addView(appWidgetProfileRow(parent, entry, index));
        }
        return widgetFragment(panel, false, false);
    }

    private void addAppWidgetRows(List<SettingsList.Row> rows) {
        List<AppWidgetStore.Entry> entries = AppWidgetStore.load(preferences);
        for (int index = 0; index < entries.size(); index += 2) {
            List<AppWidgetStore.Entry> pair =
                    new ArrayList<>(entries.subList(index, Math.min(index + 2, entries.size())));
            String key = "widgets:" + pair.get(0).id;
            rows.add(
                    new SettingsList.Row(
                            key,
                            parent -> {
                                SettingsGrid grid = new SettingsGrid(this, null);
                                for (AppWidgetStore.Entry entry : pair) {
                                    grid.addView(
                                            widgetFragment(
                                                    appWidgetRow(parent, entry), true, false));
                                }
                                return grid;
                            }));
            int profileCount = 0;
            for (AppWidgetStore.Entry entry : pair) {
                profileCount = Math.max(profileCount, entry.profiles.size());
            }
            for (int profileIndex = 0; profileIndex < profileCount; profileIndex++) {
                final int currentProfile = profileIndex;
                rows.add(
                        new SettingsList.Row(
                                key + ":profile:" + profileIndex,
                                parent -> {
                                    SettingsGrid grid = new SettingsGrid(this, null);
                                    for (AppWidgetStore.Entry entry : pair) {
                                        grid.addView(
                                                widgetProfileFragment(
                                                        parent, entry, currentProfile));
                                    }
                                    return grid;
                                }));
            }
            rows.add(
                    new SettingsList.Row(
                            key + ":footer",
                            parent -> {
                                SettingsGrid grid = new SettingsGrid(this, null);
                                for (AppWidgetStore.Entry entry : pair) {
                                    grid.addView(
                                            widgetFragment(
                                                    appWidgetFooterRow(parent, entry),
                                                    false,
                                                    true));
                                }
                                return spaced(grid);
                            }));
        }
    }

    private View fullscreenRow(LinearLayout parent, String packageName) {
        LayoutInflater inflater = LayoutInflater.from(this);
        android.content.pm.PackageManager packageManager = getPackageManager();
        View row = inflater.inflate(R.layout.item_app_shortcut, parent, false);
        android.widget.ImageView icon = row.findViewById(R.id.shortcutIco);
        TextView label = row.findViewById(R.id.shortcutLabel);
        ImageButton delete = row.findViewById(R.id.shortcutDelete);
        String name = packageName;
        try {
            android.content.pm.ApplicationInfo info =
                    packageManager.getApplicationInfo(packageName, 0);
            name = packageManager.getApplicationLabel(info).toString();
            icon.setImageResource(R.drawable.settings_icon_fullscreen);
        } catch (Exception ignored) {
        }
        label.setText(name);
        delete.setContentDescription("Убрать из полноэкранных приложений");
        delete.setOnClickListener(
                v -> {
                    java.util.List<String> next = FullscreenAppStore.load(preferences);
                    next.remove(packageName);
                    saveFullscreenApps(next);
                });
        SettingsDesign.styleTree(row);

        return row;
    }

    private View splitRow(LinearLayout parent, SplitStore.Preset preset, int presetIndex) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View row = inflater.inflate(R.layout.item_split_preset, parent, false);
        SettingsPreviewView preview = row.findViewById(R.id.settingsSplitPreview);
        preview.setSplit(preset);
        ((TextView) row.findViewById(R.id.settingsSplitTitle))
                .setText("Сплит " + (presetIndex + 1));
        TextView ratioLabel = row.findViewById(R.id.settingsSplitRatioLabel);
        ratioLabel.setText(SplitStore.RATIO_LABELS[Math.max(0, Math.min(4, preset.ratio))]);

        Button leftAppButton = row.findViewById(R.id.splitLeftBtn);
        Button rightAppButton = row.findViewById(R.id.splitRightBtn);
        Button deleteButton = row.findViewById(R.id.splitDeleteBtn);
        android.widget.Spinner scaleSpinner = row.findViewById(R.id.splitRatioSpinner);

        leftAppButton.setText(preset.ll.isEmpty() ? "не выбрано" : preset.ll);
        rightAppButton.setText(preset.rl.isEmpty() ? "не выбрано" : preset.rl);

        leftAppButton.setOnClickListener(
                v ->
                        showAppPicker(
                                "Приложение слева",
                                (packageName, label) -> {
                                    java.util.List<SplitStore.Preset> updatedPresets =
                                            SplitStore.load(preferences);
                                    if (presetIndex < updatedPresets.size()) {
                                        updatedPresets.get(presetIndex).l = packageName;
                                        updatedPresets.get(presetIndex).ll = label;
                                        saveSplitPresets(updatedPresets);
                                        renderSplitPresets();
                                    }
                                }));
        rightAppButton.setOnClickListener(
                v ->
                        showAppPicker(
                                "Приложение справа",
                                (packageName, label) -> {
                                    java.util.List<SplitStore.Preset> updatedPresets =
                                            SplitStore.load(preferences);
                                    if (presetIndex < updatedPresets.size()) {
                                        updatedPresets.get(presetIndex).r = packageName;
                                        updatedPresets.get(presetIndex).rl = label;
                                        saveSplitPresets(updatedPresets);
                                        renderSplitPresets();
                                    }
                                }));

        android.widget.ArrayAdapter<String> spinnerAdapter =
                new android.widget.ArrayAdapter<>(
                        this, R.layout.settings_spinner_item, SplitStore.RATIO_LABELS);
        spinnerAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        scaleSpinner.setAdapter(spinnerAdapter);
        scaleSpinner.setSelection(preset.ratio, false);
        SettingsChoiceGroup ratios = row.findViewById(R.id.settingsSplitRatios);
        for (int ratioIndex = 0; ratioIndex < SplitStore.RATIO_LABELS.length; ratioIndex++) {
            RadioButton choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(SplitStore.RATIO_LABELS[ratioIndex]);
            choice.setTag(ratioIndex);
            ratios.addView(choice);
            if (ratioIndex == preset.ratio) {
                ratios.check(choice.getId());
            }
        }
        ratios.setOnCheckedChangeListener(
                (group, checkedId) -> {
                    View selected = group.findViewById(checkedId);
                    if (selected != null) {
                        scaleSpinner.setSelection((Integer) selected.getTag());
                    }
                });
        scaleSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        java.util.List<SplitStore.Preset> updatedPresets =
                                SplitStore.load(preferences);
                        if (presetIndex < updatedPresets.size()
                                && updatedPresets.get(presetIndex).ratio != position) {
                            updatedPresets.get(presetIndex).ratio = position;
                            updatedPresets.get(presetIndex).split = 0f;
                            preview.setSplit(updatedPresets.get(presetIndex));
                            ratioLabel.setText(
                                    SplitStore.RATIO_LABELS[updatedPresets.get(presetIndex).ratio]);
                            saveSplitPresets(updatedPresets);
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });

        Switch resizable = row.findViewById(R.id.splitResizableSwitch);
        if (resizable != null) {
            resizable.setChecked(preset.resizable);
            resizable.setOnCheckedChangeListener(
                    (b, checked) -> {
                        java.util.List<SplitStore.Preset> updatedPresets =
                                SplitStore.load(preferences);
                        if (presetIndex < updatedPresets.size()) {
                            updatedPresets.get(presetIndex).resizable = checked;
                            if (!checked) {
                                updatedPresets.get(presetIndex).split = 0f;
                            }
                            preview.setSplit(updatedPresets.get(presetIndex));
                            ratioLabel.setText(
                                    SplitStore.RATIO_LABELS[updatedPresets.get(presetIndex).ratio]);
                            saveSplitPresets(updatedPresets);
                        }
                    });
        }

        deleteButton.setOnClickListener(
                v -> {
                    java.util.List<SplitStore.Preset> updatedPresets = SplitStore.load(preferences);
                    if (presetIndex < updatedPresets.size()) {
                        updatedPresets.remove(presetIndex);
                        saveSplitPresets(updatedPresets);
                        renderSplitPresets();
                    }
                });

        SettingsDesign.styleTree(row);

        return row;
    }

    private View dpiRow(LinearLayout parent, String packageName) {
        LayoutInflater inflater = LayoutInflater.from(this);
        android.content.pm.PackageManager packageManager = getPackageManager();
        final String targetPackageName = packageName;
        View row = inflater.inflate(R.layout.item_app_dpi, parent, false);
        android.widget.ImageView icon = row.findViewById(R.id.appDpiIco);
        TextView label = row.findViewById(R.id.appDpiLabel);
        android.widget.Spinner scaleSpinner = row.findViewById(R.id.appDpiSpinner);

        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(packageName));
        } catch (Exception ignored) {
        }
        label.setText(appLabels.get(packageName));

        android.widget.ArrayAdapter<String> spinnerAdapter =
                new android.widget.ArrayAdapter<>(this, R.layout.settings_spinner_item, DPI_LABELS);
        spinnerAdapter.setDropDownViewResource(R.layout.settings_spinner_dropdown);
        scaleSpinner.setAdapter(spinnerAdapter);
        scaleSpinner.setSelection(dpiIndex(AppDpiStore.get(preferences, packageName)), false);
        scaleSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent, View v, int position, long id) {
                        int dpi = DPI_VALUES[position];
                        if (dpi != AppDpiStore.get(preferences, targetPackageName)) {
                            AppDpiStore.set(preferences, targetPackageName, dpi);
                            SplitConfigSync.pushAppDpi(
                                    AdvanceActivity.this, preferences, targetPackageName, dpi);
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });

        SettingsDesign.styleTree(row);
        return row;
    }

    private List<SettingsList.Row> sectionRows(SettingsSection section) {
        List<SettingsList.Row> rows = new ArrayList<>();
        for (SettingsSectionLayouts.Item item : SettingsSectionLayouts.forSection(section)) {
            if (item.layoutResource == R.layout.settings_main_add_shortcut) {
                rows.add(
                        new SettingsList.Row(
                                "layout:" + item.layoutResource,
                                parent -> inflateSettingsRow(item.layoutResource, parent)));
            } else if (item.layoutResource != 0) {
                rows.add(
                        staticRows.computeIfAbsent(
                                item.layoutResource,
                                resource ->
                                        new SettingsList.Row(
                                                "layout:" + resource,
                                                parent -> inflateSettingsRow(resource, parent))));
            } else {
                addDynamicRows(rows, item.dynamicContent);
            }
        }
        return rows;
    }

    private void addDynamicRows(
            List<SettingsList.Row> rows, SettingsSectionLayouts.DynamicContent kind) {
        switch (kind) {
            case APP_WIDGETS:
                addAppWidgetRows(rows);
                break;
            case DIAL_CARDS:
                for (DialWidgetStore.Entry entry : DialWidgetStore.load(preferences)) {
                    rows.add(
                            new SettingsList.Row(
                                    "dial:" + entry.id, parent -> dialRow(parent, entry)));
                }
                break;
            case SHORTCUTS:
                addAppChipRows(
                        rows,
                        AppShortcutStore.load(preferences),
                        "shortcuts",
                        true,
                        this::shortcutRow);
                break;
            case FULLSCREEN_APPS:
                addAppChipRows(
                        rows,
                        FullscreenAppStore.load(preferences),
                        "fullscreen",
                        false,
                        this::fullscreenRow);
                break;
            case SPLITS:
                {
                    List<SplitStore.Preset> presets = SplitStore.load(preferences);
                    for (int i = 0; i < presets.size(); i += 2) {
                        final int start = i;
                        rows.add(
                                new SettingsList.Row(
                                        "split:" + i,
                                        parent -> {
                                            SettingsGrid grid = new SettingsGrid(this, null);
                                            for (int n = start;
                                                    n < Math.min(start + 2, presets.size());
                                                    n++) {
                                                grid.addView(splitRow(parent, presets.get(n), n));
                                            }
                                            return spaced(grid);
                                        }));
                    }
                    break;
                }
            case APP_SCALE:
                loadAppMetadata();
                for (String packageName : appLabels.keySet()) {
                    rows.add(
                            new SettingsList.Row(
                                    "dpi:" + packageName, parent -> dpiRow(parent, packageName)));
                }
                break;
        }
    }

    private View spaced(View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = SettingsDesign.dp(view, 16);
        view.setLayoutParams(params);
        return view;
    }

    private void addAppChipRows(
            List<SettingsList.Row> rows,
            List<String> packages,
            String keyPrefix,
            boolean startsPanel,
            java.util.function.BiFunction<LinearLayout, String, View> chipFactory) {
        // Bound each flow row so long app lists never inflate outside the viewport.
        final int chipsPerRow = 3;
        for (int index = 0; index < packages.size(); index += chipsPerRow) {
            int end = Math.min(index + chipsPerRow, packages.size());
            List<String> group = new ArrayList<>(packages.subList(index, end));
            boolean first = startsPanel && index == 0;
            boolean hasNextRow = end < packages.size();
            rows.add(
                    new SettingsList.Row(
                            keyPrefix + ":" + group.get(0),
                            parent -> {
                                SettingsFlow flow = new SettingsFlow(this, null);
                                for (String packageName : group) {
                                    flow.addView(chipFactory.apply(flow, packageName));
                                }
                                if (hasNextRow) {
                                    LinearLayout.LayoutParams params =
                                            new LinearLayout.LayoutParams(-1, -2);
                                    params.bottomMargin = SettingsDesign.dp(flow, 12);
                                    flow.setLayoutParams(params);
                                }
                                return appSelectionPanel(flow, first, false);
                            }));
        }
    }

    private View appSelectionPanel(View content, boolean first, boolean last) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsDesign.dp(panel, 25);
        panel.setPadding(padding, first ? padding : 0, padding, last ? padding : 0);
        panel.addView(content);
        return widgetFragment(panel, first, last);
    }
}
