package ru.big.town.restoremode.settings.sections.other;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import ru.big.town.restoremode.BuildConfig;
import ru.big.town.restoremode.LoggingActivity;
import ru.big.town.restoremode.OtaActivity;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.diagnostics.metrics.SystemMetricsReader;
import ru.big.town.restoremode.integration.GlobalVars;
import ru.big.town.restoremode.integration.config.SplitConfigSync;
import ru.big.town.restoremode.integration.hooks.HookStatusContract;
import ru.big.town.restoremode.settings.core.SettingsSection;
import ru.big.town.restoremode.settings.core.SettingsSectionFragment;
import ru.big.town.restoremode.settings.ui.controls.SettingsControls;
import ru.big.town.restoremode.settings.ui.dialogs.SettingsAppPicker;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;
import ru.big.town.restoremode.trips.location.TripLocationPermission;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class OtherSettingsFragment extends SettingsSectionFragment {
    @Override
    public SettingsSection section() {
        return SettingsSection.OTHER;
    }

    private static final String PREF_SHOW_CUSTOM_COMMANDS = "showCustomCommands";

    private static final long SYSTEM_METRICS_INTERVAL_MS = SystemMetricsReader.INTERVAL_MS;

    private TextView textRamStatus, textCpuStatus, textHookStatus;

    private boolean sectionResumed;

    private volatile boolean systemMetricsActive;

    private volatile long systemMetricsGeneration;

    private SystemMetricsReader systemMetricsReader;

    private final ExecutorService systemMetricsExecutor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "VoyahTune-system-metrics");
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    });

    static final int MSG_REBOOT = 22;

    static final int MSG_FLOATING_BACK = 24;

    static final int MSG_FLOATING_BACK_SIDE = 25;

    static final int MSG_GRANT_INSTALL = 26;

    static final int MSG_CLOSE_ALL = 27;

    static final int MSG_SET_THEME = 28;

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

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable systemMetricsTick = this::sampleSystemMetrics;

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
                                    ru.big.town.common.InfrastructureProfile.read(requireContext())
                                            == ru.big.town.common.InfrastructureProfile.OD;
                            Intent updates =
                                    new Intent(Intent.ACTION_MAIN)
                                            .setComponent(
                                                    embedded
                                                            ? new android.content.ComponentName(
                                                                    requireContext(),
                                                                    OtaActivity.class)
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
                                                requireContext(),
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
            TextView row = new TextView(requireContext());
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
                        SplitConfigSync.pushKeyboard(requireContext(), preferences);
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
                        SplitConfigSync.pushKeyboard(requireContext(), preferences);
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
            SettingsControls.checkRadioByTag(
                    themeGroup, String.valueOf(preferences.getInt("themeOverride", 0)));
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
                .setOnClickListener(v -> TripLocationPermission.openSettings(requireActivity()));
        settingView(R.id.buttonRequestTripLocation)
                .setOnClickListener(v -> TripLocationPermission.request(requireActivity()));
        settingView(R.id.buttonDisableTripLocation)
                .setOnClickListener(v -> TripLocationPermission.disable(requireActivity()));
        settingsList.post(
                () -> {
                    if (getView() != null) {
                        TripLocationPermission.refresh(requireActivity());
                    }
                });
    }

    private void bindFloatingBackPosition() {
        if (settingView(R.id.floatingBackSideGroup) == null) {
            return;
        }
        RadioGroup sideGroup = settingView(R.id.floatingBackSideGroup);
        if (sideGroup != null) {
            SettingsControls.checkRadioByTag(
                    sideGroup, String.valueOf(preferences.getInt("floatingBackSide", 0)));
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

    private void onButtonCloseAll(View v) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        requireContext(), R.style.SettingsDialog)
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
                                            requireView(),
                                            ok ? "Команда закрытия отправлена" : "Сервис не готов",
                                            com.google.android.material.snackbar.Snackbar
                                                    .LENGTH_LONG)
                                    .show();
                            Log.i("$$$ Advance closeAll $$$", "MSG_CLOSE_ALL sent=" + ok);
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void onButtonGrantInstall(View v) {
        SettingsAppPicker.show(
                requireContext(),
                "Выдать права на установку",
                (packageName, label) -> {
                    sendGrantInstall(packageName);
                    com.google.android.material.snackbar.Snackbar.make(
                                    requireView(),
                                    "Право на установку выдано: " + label,
                                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                            .show();
                });
    }

    private void sendGrantInstall(String packageName) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            Log.w("$$$ Advance grantInstall $$$", "SetModesService не забинден");
            return;
        }
        int uid;
        try {
            uid = requireContext().getPackageManager().getApplicationInfo(packageName, 0).uid;
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

    private void onButtonRebootSystem(View v) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        requireContext(), R.style.SettingsDialog)
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

    private void onButtonLogging(View v) {
        startActivity(new Intent(requireContext(), LoggingActivity.class));
    }

    private void updateLightDiagnosticsBinding() {
        boolean shouldBind =
                sectionResumed
                        && lightDiagnosticsRows[0] != null
                        && lightDiagnosticsRows[0].isShown()
                        && preferences.getBoolean("debugMode", false)
                        && !requireActivity().isFinishing();
        if (shouldBind == lightDiagnosticsActive) {
            return;
        }
        lightDiagnosticsActive = shouldBind;
        if (shouldBind) {
            ++lightDiagnosticsSession;
            resetLightDiagnostics();
            lightSensorManager =
                    (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
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
                        requireContext()
                                .bindService(
                                        intent,
                                        lightDiagnosticsConnection,
                                        Context.BIND_AUTO_CREATE);
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
                    requireContext().unbindService(lightDiagnosticsConnection);
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
                sectionResumed
                        && textRamStatus != null
                        && textRamStatus.isShown()
                        && !requireActivity().isFinishing();
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
        if (!systemMetricsActive) {
            return;
        }
        final long generation = systemMetricsGeneration;
        final Context application = requireContext().getApplicationContext();
        try {
            systemMetricsExecutor.execute(
                    () -> {
                        final SystemMetricsReader.Snapshot snapshot =
                                systemMetricsReader.read(generation);
                        String hookPayload =
                                application
                                        .getSharedPreferences(
                                                HookStatusContract.PREFERENCES_NAME,
                                                Context.MODE_PRIVATE)
                                        .getString(HookStatusContract.PAYLOAD_KEY, null);
                        final String hookStatus = HookStatusContract.renderForUi(hookPayload);
                        uiHandler.post(
                                () -> {
                                    if (!systemMetricsActive
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
                                                                                requireContext(),
                                                                                snapshot.usedMemoryBytes)
                                                                + " из "
                                                                + android.text.format.Formatter
                                                                        .formatFileSize(
                                                                                requireContext(),
                                                                                snapshot.totalMemoryBytes)
                                                                + "\nДоступно: "
                                                                + android.text.format.Formatter
                                                                        .formatFileSize(
                                                                                requireContext(),
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

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        systemMetricsReader = new SystemMetricsReader(requireContext().getApplicationContext());
    }

    @Override
    protected void bindSettingsRow() {
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
        bindClick(R.id.buttonCloseAll, this::onButtonCloseAll);
        bindClick(R.id.buttonGrantInstall, this::onButtonGrantInstall);
        bindClick(R.id.buttonReboot, this::onButtonRebootSystem);
        bindClick(R.id.buttonLogging, this::onButtonLogging);
    }

    @Override
    protected void releaseSettingsRow(View row) {
        if (isDescendant(row, textRamStatus)) {
            textRamStatus = null;
        }
        if (isDescendant(row, textCpuStatus)) {
            textCpuStatus = null;
        }
        if (isDescendant(row, textHookStatus)) {
            textHookStatus = null;
        }
        for (int index = 0; index < lightDiagnosticsRows.length; index++) {
            if (isDescendant(row, lightDiagnosticsRows[index])) {
                lightDiagnosticsRows[index] = null;
            }
        }
        scheduleVisibilityUpdate();
    }

    @Override
    protected void onRowsVisibilityChanged() {
        updateSystemMetricsPolling();
        updateLightDiagnosticsBinding();
    }

    @Override
    public void onResume() {
        super.onResume();
        sectionResumed = true;
        onRowsVisibilityChanged();
    }

    @Override
    public void onPause() {
        sectionResumed = false;
        onRowsVisibilityChanged();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        systemMetricsExecutor.shutdownNow();
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
