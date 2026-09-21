package ru.big.town.restoremode;


import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;


public class MainActivity extends AppCompatActivity {
    private String driveMode="INDIVIDUAL";
    private String energy="SREV";
    private  String recycle="LOW";
    private String customCommand="";
    private int customCommandCount=1;

    private SharedPreferences sharedPreferences;
    static final int MSG_RESULT             = 4;
    static final int MSG_AUTO_LIGHT_ENABLE  = 10;
    static final int MSG_AUTO_LIGHT_DISABLE = 11;
    static final int MSG_LEAVE_CAR          = 20;
    static final int MSG_APPLY_PEDESTRIAN   = 21;
    static final int MSG_WASH_MODE          = 23;
    static final int MSG_SPLIT_LAUNCH_VD    = 34; // single → physical WM-clamped task; pair → VD split
    static final int MSG_APPLY_FORCED_EV    = 35; // форсированный электрорежим (arg1: 1=вкл)
    static final int REQUEST_CODE           = 1;
    static final String ACTION_REQUEST_POWER_HOLD_STATUS =
            "ru.big.town.anative.REQUEST_POWER_HOLD_STATUS";
    static final String ACTION_POWER_HOLD_STATUS_UPDATE =
            "ru.big.town.anative.POWER_HOLD_STATUS_UPDATE";
    // Native уводит задачу приложения с виртуального дисплея виджета при полноэкранном запуске.
    static final String ACTION_EMBEDDED_TASK_LEFT = "ru.big.town.anative.EMBEDDED_TASK_LEFT";
    static final String EXTRA_EMBEDDED_TASK_PKG = "pkg";
    private static final String BIND_SET_MODES_PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
    private static final int POWER_HOLD_UNKNOWN = 0;
    private static final int POWER_HOLD_INACTIVE = 1;
    private static final int POWER_HOLD_ACTIVATING = 2;
    private static final int POWER_HOLD_ACTIVE = 3;
    private static final int POWER_HOLD_FAILED = 4;
    private static final int POWER_HOLD_EXIT_LOW_BATTERY = 1;
    private static final int POWER_HOLD_EXIT_TIME_UP = 2;
    private static final int POWER_HOLD_REQUEST_ACCEPTED = 1;
    private static final int POWER_HOLD_REQUEST_NOT_IN_PARK = 2;
    private static final int POWER_HOLD_REQUEST_LOW_BATTERY = 3;
    private static final int POWER_HOLD_REQUEST_STATE_UNAVAILABLE = 4;
    private static final int POWER_HOLD_REQUEST_TRANSPORT_FAILURE = 5;
    private Intent resultIntent=null;
    private Intent resultIntentStarButton=null;
    private SharedPreferences.Editor editor=null;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    static final String TAG = "$$$ MainActivityRestoreMode $$$";

    // -------- Виджет статистики поездки --------
    static final String ACTION_TRIP_UPDATE = "ru.big.town.anative.TRIP_UPDATE";
    static final String ACTION_REQUEST_TRIP_UPDATE = "ru.big.town.anative.REQUEST_TRIP_UPDATE";
    static final String ACTION_TRIP_RESET = "ru.big.town.anative.TRIP_RESET";
    private TextView tripTimer, tripStatus;
    // Карточки главного экрана, скрываемые настройками раздела «Главный экран»
    private View tripCard, cardPowerHold, cardWashMode, cardAutoLight, cardPedestrian, cardForcedEv;
    // Native-виджеты
    private View launchAppsWidget;
    private boolean tripActive = false, tripInDrive = false;
    private long tripAccumMs = 0L, tripDriveStartElapsed = 0L;
    private String lastTripsJson = "[]"; // снимок лога для экрана истории

    // Тоггл-карточки на главном (автосвет / звук пешеходов): нейтральные, состояние — капсула-тег
    private TextView autoLightBadge, pedestrianBadge, forcedEvBadge;
    private TextView powerHoldBadge;
    private boolean autoLightOn, pedestrianOn, forcedEvOn;

    // -------- Виджет «Прогрев батареи» --------
    static final String ACTION_BATTERY_HEAT_UPDATE   = "ru.big.town.anative.BATTERY_HEAT_UPDATE";
    static final String ACTION_REQUEST_BATTERY_HEAT  = "ru.big.town.anative.REQUEST_BATTERY_HEAT";
    static final String ACTION_BATTERY_HEAT_ACTIVATE = "ru.big.town.anative.BATTERY_HEAT_ACTIVATE";
    // Запуск приложения из плитки «Быстрый запуск» на выбранном физическом дисплее (0/1).
    static final String ACTION_OPEN_ON_DISPLAY = "ru.big.town.anative.OPEN_ON_DISPLAY";
    private static final int BH_UNKNOWN = Integer.MIN_VALUE;
    private static final int BH_TEMP_INVALID = -9999;
    private static final int BH_PLATFORM_H97X = 1;
    private static final int BH_PLATFORM_H97C = 2;
    private static final int BH_PHASE_IDLE = 0;
    private static final int BH_PHASE_SENDING = 1;
    private static final int BH_PHASE_AWAITING_CONFIRMATION = 2;
    private static final int BH_PHASE_ACTIVE = 3;
    private static final int BH_PHASE_BLOCKED = 4;
    private static final int BH_PHASE_ENABLED = 5;
    private View cardBatteryHeat;
    private ImageView batteryHeatIcon;
    private TextView batteryHeatState, batteryHeatTemp, batteryHeatStatus, batteryHeatFail;
    private Button buttonBatteryHeat;
    // Палитра состояний прогрева (цвет = состояние термоменеджмента ВВБ)
    private static final int BH_COLOR_COLD    = 0xFF3D7FD0; // синий — на улице холодно, прогрев уместен
    private static final int BH_COLOR_HEATING = 0xFF35B06A; // зелёный — идёт прогрев
    private static final int BH_COLOR_NORMAL  = 0xFF6B7280; // серый — норма / нет данных
    private static final int BH_COLOR_WARN    = 0xFFD0A92F; // жёлтый — внимание (прогрев невозможен)
    private static final int BH_COLOR_FAULT   = 0xFFD04A4A; // красный — неисправность ВВБ

    // Сплиты — прокручиваемая сетка плиток по пресетам из «Разделение экрана»
    private GridLayout splitTilesGrid;
    // Верхний инсет контента — тот же, что уходит в padding mainContent (нижний всегда 0).
    private int contentInsetTop;
    // «Полноэкранная сетка»: верхний ряд плиток растягивается до границы экрана.
    private boolean fullscreenGrid;
    // Настройка «сколько левых колонок верхнего ряда растягивается» и её границы.
    private static final int FULLSCREEN_GRID_COLUMNS_DEFAULT = 8;
    private static final int FULLSCREEN_GRID_COLUMNS_MAX = 12;
    // Собственные вертикальные отступы сетки из разметки — «Полноэкранная сетка» снимает верхний.
    private int gridPaddingTop;
    private int gridPaddingBottom;
    private final Map<String, TextureView> embeddedWidgetSurfaces = new HashMap<>();
    private final Map<String, Surface> embeddedWidgetOutputs = new HashMap<>();
    // View плиток app_widget по id записи: нужен, чтобы запустить приложение в первом виджете.
    private final Map<String, View> appWidgetTileViews = new HashMap<>();

    // -------- Drag-and-drop для переупорядочивания плиток --------
    private int draggedTilePosition = -1;    // позиция в общем списке TileOrderStore
    
    private final BroadcastReceiver tripReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            tripActive = intent.getBooleanExtra("tripActive", false);
            tripInDrive = intent.getBooleanExtra("inDrive", false);
            tripAccumMs = intent.getLongExtra("accumMs", 0L);
            tripDriveStartElapsed = intent.getLongExtra("driveStartElapsed", 0L);
            String tj = intent.getStringExtra("tripsJson");
            if (tj != null) lastTripsJson = tj;
            updateTripTimer();
        }
    };

    private final BroadcastReceiver batteryHeatReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderBatteryHeat(intent);
        }
    };

    private final BroadcastReceiver powerHoldStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderPowerHoldStatus(intent);
        }
    };

    private final BroadcastReceiver embeddedLeftReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            releaseEmbeddedWidgetsOf(intent.getStringExtra(EXTRA_EMBEDDED_TASK_PKG));
        }
    };

    /**
     * Приложение открыли в полный экран, а его задача уехала с дисплея виджета: снимаем такие
     * виджеты, иначе они показывали бы чёрный квадрат, который сам не восстановится.
     */
    private void releaseEmbeddedWidgetsOf(String pkg) {
        if (pkg == null) return;
        for (String widgetId : new ArrayList<>(embeddedWidgetSurfaces.keySet())) {
            AppWidgetStore.Entry entry = AppWidgetStore.find(sharedPreferences, widgetId);
            View tile = appWidgetTileViews.get(widgetId);
            if (tile != null && entry != null && pkg.equals(entry.selected().packageName)) {
                releaseEmbeddedWidget(widgetId, tile);
            }
        }
    }

    private void renderPowerHoldStatus(Intent intent) {
        if (powerHoldBadge == null || intent == null) return;
        int status = intent.getIntExtra("status", POWER_HOLD_UNKNOWN);
        int exitReason = intent.getIntExtra("exitReason", 0);
        int requestOutcome = intent.getIntExtra("requestOutcome", 0);
        switch (status) {
            case POWER_HOLD_INACTIVE:
                if (exitReason == POWER_HOLD_EXIT_LOW_BATTERY) {
                    powerHoldBadge.setText(R.string.power_hold_status_exit_low_battery);
                } else if (exitReason == POWER_HOLD_EXIT_TIME_UP) {
                    powerHoldBadge.setText(R.string.power_hold_status_exit_time_up);
                } else {
                    powerHoldBadge.setText(R.string.power_hold_status_inactive);
                }
                powerHoldBadge.setBackgroundResource(R.drawable.pill_inactive);
                break;
            case POWER_HOLD_ACTIVATING:
                powerHoldBadge.setText(R.string.power_hold_status_activating);
                powerHoldBadge.setBackgroundResource(R.drawable.pill_pending);
                break;
            case POWER_HOLD_ACTIVE:
                powerHoldBadge.setText(R.string.power_hold_status_active);
                powerHoldBadge.setBackgroundResource(R.drawable.pill_active);
                break;
            case POWER_HOLD_FAILED:
                powerHoldBadge.setText(R.string.power_hold_status_failed);
                powerHoldBadge.setBackgroundResource(R.drawable.pill_error);
                break;
            case POWER_HOLD_UNKNOWN:
            default:
                powerHoldBadge.setText(R.string.power_hold_status_unknown);
                powerHoldBadge.setBackgroundResource(R.drawable.pill_inactive);
                break;
        }
        showPowerHoldRequestOutcome(requestOutcome);
    }

    private void showPowerHoldRequestOutcome(int outcome) {
        switch (outcome) {
            case POWER_HOLD_REQUEST_ACCEPTED:
                showSnack(getString(R.string.power_hold_request_accepted));
                break;
            case POWER_HOLD_REQUEST_NOT_IN_PARK:
                showSnack(getString(R.string.power_hold_request_not_in_park));
                break;
            case POWER_HOLD_REQUEST_LOW_BATTERY:
                showSnack(getString(R.string.power_hold_request_low_battery));
                break;
            case POWER_HOLD_REQUEST_STATE_UNAVAILABLE:
                showSnack(getString(R.string.power_hold_request_state_unavailable));
                break;
            case POWER_HOLD_REQUEST_TRANSPORT_FAILURE:
                showSnack(getString(R.string.power_hold_request_transport_failure));
                break;
            default:
                break;
        }
    }

    // Синхронизация карточек, когда Force EV или звук пешеходов переключены кнопкой руля.
    private final BroadcastReceiver settingSyncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String key = intent.getStringExtra("key");
            if (key == null || !intent.hasExtra("value")) return;
            boolean value = intent.getBooleanExtra("value", false);
            editor.putBoolean(key, value).apply();
            if ("forcedEv".equals(key)) forcedEvOn = value;
            else if ("disablePedestrianSound".equals(key)) pedestrianOn = !value;
            else return;
            updateToggleVisuals();
        }
    };

    private final Runnable tripTick = new Runnable() {
        @Override
        public void run() {
            updateTripTimer();
            uiHandler.postDelayed(this, 1000);
        }
    };

    private void updateTripTimer() {
        long ms = tripAccumMs;
        if (tripActive && tripInDrive) ms += SystemClock.elapsedRealtime() - tripDriveStartElapsed;
        if (tripTimer != null) tripTimer.setText(fmtDuration(ms));
        if (tripStatus != null) {
            tripStatus.setText(!tripActive ? "нет активной поездки"
                    : (tripInDrive ? "в пути" : "на паузе (не Drive)"));
        }
    }

    /** Полный формат таймера: H:MM:SS. */
    private static String fmtDuration(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    /** Показ снимка статуса прогрева батареи в виджете (данные из BatteryHeatService). */
    private void renderBatteryHeat(Intent intent) {
        if (batteryHeatTemp == null) return;
        int temp    = intent.getIntExtra("ambientTemp",   BH_TEMP_INVALID);
        int status  = intent.getIntExtra("controlStatus", BH_UNKNOWN);
        int switchState = intent.getIntExtra("switchState", BH_UNKNOWN);
        int preheat = intent.getIntExtra("preheatSet",    BH_UNKNOWN);
        int bms     = intent.getIntExtra("bmsState",      BH_UNKNOWN);
        int autoCtl = intent.getIntExtra("autoCtrl",      BH_UNKNOWN);
        int fail    = intent.getIntExtra("failReason",    BH_UNKNOWN);
        int platform = intent.getIntExtra("vehiclePlatform", 0);
        int phase = intent.getIntExtra("activationPhase", BH_PHASE_IDLE);

        int threshold = intent.getIntExtra("tempThreshold", 10);
        boolean tempValid = temp != BH_TEMP_INVALID && temp != BH_UNKNOWN;

        batteryHeatTemp.setText(tempValid ? "за бортом: " + temp + " °C" : "за бортом: —");

        String preheatTxt = (bms == 9) ? "идёт"
                : bhControlState(platform, preheat, switchState);
        batteryHeatStatus.setText("Нагрев: " + bhHeating(status)
                + "   ·   Pre-heat: " + preheatTxt
                + "   ·   Автоподогрев: " + bhOnOff(autoCtl));

        String failTxt = bhFail(fail);
        if (failTxt != null) {
            batteryHeatFail.setText("Не удалось запустить прогрев: " + failTxt);
            batteryHeatFail.setVisibility(View.VISIBLE);
        } else {
            batteryHeatFail.setVisibility(View.GONE);
        }

        if (buttonBatteryHeat != null) {
            boolean enabled = phase == BH_PHASE_IDLE;
            buttonBatteryHeat.setEnabled(enabled);
            buttonBatteryHeat.setAlpha(enabled ? 1.0f : 0.55f);
            if (phase == BH_PHASE_SENDING) {
                buttonBatteryHeat.setText("Отправка…");
            } else if (phase == BH_PHASE_AWAITING_CONFIRMATION) {
                buttonBatteryHeat.setText("Ожидаем ответ…");
            } else if (phase == BH_PHASE_ACTIVE) {
                buttonBatteryHeat.setText("Прогрев активен");
            } else if (phase == BH_PHASE_ENABLED) {
                buttonBatteryHeat.setText("Контроль включён");
            } else if (phase == BH_PHASE_BLOCKED) {
                buttonBatteryHeat.setText("Сейчас недоступно");
            } else {
                buttonBatteryHeat.setText("Запустить прогрев");
            }
        }

        applyBatteryHeatIndicator(status, bms, fail, temp, tempValid, threshold, phase);
    }

    /**
     * Графический индикатор состояния прогрева: тон иконки + цветная «пилюля» с подписью.
     * Числовой температуры батареи голова не отдаёт, поэтому цвет отражает состояние
     * термоменеджмента ВВБ и уличный холод (приоритет — сверху вниз):
     *  красный  — неисправность ВВБ (BMS_STATE=FAULT);
     *  зелёный  — идёт прогрев;
     *  жёлтый   — прогрев невозможен (зарядка / низкий заряд / ВВ выкл / температура вне диапазона);
     *  синий    — на улице холодно (ниже порога), прогрев уместен;
     *  серый    — норма / нет данных.
     */
    private void applyBatteryHeatIndicator(int status, int bms, int fail,
                                           int temp, boolean tempValid, int threshold,
                                           int phase) {
        int color; String label;
        boolean anyData = status != BH_UNKNOWN || bms != BH_UNKNOWN || fail != BH_UNKNOWN || tempValid;
        if (bms == 8) {                              // BMS_STATE_FAULT
            color = BH_COLOR_FAULT;   label = "Неисправность";
        } else if (status == 1 || bms == 9 || phase == BH_PHASE_ACTIVE) {
            color = BH_COLOR_HEATING; label = "Прогрев";
        } else if (fail >= 1 && fail <= 4) {         // прогрев невозможен
            color = BH_COLOR_WARN;    label = "Внимание";
        } else if (phase == BH_PHASE_SENDING
                || phase == BH_PHASE_AWAITING_CONFIRMATION) {
            color = BH_COLOR_COLD;    label = "Ожидание";
        } else if (phase == BH_PHASE_ENABLED) {
            color = BH_COLOR_HEATING; label = "Контроль включён";
        } else if (tempValid && temp < threshold) {  // на улице холодно
            color = BH_COLOR_COLD;    label = "Холодно";
        } else {
            color = BH_COLOR_NORMAL;  label = anyData ? "Норма" : "Нет данных";
        }

        if (batteryHeatIcon != null) batteryHeatIcon.setColorFilter(color);
        if (batteryHeatState != null) {
            batteryHeatState.setText(label);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(getResources().getDisplayMetrics().density * 14f);
            batteryHeatState.setBackground(bg);
        }
    }

    private static String bhHeating(int v) {
        switch (v) {
            case 0:  return "нет";
            case 1:  return "идёт";
            case 2:  return "инициализация";
            default: return "—";
        }
    }

    private static String bhOnOff(int v) {
        switch (v) {
            case 1:  return "вкл";
            case 2:  return "выкл";
            default: return "—";
        }
    }

    private static String bhControlState(int platform, int h97xState, int h97cSwitch) {
        if (platform == BH_PLATFORM_H97X) {
            if (h97xState == 1) return "вкл";
            if (h97xState == 0) return "выкл";
            return "—";
        }
        if (platform == BH_PLATFORM_H97C) return bhOnOff(h97cSwitch);
        if (h97xState == 1) return "вкл";
        if (h97xState == 0) return "выкл";
        return bhOnOff(h97cSwitch);
    }

    /** Причина отказа прогрева (FAIL_STATE): null — отказа нет / нет данных. */
    private static String bhFail(int v) {
        switch (v) {
            case 1:  return "идёт зарядка";
            case 2:  return "высоковольтная сеть выключена";
            case 3:  return "низкий заряд батареи";
            case 4:  return "температура вне допустимого диапазона";
            default: return null; // 0 = нет отказа, прочее/UNKNOWN — не показываем
        }
    }

    /** Прогрев батареи в один клик — шлём в BatteryHeatService (тот дёргает CAN-команду). */
    public void onButtonBatteryHeat(View v) {
        Intent i = new Intent(ACTION_BATTERY_HEAT_ACTIVATE);
        i.setPackage("ru.big.town.anative");
        sendBroadcast(i);
        showSnack("Запрос отправлен, ожидаем подтверждение автомобиля…");
        Log.i(TAG, "BATTERY_HEAT_ACTIVATE отправлен");
    }

    /** Открыть отдельный экран истории поездок (снимок лога передаём в интенте). */
    public void onButtonTripHistory(View v) {
        Intent i = new Intent(this, TripHistoryActivity.class);
        i.putExtra("tripsJson", lastTripsJson);
        startActivity(i);
    }

    /** Сброс таймера текущей поездки в 0 (с подтверждением, чтобы исключить случайное нажатие). */
    public void onButtonTripReset(View v) {
        new MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Сбросить таймер")
                .setMessage("Обнулить время текущей поездки? Действие не пишется в историю.")
                .setPositiveButton("Сбросить", (d, w) -> {
                    Intent i = new Intent(ACTION_TRIP_RESET);
                    i.setPackage("ru.big.town.anative");
                    sendBroadcast(i);
                    Log.i(TAG, "TRIP_RESET отправлен");
                })
                .setNegativeButton("Отмена", null)
                .show();
    }


    // Handling result
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            Log.i("onActivityResult",String.format("requestCode - %d resultCode - %d data %s",requestCode,resultCode,data.toString()));

            customCommand      = data.getStringExtra("customCommand");
            customCommandCount = data.getIntExtra("customCommandCount", 1);

            Log.i("onActivityResult", String.format(
                    "customCommand=%s count=%d", customCommand, customCommandCount));

            editor.putString("customCommand", customCommand);
            editor.putInt("customCommandCount", customCommandCount);
            editor.apply();
        }
    }

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RESULT:
                    Log.i(TAG, "handleMessage() MSG_RESULT");
                    break;
                default:
                    Log.i(TAG, "handleMessage() default");
                    super.handleMessage(msg);
            }
        }
    }

    private boolean bindingRequested = false;
    private boolean connectionReported = false;
    private boolean destroyed = false;
    private static final long BIND_RETRY_MS = 5_000L;
    private final Runnable messengerRebindRunnable = this::bindToMessengerService;

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (destroyed) return;
            uiHandler.removeCallbacks(messengerRebindRunnable);
            bindingRequested = true;
            Log.i(TAG, "onServiceConnected()");
            if (!connectionReported) {
                connectionReported = true;
                GlobalVars.clientConnected(new Messenger(service));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            clearReportedConnection();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            restartMessengerBinding("binding died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            restartMessengerBinding("null binding");
        }
    };

    private void bindToMessengerService() {
        if (destroyed || bindingRequested) return;
        uiHandler.removeCallbacks(messengerRebindRunnable);
        Log.i(TAG, "bindToMessengerService() begin");

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "ru.big.town.anative",
                "ru.big.town.anative.SetModesService"
        ));
        try {
            bindingRequested = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindToMessengerService() end, requested=" + bindingRequested);
            if (!bindingRequested) scheduleMessengerRebind();
        } catch (RuntimeException e) {
            bindingRequested = false;
            Log.w(TAG, "bindToMessengerService() failed: " + e.getMessage());
            scheduleMessengerRebind();
        }
    }

    private void clearReportedConnection() {
        if (!connectionReported) return;
        connectionReported = false;
        GlobalVars.clientDisconnected();
    }

    private void restartMessengerBinding(String reason) {
        Log.w(TAG, "SetModesService " + reason + " — replacing binding");
        releaseMessengerBinding(reason);
        scheduleMessengerRebind();
    }

    private void scheduleMessengerRebind() {
        if (destroyed) return;
        uiHandler.removeCallbacks(messengerRebindRunnable);
        uiHandler.postDelayed(messengerRebindRunnable, BIND_RETRY_MS);
    }

    private void releaseMessengerBinding(String reason) {
        uiHandler.removeCallbacks(messengerRebindRunnable);
        clearReportedConnection();
        if (bindingRequested) {
            try {
                unbindService(connection);
            } catch (RuntimeException e) {
                Log.w(TAG, reason + ": unbindService failed: " + e.getMessage());
            }
        }
        bindingRequested = false;
    }

    public boolean sendMessageToService(int message) {
        return sendMessageToService(message, 0);
    }

    public boolean sendMessageToService(int message, int arg1) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) return false;

        try {
            Message msg = Message.obtain(null, message, arg1, 0);
            msg.replyTo = GlobalVars.clientMessenger;
            GlobalVars.serviceMessenger.send(msg);
            return true;
        } catch (RemoteException e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Тоггл-карточки автосвета/звука пешеходов: находим бейджи + начальное состояние из prefs. */
    private void initToggleCards() {
        refreshToggles();
    }

    /** Клик по карточке «Автосвет» — переключаем и применяем немедленно. */
    public void onCardAutoLight(View v) {
        autoLightOn = !autoLightOn;
        editor.putBoolean("autoLight", autoLightOn).apply();
        sendMessageToService(autoLightOn ? MSG_AUTO_LIGHT_ENABLE : MSG_AUTO_LIGHT_DISABLE);
        updateToggleVisuals();
        Log.i(TAG, "card autoLight=" + autoLightOn);
    }

    /** Клик по карточке «Звук пешеходов» (вкл = звук есть). */
    public void onCardPedestrian(View v) {
        pedestrianOn = !pedestrianOn;
        boolean disabled = !pedestrianOn;   // pref: true = заглушить
        editor.putBoolean("disablePedestrianSound", disabled).apply();
        sendMessageToService(MSG_APPLY_PEDESTRIAN, disabled ? 1 : 0);
        updateToggleVisuals();
        Log.i(TAG, "card pedestrianSound on=" + pedestrianOn);
    }

    /** Клик по карточке «Forced EV» (вкл = удерживаем электротягу). */
    public void onCardForcedEv(View v) {
        forcedEvOn = !forcedEvOn;
        editor.putBoolean("forcedEv", forcedEvOn).apply();
        sendMessageToService(MSG_APPLY_FORCED_EV, forcedEvOn ? 1 : 0);
        updateToggleVisuals();
        Log.i(TAG, "card forcedEv on=" + forcedEvOn);
    }

    /** Перечитать состояние из prefs (напр. после «Дополнительно») и обновить вид. */
    private void refreshToggles() {
        autoLightOn  = sharedPreferences.getBoolean("autoLight", false);
        pedestrianOn = !sharedPreferences.getBoolean("disablePedestrianSound", false);
        forcedEvOn   = sharedPreferences.getBoolean("forcedEv", false);
        updateToggleVisuals();
    }

    /** Состояние карточки — капсула-тег: голубая «активно» / серая «не активно». */
    private void updateToggleVisuals() {
        applyBadge(autoLightBadge, autoLightOn);
        applyBadge(pedestrianBadge, pedestrianOn);
        applyBadge(forcedEvBadge, forcedEvOn);
    }

    private void applyBadge(TextView badge, boolean on) {
        if (badge == null) return;
        badge.setText(on ? "активно" : "не активно");
        badge.setBackgroundResource(on ? R.drawable.pill_active : R.drawable.pill_inactive);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bindToMessengerService();

        EdgeToEdge.enable(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Свой док удалён — используется родной док головы (висит поверх слева ~145dp, в insets не приходит).
        // Контент отступаем вправо от него + под статус-бар.
        final View mainContent = findViewById(R.id.mainContent);
        final int nativeDock = Math.round(getResources().getDisplayMetrics().density * 145f);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int top = sb.top;
            if (top == 0) {   // на голове статус-бар не сообщает высоту в insets — берём системный status_bar_height
                int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) top = getResources().getDimensionPixelSize(id);
            }
            boolean fullscreen = isFullscreenGridEnabled();
            // «Полноэкранная сетка» снимает верхний отступ целиком: верхний ряд плиток
            // растягивается на его высоту (см. applyTileVerticalMetrics).
            mainContent.setPadding(nativeDock + sb.left, fullscreen ? 0 : top, sb.right, 0);
            // Плитки считают высоту от реально доступного места, поэтому после уточнения
            // инсетов сетку нужно перерисовать.
            if (top != contentInsetTop || fullscreen != fullscreenGrid) {
                contentInsetTop = top;
                fullscreenGrid = fullscreen;
                if (splitTilesGrid != null) splitTilesGrid.post(this::renderSplitTiles);
            }
            return insets;
        });

        sharedPreferences = getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        GlobalVars.sharedPreferences=sharedPreferences;

        splitTilesGrid = findViewById(R.id.splitTilesGrid);
        gridPaddingTop = splitTilesGrid.getPaddingTop();
        gridPaddingBottom = splitTilesGrid.getPaddingBottom();

        editor = sharedPreferences.edit();
        GlobalVars.editor=editor;

        initToggleCards();
        initIntent();
        GlobalVars.clientMessenger = new Messenger(new IncomingHandler());
    }

    public void initIntent(){
        if(resultIntent==null){
            resultIntent = new Intent(this, AdvanceActivity.class);
        }

        resultIntent.putExtra("customCommand", customCommand);
        resultIntent.putExtra("customCommandCount", customCommandCount);
    }
    public void initIntentStarButton(){
        if(resultIntentStarButton==null){
            resultIntentStarButton = new Intent(this, AdvanceActivityStarButton.class);
        }

    }

    private void getModes(){

        Cursor cursor = getContentResolver().query(Uri
                        .parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                null, null,
                null, null);
        if(cursor.getCount() != 0){
            cursor.moveToFirst();
            driveMode=cursor.getString(0);
            energy=cursor.getString(1);
            recycle=cursor.getString(2);
            customCommand=cursor.getString(3);
            customCommandCount=cursor.getInt(4);
            Log.i("$$$ getModes() $$$", "Query Result:" +
                    "\ndriveMode: " + driveMode +
                    "\nenergy: " + energy +
                    "\nrecycle: " + recycle
            );
        }
        cursor.close();    }

    public void onButtonClickClose(View v){
        finish();
    }

    /** Power Hold (leave car): подтверждение → шлём в SetModesService, тот дёргает CAN. */
    public void onButtonLeaveCar(View v){
        new MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle(R.string.power_hold_title)
                .setMessage(R.string.power_hold_confirmation)
                .setPositiveButton(R.string.power_hold_activate, (d, w) -> {
                    boolean ok = sendMessageToService(MSG_LEAVE_CAR);
                    if (!ok) showSnack(getString(R.string.service_not_ready));
                    Log.i(TAG, "onButtonLeaveCar sent=" + ok);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Режим мойки — машина засыпает и не реагирует на открытие дверей. */
    public void onCardWashMode(View v){
        new MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle(R.string.wash_mode_title)
                .setMessage(R.string.wash_mode_confirmation)
                .setPositiveButton(R.string.wash_mode_activate, (d, w) -> {
                    boolean ok = sendMessageToService(MSG_WASH_MODE);
                    if (!ok) showSnack(getString(R.string.service_not_ready));
                    Log.i(TAG, "onCardWashMode sent=" + ok);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Snackbar вместо Toast — в Android Automotive системные тосты приложений не показываются. */
    private void showSnack(String text) {
        Snackbar.make(
                findViewById(R.id.main), text,
                Snackbar.LENGTH_LONG).show();
    }

    /** Открыть экран запущенных приложений (RunningAppsActivity из Native). */

    /**
     * Плитка «Быстрый запуск»: сетка приложений с вертикальным скролом, порядок — по подписи.
     * Колонок не больше, чем ширина плитки в ячейках smart-grid.
     */
    private void populateLaunchAppsWidget() {
        if (launchAppsWidget == null) return;
        ViewGroup grid = launchAppsWidget.findViewById(R.id.launchAppsGrid);
        if (grid == null) return;
        grid.removeAllViews();

        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

        // Пакет без дублей и сразу его подпись: по ней же сортируем.
        Map<String, String> labels = new HashMap<>();
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            String pkg = info.activityInfo.packageName;
            if (labels.containsKey(pkg)) continue;
            if (!isLaunchAppsTileCandidate(pm, pkg)) continue;
            labels.put(pkg, info.loadLabel(pm).toString());
        }

        List<String> packages = new ArrayList<>(labels.keySet());
        packages.sort((a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));

        int columns = Math.max(1, Math.min(LAUNCH_APPS_MAX_COLUMNS,
                TileSizeStore.width(sharedPreferences, TileSizeStore.LAUNCH_APPS_WIDGET_ID,
                        TileSizeStore.LAUNCH_APPS_DEFAULT_WIDTH)));
        if (grid instanceof GridLayout) ((GridLayout) grid).setColumnCount(columns);

        LayoutInflater inf = LayoutInflater.from(this);
        for (String pkg : packages) {
            View item = inf.inflate(R.layout.item_launch_app_grid, grid, false);
            ((TextView) item.findViewById(R.id.launchAppGridLabel)).setText(labels.get(pkg));
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                ((ImageView) item.findViewById(R.id.launchAppGridIcon))
                        .setImageDrawable(pm.getApplicationIcon(ai));
            } catch (Exception ignored) {
            }

            item.setOnClickListener(v -> launchAppNormally(pkg));
            // Долгий тап по иконке — контекстное меню запуска (основной дисплей / пассажирский /
            // первый app_widget). Перенос самой плитки остаётся на долгом тапе по её фону.
            item.setOnLongClickListener(v -> {
                showLaunchAppMenu(v, pkg);
                return true;
            });

            if (grid instanceof GridLayout) {
                int index = ((GridLayout) grid).getChildCount();
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = 0;                                  // ширину колонки задаёт вес
                lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
                lp.columnSpec = GridLayout.spec(index % columns, 1, 1f);
                lp.rowSpec = GridLayout.spec(index / columns, 1);
                item.setLayoutParams(lp);
            }
            grid.addView(item);
        }
    }

    /** Кандидат для плитки «Быстрый запуск»: стороннее приложение, без своих и служебных пакетов. */
    private boolean isLaunchAppsTileCandidate(PackageManager pm, String pkg) {
        if (pkg.equals(getPackageName()) || pkg.equals("ru.big.town.anative")
                || pkg.startsWith("com.qinggan") || pkg.startsWith("com.android.car")) {
            return false;
        }
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Контекстное меню иконки «Быстрого запуска»: куда открыть приложение. */
    private void showLaunchAppMenu(View anchor, String pkg) {
        final int idMainDisplay = 1;
        final int idNoWidgets = 2;
        final int idWidgetBase = 100;
        java.util.List<AppWidgetStore.Entry> widgets = AppWidgetStore.load(sharedPreferences);
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, idMainDisplay, 0, "Запустить на основном дисплее");
        if (widgets.isEmpty()) {
            menu.getMenu().add(0, idNoWidgets, 1, "Нет виджетов приложения").setEnabled(false);
        } else {
            // По пункту на каждый созданный пользователем виджет приложения.
            for (int i = 0; i < widgets.size(); i++) {
                menu.getMenu().add(0, idWidgetBase + i, i + 1,
                        "Запустить в виджете: " + appWidgetTitle(widgets.get(i)));
            }
        }
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == idMainDisplay) {
                launchAppOnDisplay(pkg, 0);
                return true;
            }
            int index = id - idWidgetBase;
            if (index >= 0 && index < widgets.size()) {
                launchInsideAppWidget(pkg, widgets.get(index).id);
                return true;
            }
            return false;
        });
        menu.show();
    }

    /** Подпись виджета в меню: приложение, выбранное в этом виджете. */
    private String appWidgetTitle(AppWidgetStore.Entry entry) {
        String configured = entry.selected().packageName;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(configured, 0)).toString();
        } catch (Exception e) {
            return configured;
        }
    }

    /** Открыть приложение обычной задачей на выбранном дисплее: 0 — водитель, 1 — пассажир. */
    private void launchAppOnDisplay(String pkg, int displayId) {
        if (!BuildConfig.IS_FULL) {
            launchAppNormally(pkg);
            return;
        }
        try {
            Intent intent = new Intent(ACTION_OPEN_ON_DISPLAY)
                    .setPackage("ru.big.town.anative")
                    .putExtra("pkg", pkg)
                    .putExtra("display", displayId);
            sendBroadcast(intent, BIND_SET_MODES_PERMISSION);
            Log.i(TAG, "launchAppOnDisplay " + pkg + " display=" + displayId);
        } catch (Exception e) {
            showSnack("Не удалось открыть приложение");
            Log.w(TAG, "launchAppOnDisplay " + pkg + ": " + e.getMessage());
        }
    }

    /** Открыть приложение внутри выбранного app_widget, не меняя его настройки. */
    private void launchInsideAppWidget(String pkg, String widgetId) {
        if (!BuildConfig.IS_FULL) {
            launchAppNormally(pkg);
            return;
        }
        AppWidgetStore.Entry entry = AppWidgetStore.find(sharedPreferences, widgetId);
        if (entry == null) {
            showSnack("Виджет приложения не найден");
            return;
        }
        View widgetView = appWidgetTileViews.get(entry.id);
        if (widgetView == null) {
            showSnack("Виджет приложения не на экране");
            return;
        }
        // Native переиспользует embedded-дисплей по widgetId и без освобождения не перезапустит
        // приложение, поэтому сначала снимаем текущее.
        if (embeddedWidgetSurfaces.containsKey(entry.id)) {
            releaseEmbeddedWidget(entry.id, widgetView);
        }
        showEmbeddedAppWidget(widgetView, entry, pkg, AppDpiStore.get(sharedPreferences, pkg));
    }

    /** Открыть системный экран настроек Android. */
    public void onCardAndroidSettings(View v){
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this,
                    "Не удалось открыть настройки Android", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "onCardAndroidSettings failed: " + e.getMessage());
        }
    }

    /** Открыть системный экран набора для сохранённого номера. */
    public void onDialNumber(View v){
        sendDialNumber(sharedPreferences.getString("dialWidgetNumber", ""));
    }

    private void onDialNumber(DialWidgetStore.Entry entry) {
        sendDialNumber(entry.number);
    }

    private void sendDialNumber(String rawNumber) {
        String number = rawNumber == null ? "" : rawNumber.replaceAll("[^0-9]", "");
        if (number.length() < 4 || number.length() > 10) {
            showSnack("Сначала сохраните номер от 4 до 10 цифр в настройках");
            return;
        }
        if (number.length() == 10) number = "8" + number;
        try {
            Intent intent = new Intent("com.qinggan.broadcast.action.callfromcard");
            intent.putExtra("dial_number", number);
            getApplicationContext().sendBroadcast(intent);
        } catch (Exception e) {
            showSnack("Не удалось передать номер для вызова");
            Log.w(TAG, "onDialNumber failed: " + e.getMessage());
        }
    }

    public void onButtonClickAdvance(View v){
        getModes();
        initIntent();
        Log.i("$$$ Main onButtonClickAdvance $$$", String.format("%s %d", customCommand, customCommandCount));
        startActivityForResult(resultIntent,REQUEST_CODE);
    }
    public void onButtonClickAdvanceStarButton(View v){
        getModes();
        initIntentStarButton();
        startActivity(resultIntentStarButton);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(tripReceiver, new IntentFilter(ACTION_TRIP_UPDATE), RECEIVER_EXPORTED);
        Intent req = new Intent(ACTION_REQUEST_TRIP_UPDATE);
        req.setPackage("ru.big.town.anative");
        sendBroadcast(req);
        uiHandler.removeCallbacks(tripTick);
        uiHandler.post(tripTick);
        registerReceiver(batteryHeatReceiver, new IntentFilter(ACTION_BATTERY_HEAT_UPDATE), RECEIVER_EXPORTED);
        registerReceiver(settingSyncReceiver, new IntentFilter("ru.big.town.anative.SETTING_SYNCED"),
                BIND_SET_MODES_PERMISSION, null, RECEIVER_EXPORTED);
        registerReceiver(powerHoldStatusReceiver,
                new IntentFilter(ACTION_POWER_HOLD_STATUS_UPDATE),
                BIND_SET_MODES_PERMISSION, null, RECEIVER_EXPORTED);
        registerReceiver(embeddedLeftReceiver,
                new IntentFilter(ACTION_EMBEDDED_TASK_LEFT),
                BIND_SET_MODES_PERMISSION, null, RECEIVER_EXPORTED);
        Intent powerHoldRequest = new Intent(ACTION_REQUEST_POWER_HOLD_STATUS);
        powerHoldRequest.setPackage("ru.big.town.anative");
        sendBroadcast(powerHoldRequest, BIND_SET_MODES_PERMISSION);
        Intent bhReq = new Intent(ACTION_REQUEST_BATTERY_HEAT);
        bhReq.setPackage("ru.big.town.anative");
        sendBroadcast(bhReq);
        refreshToggles();   // подхватить изменения, сделанные в «Дополнительно»
        applyMainScreenVisibility();
        // «Полноэкранная сетка» могла быть переключена в «Дополнительно»: вернуть верхний
        // отступ контента в согласованное с настройкой состояние до перерисовки сетки.
        fullscreenGrid = isFullscreenGridEnabled();
        applyContentTopInset();
        // Синхронизировать список плиток перед рендером
        TileOrderStore.sync(sharedPreferences, getPackageManager());
        renderSplitTiles();
    }

    /**
     * Показывать ли виджет главного экрана: ключ и дефолт его тумблера из «Дополнительно» → «Главный экран».
     * Дефолты совпадают с тумблерами: Forced EV и «Быстрый запуск» выключены, остальные карточки включены.
     */
    private boolean isWidgetVisible(String widgetId) {
        switch (widgetId) {
            case "tripCard":         return sharedPreferences.getBoolean("showTripTimer", true);
            case "cardPowerHold":
            case "cardLeaveCar":     return sharedPreferences.getBoolean("showPowerHold", true);
            case "cardWashMode":     return sharedPreferences.getBoolean("showWashMode", true);
            case "cardAutoLight":    return sharedPreferences.getBoolean("showAutoLight", true);
            case "cardPedestrian":   return sharedPreferences.getBoolean("showPedestrian", true);
            case "cardForcedEv":     return sharedPreferences.getBoolean("showForcedEv", false);
            case "cardBatteryHeat":  return sharedPreferences.getBoolean("showBatteryHeat", true);
            case "launchAppsWidget": return sharedPreferences.getBoolean("showLaunchAppsWidget", false);
            // Виджеты без тумблера («Настройки», настройки Android) видно всегда.
            default:                 return true;
        }
    }

    /** Скрыть/показать карточки главного экрана по настройкам раздела «Главный экран». */
    private void applyMainScreenVisibility() {
        setCardVisible(tripCard,       "showTripTimer");
        setCardVisible(cardPowerHold,  "showPowerHold");
        setCardVisible(cardWashMode,   "showWashMode");
        setCardVisible(cardAutoLight,  "showAutoLight");
        setCardVisible(cardPedestrian, "showPedestrian");
        // Forced EV по умолчанию СКРЫТ — в отличие от остальных карточек (у них дефолт true).
        if (cardForcedEv != null) {
            cardForcedEv.setVisibility(sharedPreferences.getBoolean("showForcedEv", false) ? View.VISIBLE : View.GONE);
        }
        setCardVisible(cardBatteryHeat, "showBatteryHeat");
        // Native-виджеты — по умолчанию скрыты, включаются через настройки
        if (launchAppsWidget != null) {
            boolean show = sharedPreferences.getBoolean("showLaunchAppsWidget", false);
            launchAppsWidget.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) populateLaunchAppsWidget();
        }
        // Кнопка «История поездок» в виджете — только если история включена.
        View histBtn = findViewById(R.id.buttonTripHistory);
        if (histBtn != null) {
            histBtn.setVisibility(
                    sharedPreferences.getBoolean("saveTripHistory", true) ? View.VISIBLE : View.GONE);
        }
    }

    private void setCardVisible(View card, String key) {
        if (card == null) return;
        card.setVisibility(sharedPreferences.getBoolean(key, true) ? View.VISIBLE : View.GONE);
    }

    /** Максимум колонок в сетке плитки «Быстрый запуск»: дальше элементы становятся слишком узкими. */
    private static final int LAUNCH_APPS_MAX_COLUMNS = 4;

    /** Получить размеры элемента в ячейках smart-grid: {ширина, высота}. */
    private int[] getWidgetDimensions(String widgetId) {
        if ("tripCard".equals(widgetId)) return new int[]{3, 2};
        if ("cardBatteryHeat".equals(widgetId)) return new int[]{3, 2};
        // Компактные карточки-иконки: одна ячейка.
        if ("cardSettings".equals(widgetId) || "cardAndroidSettings".equals(widgetId)) {
            return new int[]{1, 1};
        }
        // Плитка «Быстрый запуск»: по умолчанию 2x3, размер задаётся в «Дополнительно».
        if (TileSizeStore.LAUNCH_APPS_WIDGET_ID.equals(widgetId)) {
            return TileSizeStore.dimensions(sharedPreferences, widgetId,
                    TileSizeStore.LAUNCH_APPS_DEFAULT_WIDTH,
                    TileSizeStore.LAUNCH_APPS_DEFAULT_HEIGHT);
        }
        return new int[]{2, 1};
    }

    /** Найти и занять первую свободную прямоугольную область smart-grid. */
    private int[] placeGridItem(List<boolean[]> occupiedColumns, int maxRows,
                                int spanColumns, int spanRows) {
        for (int column = 0; ; column++) {
            while (occupiedColumns.size() < column + spanColumns) {
                occupiedColumns.add(new boolean[maxRows]);
            }
            for (int row = 0; row <= maxRows - spanRows; row++) {
                boolean free = true;
                for (int checkColumn = column; checkColumn < column + spanColumns && free; checkColumn++) {
                    for (int checkRow = row; checkRow < row + spanRows; checkRow++) {
                        if (occupiedColumns.get(checkColumn)[checkRow]) {
                            free = false;
                            break;
                        }
                    }
                }
                if (!free) continue;
                for (int markColumn = column; markColumn < column + spanColumns; markColumn++) {
                    for (int markRow = row; markRow < row + spanRows; markRow++) {
                        occupiedColumns.get(markColumn)[markRow] = true;
                    }
                }
                return new int[]{column, row};
            }
        }
    }

    /** Подсветить или вернуть обычный вид карточки под курсором drag-and-drop. */
    private void setDragTargetHighlight(View view, boolean highlighted) {
        view.animate().cancel();
        view.animate()
                .scaleX(highlighted ? 1.04f : 1f)
                .scaleY(highlighted ? 1.04f : 1f)
                .alpha(highlighted ? 0.78f : 1f)
                .setDuration(120L)
                .start();
        view.setElevation(highlighted ? 16f : 0f);
    }

    /** Рисует все плитки (сплиты + приложения) в едином смешанном порядке из TileOrderStore, 12 в ряд. */
    private void renderSplitTiles() {
        if (splitTilesGrid == null) return;
        // «Полноэкранная сетка» снимает и верхний отступ самой сетки: растянутый ряд должен
        // начинаться от границы экрана, а отрицательный отступ GridLayout не переносит.
        splitTilesGrid.setPadding(splitTilesGrid.getPaddingLeft(),
                fullscreenGrid ? 0 : gridPaddingTop,
                splitTilesGrid.getPaddingRight(), gridPaddingBottom);
        splitTilesGrid.removeAllViews();
        appWidgetTileViews.clear();
        splitTilesGrid.setOnDragListener((v, event) -> {
            if (event.getAction() == DragEvent.ACTION_DROP) {
                int insertionPos = findGridInsertionPosition(event.getX(), event.getY());
                insertTileAt(draggedTilePosition, insertionPos);
                renderSplitTiles();
            }
            return true;
        });
        
        PackageManager pm = getPackageManager();
        LayoutInflater inf = LayoutInflater.from(this);
        
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int nativeDock = Math.round(metrics.density * 145f);
        int availableW = metrics.widthPixels - nativeDock - Math.round(metrics.density * 4f);
        // Высота ряда считается от того же места, что реально получает сетка: высота экрана
        // минус верхний инсет и собственные вертикальные отступы сетки.
        int availableH = 720 - contentInsetTop
                - splitTilesGrid.getPaddingTop() - 0;
        // Расстояние между плитками — настройка раздела «Главный экран» (dp вокруг каждой плитки).
        int spacingDp = Math.max(0, Math.min(24, sharedPreferences.getInt("tileSpacingDp", 4)));
        int m = Math.round(spacingDp * metrics.density);
        int tileW = (availableW / 12) - 2 * m;
        int tileH = (availableH / 5) - 2 * m;
        final int rows = 5;
        // Высота сетки не делится на число рядов нацело. Остаток (до 4px) раздаём по
        // одному пикселю верхним рядам, иначе под нижним рядом остаётся пустая полоса.
        final int rowRemainder = availableH - (availableH / rows) * rows;
        // «Полноэкранная сетка»: верхний отступ контента снят, поэтому верхний ряд
        // растягивается на его высоту — до самой границы экрана.
        final int topInset = fullscreenGrid ? contentInsetTop : 0;
        // Сколько левых колонок верхнего ряда растягивается до границы экрана.
        final int stretchedColumns = fullscreenGridColumns();
        
        // Загружаем единый список всех плиток в нужном порядке
        List<TileOrderStore.Tile> tiles = TileOrderStore.load(sharedPreferences);
        
        // Создаём карту сплитов по id для быстрого доступа
        Map<String, SplitStore.Preset> splitMap = new HashMap<>();
        if (BuildConfig.IS_FULL) {
            List<SplitStore.Preset> splits = SplitStore.load(sharedPreferences);
            for (SplitStore.Preset ps : splits) {
                if (ps.ready()) {
                    splitMap.put(ps.id, ps);
                }
            }
        }
        
        List<boolean[]> occupied = new ArrayList<>();
        for (int pos = 0; pos < tiles.size(); pos++) {
            TileOrderStore.Tile tile = tiles.get(pos);
            final int tilePos = pos;

            if (TileOrderStore.Tile.TYPE_DIAL.equals(tile.type)) {
                DialWidgetStore.Entry dialEntry = null;
                for (DialWidgetStore.Entry candidate : DialWidgetStore.load(sharedPreferences)) {
                    if (candidate.id.equals(tile.id)) {
                        dialEntry = candidate;
                        break;
                    }
                }
                if (dialEntry == null) continue;
                final DialWidgetStore.Entry finalDialEntry = dialEntry;
                View dialView = inf.inflate(R.layout.tile_dial, splitTilesGrid, false);
                ((TextView) dialView.findViewById(R.id.dialTileName)).setText(
                        finalDialEntry.name.isEmpty() ? "Набрать номер" : finalDialEntry.name);
                ((TextView) dialView.findViewById(R.id.dialTileNumber)).setText(
                        finalDialEntry.number.isEmpty() ? "Номер не задан" : finalDialEntry.number);
                dialView.setOnClickListener(v -> onDialNumber(finalDialEntry));
                dialView.setOnLongClickListener(v -> {
                    startTileDrag(v, TileOrderStore.Tile.TYPE_DIAL, finalDialEntry.id);
                    return true;
                });
                setTileDragListener(dialView, tilePos);
                int[] gridPosition = placeGridItem(occupied, rows, 2, 1);
                GridLayout.LayoutParams dialLp = new GridLayout.LayoutParams();
                dialLp.width = tileW * 2 + m * 2;
                dialLp.columnSpec = GridLayout.spec(gridPosition[0], 2);
                dialLp.rowSpec = GridLayout.spec(gridPosition[1]);
                applyTileVerticalMetrics(dialLp, m, tileH, gridPosition, 1, rowRemainder,
                        topInset, stretchedColumns);
                dialView.setLayoutParams(dialLp);
                splitTilesGrid.addView(dialView);
                continue;
            }
            
            if (TileOrderStore.Tile.TYPE_WIDGET.equals(tile.type)) {
                // ===== Это виджет =====
                View widgetView = null;
                
                switch(tile.id) {
                    case "tripCard": widgetView = inf.inflate(R.layout.tile_trip, splitTilesGrid, false); break;
                    // Исторический id карточки Power Hold: встречается в сохранённом порядке плиток.
                    case "cardPowerHold":
                    case "cardLeaveCar": widgetView = inf.inflate(R.layout.tile_power_hold, splitTilesGrid, false); break;
                    case "cardWashMode": widgetView = inf.inflate(R.layout.tile_wash_mode, splitTilesGrid, false); break;
                    case "cardSettings": widgetView = inf.inflate(R.layout.tile_settings, splitTilesGrid, false); break;
                    case "cardAndroidSettings": widgetView = inf.inflate(R.layout.tile_android_settings, splitTilesGrid, false); break;
                    case "cardAutoLight": widgetView = inf.inflate(R.layout.tile_auto_light, splitTilesGrid, false); break;
                    case "cardPedestrian": widgetView = inf.inflate(R.layout.tile_pedestrian, splitTilesGrid, false); break;
                    case "cardForcedEv": widgetView = inf.inflate(R.layout.tile_forced_ev, splitTilesGrid, false); break;
                    case "cardBatteryHeat": widgetView = inf.inflate(R.layout.tile_battery_heat, splitTilesGrid, false); break;
                    case "launchAppsWidget": widgetView = inf.inflate(R.layout.tile_launch_apps, splitTilesGrid, false); break;
                }
                if (widgetView == null) continue;
                // Отключённые карточки не должны занимать место в smart-grid. Настройку читаем
                // именно здесь: сетка пересобирается на каждом рендере, поэтому «скрыть» уже
                // созданную вьюху бесполезно — на её место приходит новая, по умолчанию видимая.
                widgetView.setVisibility(isWidgetVisible(tile.id) ? View.VISIBLE : View.GONE);
                if (widgetView.getVisibility() != View.VISIBLE) continue;

                if ("cardDialNumber".equals(tile.id)) {
                    continue;
                }
                
                // Re-bind dynamically inflated views based on ID
                if (tile.id.equals("tripCard")) {
                    tripTimer  = widgetView.findViewById(R.id.tripTimer);
                    tripStatus = widgetView.findViewById(R.id.tripStatus);
                    tripCard   = widgetView;
                    updateTripTimer();
                } else if (tile.id.equals("cardPowerHold") || tile.id.equals("cardLeaveCar")) {
                    cardPowerHold  = widgetView;
                    powerHoldBadge = widgetView.findViewById(R.id.powerHoldBadge);
                    refreshToggles();
                } else if (tile.id.equals("cardWashMode")) {
                    cardWashMode   = widgetView;
                } else if (tile.id.equals("cardAutoLight")) {
                    cardAutoLight  = widgetView;
                    autoLightBadge  = widgetView.findViewById(R.id.autoLightBadge);
                    refreshToggles();
                } else if (tile.id.equals("cardPedestrian")) {
                    cardPedestrian = widgetView;
                    pedestrianBadge = widgetView.findViewById(R.id.pedestrianBadge);
                    refreshToggles();
                } else if (tile.id.equals("cardForcedEv")) {
                    cardForcedEv   = widgetView;
                    forcedEvBadge   = widgetView.findViewById(R.id.forcedEvBadge);
                    refreshToggles();
                } else if (tile.id.equals("cardBatteryHeat")) {
                    cardBatteryHeat   = widgetView.findViewById(R.id.cardBatteryHeat);
                    batteryHeatIcon   = widgetView.findViewById(R.id.batteryHeatIcon);
                    batteryHeatState  = widgetView.findViewById(R.id.batteryHeatState);
                    batteryHeatTemp   = widgetView.findViewById(R.id.batteryHeatTemp);
                    batteryHeatStatus = widgetView.findViewById(R.id.batteryHeatStatus);
                    batteryHeatFail   = widgetView.findViewById(R.id.batteryHeatFail);
                    buttonBatteryHeat = widgetView.findViewById(R.id.buttonBatteryHeat);
                } else if (tile.id.equals("launchAppsWidget")) {
                    launchAppsWidget  = widgetView.findViewById(R.id.launchAppsWidget);
                    populateLaunchAppsWidget();
                }
                
                widgetView.setOnLongClickListener(v -> {
                    startTileDrag(v, TileOrderStore.Tile.TYPE_WIDGET, tile.id);
                    return true;
                });
                
                setTileDragListener(widgetView, tilePos);
                
                // Установить размер виджета в сетке
                int[] dims = getWidgetDimensions(tile.id);
                int[] gridPosition = placeGridItem(occupied, rows, dims[0], dims[1]);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = tileW * dims[0] + (dims[0] - 1) * 2 * m;
                lp.columnSpec = GridLayout.spec(gridPosition[0], dims[0]);
                lp.rowSpec = GridLayout.spec(gridPosition[1], dims[1]);
                applyTileVerticalMetrics(lp, m, tileH, gridPosition, dims[1], rowRemainder,
                        topInset, stretchedColumns);
                widgetView.setLayoutParams(lp);
                
                splitTilesGrid.addView(widgetView);
                continue;  // пропустить остальную обработку для этого элемента
            }

            if (TileOrderStore.Tile.TYPE_APP_WIDGET.equals(tile.type)) {
                AppWidgetStore.Entry entry = AppWidgetStore.find(sharedPreferences, tile.id);
                if (entry == null) continue;
                View appWidgetView = inf.inflate(R.layout.tile_embedded_app, splitTilesGrid, false);
                appWidgetTileViews.put(entry.id, appWidgetView);
                populateAppWidgetLauncher(appWidgetView, entry);
                
                // Перетаскивание всей плитки: кнопка в углу или долгий тап по карточке.
                View dragHandleLauncher = appWidgetView.findViewById(R.id.appWidgetDragHandleLauncher);
                if (dragHandleLauncher != null) {
                    dragHandleLauncher.setVisibility(View.VISIBLE);
                    dragHandleLauncher.setOnLongClickListener(v -> {
                        startTileDrag(appWidgetView, TileOrderStore.Tile.TYPE_APP_WIDGET, entry.id);
                        return true;
                    });
                }
                
                // Re-enable long click on root if needed.
                
                appWidgetView.setOnLongClickListener(v -> {
                    startTileDrag(appWidgetView, TileOrderStore.Tile.TYPE_APP_WIDGET, entry.id);
                    return true;
                });
                setTileDragListener(appWidgetView, tilePos);

                int widgetWidth = AppWidgetStore.clampWidth(entry.width);
                int widgetHeight = AppWidgetStore.clampHeight(entry.height);
                int[] gridPosition = placeGridItem(occupied, rows, widgetWidth, widgetHeight);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = tileW * widgetWidth + (widgetWidth - 1) * 2 * m;
                lp.columnSpec = GridLayout.spec(gridPosition[0], widgetWidth);
                lp.rowSpec = GridLayout.spec(gridPosition[1], widgetHeight);
                applyTileVerticalMetrics(lp, m, tileH, gridPosition, widgetHeight, rowRemainder,
                        topInset, stretchedColumns);
                appWidgetView.setLayoutParams(lp);
                splitTilesGrid.addView(appWidgetView);
                if (entry.autoStart) {
                    appWidgetView.postDelayed(() -> {
                        if (!embeddedWidgetSurfaces.containsKey(entry.id)) {
                            showEmbeddedAppWidget(appWidgetView, entry);
                        }
                    }, entry.autoStartDelay * 1000L);
                }
                continue;
            }
            
            if (TileOrderStore.Tile.TYPE_SPLIT.equals(tile.type)) {
                // Это плитка сплита
                SplitStore.Preset preset = splitMap.get(tile.id);
                if (preset == null || !preset.ready()) continue;  // пропускаем если сплит удалён
                
                final SplitStore.Preset finalPreset = preset;
                
                View tileView = inf.inflate(R.layout.tile_split, splitTilesGrid, false);
                ImageView icoL = tileView.findViewById(R.id.tileIcoLeft);
                ImageView icoR = tileView.findViewById(R.id.tileIcoRight);
                TextView title = tileView.findViewById(R.id.tileTitle);
                TextView ratio = tileView.findViewById(R.id.tileRatio);
                TextView state = tileView.findViewById(R.id.tileState);
                
                try { icoL.setImageDrawable(pm.getApplicationIcon(preset.l)); } catch (Exception ignored) {}
                try { icoR.setImageDrawable(pm.getApplicationIcon(preset.r)); } catch (Exception ignored) {}
                title.setText(preset.ll + "  |  " + preset.rl);
                ratio.setText(SplitStore.RATIO_LABELS[Math.max(0, Math.min(4, preset.ratio))]);
                state.setText("");
                tileView.setOnClickListener(v -> onSplitTileClick(finalPreset));
                
                // Долгий тап сразу начинает перемещение карточки.
                tileView.setOnLongClickListener(v -> {
                    startTileDrag(v, TileOrderStore.Tile.TYPE_SPLIT, preset.id);
                    return true;
                });
                
                setTileDragListener(tileView, tilePos);
                
                int[] gridPosition = placeGridItem(occupied, rows, 2, 1);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = tileW * 2 + m * 2;
                lp.columnSpec = GridLayout.spec(gridPosition[0], 2);
                lp.rowSpec = GridLayout.spec(gridPosition[1], 1);
                applyTileVerticalMetrics(lp, m, tileH, gridPosition, 1, rowRemainder,
                        topInset, stretchedColumns);
                tileView.setLayoutParams(lp);
                splitTilesGrid.addView(tileView);
                
            } else if (TileOrderStore.Tile.TYPE_APP.equals(tile.type)) {
                // Это плитка приложения
                String pkg = tile.id;

                // Проверяем, установлено ли приложение
                try {
                    pm.getApplicationInfo(pkg, 0);
                } catch (Exception e) {
                    // Приложение удалено — пропускаем и удаляем из списка
                    tiles.remove(pos);
                    TileOrderStore.save(sharedPreferences, tiles);
                    pos--;
                    continue;
                }
                
                View tileView = inf.inflate(R.layout.tile_app_shortcut, splitTilesGrid, false);
                ImageView ico = tileView.findViewById(R.id.tileIco);
                TextView title = tileView.findViewById(R.id.tileTitle);
                
                String name = pkg;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    name = pm.getApplicationLabel(ai).toString();
                    ico.setImageDrawable(pm.getApplicationIcon(ai));
                } catch (Exception ignored) {
                }
                title.setText(name);
                tileView.setOnClickListener(v -> onAppTileClick(pkg));

                // Долгий тап сразу начинает перемещение карточки.
                tileView.setOnLongClickListener(v -> {
                    startTileDrag(v, TileOrderStore.Tile.TYPE_APP, pkg);
                    return true;
                });
                
                setTileDragListener(tileView, tilePos);
                
                int[] gridPosition = placeGridItem(occupied, rows, 1, 1);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = tileW;
                lp.columnSpec = GridLayout.spec(gridPosition[0], 1);
                lp.rowSpec = GridLayout.spec(gridPosition[1]);
                applyTileVerticalMetrics(lp, m, tileH, gridPosition, 1, rowRemainder,
                        topInset, stretchedColumns);
                tileView.setLayoutParams(lp);
                splitTilesGrid.addView(tileView);
            }
        }
    }

    /**
     * Вертикальные метрики плитки: высота по номеру ряда плюс, при включённой
     * «Полноэкранной сетке», верхний отступ. Верхний отступ контента и сетки в этом режиме
     * снят, поэтому плитки верхнего ряда компенсируют его собственным отступом и остаются
     * на месте, а заданные настройкой первые {@code columns} колонок растягиваются до границы
     * экрана, сохраняя нижнюю кромку.
     */
    private static void applyTileVerticalMetrics(GridLayout.LayoutParams lp, int m, int tileH,
            int[] gridPosition, int span, int rowRemainder, int topInset, int columns) {
        int row = gridPosition[1];
        lp.height = tileHeight(tileH, m, row, span, rowRemainder);
        int topMargin = m;
        if (topInset > 0 && row == 0) {
            if (gridPosition[0] < columns) {
                // Снятый отступ возвращаем высотой, а не отступом: плитка доходит до границы.
                lp.height += m + topInset;
                topMargin = 0;
            } else {
                topMargin = m + topInset;
            }
        }
        lp.setMargins(m, topMargin, m, m);
    }

    /** Включена ли «Полноэкранная сетка» в настройках. */
    private boolean isFullscreenGridEnabled() {
        return sharedPreferences != null && sharedPreferences.getBoolean("fullscreenGrid", false);
    }

    /** Сколько левых колонок верхнего ряда растягивается до границы экрана (0…12). */
    private int fullscreenGridColumns() {
        if (sharedPreferences == null) return FULLSCREEN_GRID_COLUMNS_DEFAULT;
        return Math.max(0, Math.min(FULLSCREEN_GRID_COLUMNS_MAX,
                sharedPreferences.getInt("fullscreenGridColumns", FULLSCREEN_GRID_COLUMNS_DEFAULT)));
    }

    /** Верхний отступ контента: «Полноэкранная сетка» снимает его целиком. */
    private void applyContentTopInset() {
        View mainContent = findViewById(R.id.mainContent);
        if (mainContent == null) return;
        mainContent.setPadding(mainContent.getPaddingLeft(),
                fullscreenGrid ? 0 : contentInsetTop, mainContent.getPaddingRight(), 0);
    }

    /**
     * Высота плитки, занимающей {@code span} рядов начиная с ряда {@code row}.
     * Остаток деления высоты сетки на число рядов раздан верхним рядам по 1px, поэтому
     * многострочная плитка добавляет столько пикселей, сколько рядов она накрыла.
     */
    private static int tileHeight(int tileH, int m, int row, int span, int rowRemainder) {
        int extra = Math.max(0, Math.min(span, rowRemainder - row));
        return tileH * span + (span - 1) * 2 * m + extra;
    }

    /** Drop по карточке меняет две карточки местами. */
    private void swapTiles(int firstPos, int secondPos) {
        if (firstPos < 0 || secondPos < 0 || firstPos == secondPos) return;
        List<TileOrderStore.Tile> tiles = TileOrderStore.load(sharedPreferences);
        if (firstPos >= tiles.size() || secondPos >= tiles.size()) return;
        TileOrderStore.Tile first = tiles.get(firstPos);
        tiles.set(firstPos, tiles.get(secondPos));
        tiles.set(secondPos, first);
        TileOrderStore.save(sharedPreferences, tiles);
    }

    /** Drop между карточками вставляет элемент в найденную позицию и сдвигает остальные. */
    private void insertTileAt(int fromPos, int insertionPos) {
        List<TileOrderStore.Tile> tiles = TileOrderStore.load(sharedPreferences);
        if (fromPos < 0 || fromPos >= tiles.size()) return;
        TileOrderStore.Tile moved = tiles.remove(fromPos);
        if (fromPos < insertionPos) insertionPos--;
        insertionPos = Math.max(0, Math.min(insertionPos, tiles.size()));
        tiles.add(insertionPos, moved);
        TileOrderStore.save(sharedPreferences, tiles);
    }

    /** Найти позицию вставки по свободной области между уже отрисованными карточками. */
    private int findGridInsertionPosition(float x, float y) {
        List<TileOrderStore.Tile> tiles = TileOrderStore.load(sharedPreferences);
        for (int i = 5; i < splitTilesGrid.getChildCount(); i++) {
            View child = splitTilesGrid.getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof Integer) || child.getWidth() == 0 || child.getHeight() == 0) continue;
            int tilePos = (Integer) tag;
            if (y < child.getTop() || (y < child.getBottom() && x < child.getLeft())) {
                return tilePos;
            }
        }
        return tiles.size();
    }

    /** Обработчик drop по карточке: карточки меняются местами. */
    private void setTileDragListener(View view, int tilePos) {
        view.setTag(tilePos);
        view.setOnDragListener((target, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_ENTERED:
                    setDragTargetHighlight(target, true);
                    break;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    setDragTargetHighlight(target, false);
                    break;
                case DragEvent.ACTION_DROP:
                    setDragTargetHighlight(target, false);
                    swapTiles(draggedTilePosition, tilePos);
                    renderSplitTiles();
                    break;
            }
            return true;
        });
    }

    /** Начать drag для элемента общего списка. */
    private void startTileDrag(View view, String type, String id) {
        List<TileOrderStore.Tile> tiles = TileOrderStore.load(sharedPreferences);
        for (int i = 0; i < tiles.size(); i++) {
            TileOrderStore.Tile tile = tiles.get(i);
            if (type.equals(tile.type) && id.equals(tile.id)) {
                draggedTilePosition = i;
                view.startDragAndDrop(null, new View.DragShadowBuilder(view), null, 0);
                return;
            }
        }
    }

    /**
     * Клик по плитке-ярлыку приложения:
     *  - full  → открыть обычной задачей, которую системный hook ужмёт в окно;
     *  - light → обычный запуск приложения (без VD/root).
     */
    private void onAppTileClick(String pkg) {
        if (BuildConfig.IS_FULL) {
            if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) { showSnack("Сервис не готов"); return; }
            sendAppWindow(pkg);
        } else {
            launchAppNormally(pkg);
        }
    }

    private void populateAppWidgetLauncher(View widgetView, AppWidgetStore.Entry entry) {
        ViewGroup list = widgetView.findViewById(R.id.appWidgetList);
        if (list == null) return;
        list.removeAllViews();
        
        PackageManager pm = getPackageManager();
        LayoutInflater inf = LayoutInflater.from(this);
        entry.ensureProfiles();
        
        for (int i = 0; i < entry.profiles.size(); i++) {
            final int index = i;
            AppWidgetStore.Profile profile = entry.profiles.get(i);
            View item = inf.inflate(R.layout.item_app_widget_launcher_item, list, false);
            ImageView ico = item.findViewById(R.id.launcherItemIcon);
            TextView label = item.findViewById(R.id.launcherItemLabel);
            
            String name = profile.packageName;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(profile.packageName, 0);
                name = pm.getApplicationLabel(ai).toString();
                ico.setImageDrawable(pm.getApplicationIcon(ai));
            } catch (Exception ignored) {}
            
            label.setText(name);
            item.setOnClickListener(v -> {
                entry.selectedProfile = index;
                AppWidgetStore.update(sharedPreferences, entry);
                showEmbeddedAppWidget(widgetView, entry);
            });
            list.addView(item);
        }
        
        View scroll = widgetView.findViewById(R.id.appWidgetLauncherScroll);
        if (scroll != null) scroll.setVisibility(View.VISIBLE);
        
        View closeBtn = widgetView.findViewById(R.id.appWidgetClose);
        if (closeBtn != null) closeBtn.setVisibility(View.VISIBLE);
        
        View dragHandleLauncher = widgetView.findViewById(R.id.appWidgetDragHandleLauncher);
    }

    /** Переключить карточку в режим embedded VirtualDisplay (пакет из настроек виджета). */
    private void showEmbeddedAppWidget(View widgetView, AppWidgetStore.Entry entry) {
        AppWidgetStore.Profile profile = entry.selected();
        showEmbeddedAppWidget(widgetView, entry, profile.packageName, profile.dpi);
    }

    /** Переключить карточку в режим embedded VirtualDisplay для явно заданного пакета. */
    private void showEmbeddedAppWidget(View widgetView, AppWidgetStore.Entry entry,
                                       String packageName, int profileDpi) {
        if (!BuildConfig.IS_FULL) {
            launchAppNormally(packageName);
            return;
        }
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) {
            showSnack("Сервис не готов");
            return;
        }

        View scroll = widgetView.findViewById(R.id.appWidgetLauncherScroll);
        if (scroll != null) scroll.setVisibility(View.GONE);
        
        View dragHandleLauncher = widgetView.findViewById(R.id.appWidgetDragHandleLauncher);
        View controls = widgetView.findViewById(R.id.appWidgetControls);
        if (controls != null) {
            controls.setVisibility(View.GONE);
            View closeBtn = widgetView.findViewById(R.id.appWidgetClose);
            if (closeBtn != null) {
                closeBtn.setOnClickListener(v -> releaseEmbeddedWidget(entry.id, widgetView));
            }
            View dragBtn = widgetView.findViewById(R.id.appWidgetDragHandle);
            if (dragBtn != null) {
                dragBtn.setOnLongClickListener(v -> {
                    startTileDrag(widgetView, TileOrderStore.Tile.TYPE_APP_WIDGET, entry.id);
                    return true;
                });
            }
            
            View expandBtn = widgetView.findViewById(R.id.appWidgetExpand);
            if (expandBtn != null) {
                expandBtn.setVisibility(View.VISIBLE);
                expandBtn.setOnClickListener(v -> {
                    // Развернуть текущее приложение на весь экран (simpleLaunch)
                    releaseEmbeddedWidget(entry.id, widgetView);
                    if (BuildConfig.IS_FULL) {
                        sendAppWindow(packageName);
                    } else {
                        launchAppNormally(packageName);
                    }
                });
            }
        }
        
        ViewGroup container = widgetView.findViewById(R.id.appWidgetRoot);
        if (container == null) container = (ViewGroup) widgetView;

        TextureView textureView = new TextureView(this);
        textureView.setOpaque(true);
        textureView.setClipToOutline(true);
        textureView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float radius = 18f * getResources().getDisplayMetrics().density;
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        container.addView(textureView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        embeddedWidgetSurfaces.put(entry.id, textureView);

        final GestureDetector longClickDetector = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                private final Runnable hideControls = () -> {
                    if (controls != null) controls.setVisibility(View.GONE);
                };
                @Override
                public void onLongPress(MotionEvent e) {
                    if (controls != null) {
                        controls.setVisibility(View.VISIBLE);
                        controls.bringToFront();
                        uiHandler.removeCallbacks(hideControls);
                        uiHandler.postDelayed(hideControls, 3000);
                    }
                }
            });

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                texture.setDefaultBufferSize(width, height);
                Surface output = new Surface(texture);
                embeddedWidgetOutputs.put(entry.id, output);
                sendEmbeddedSurface(entry.id, packageName, profileDpi, output, width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture,
                                                    int width, int height) {
                texture.setDefaultBufferSize(width, height);
                Surface output = embeddedWidgetOutputs.get(entry.id);
                if (output != null) {
                    sendEmbeddedSurface(entry.id, packageName, profileDpi, output, width, height);
                }
            }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                embeddedWidgetSurfaces.remove(entry.id);
                Surface output = embeddedWidgetOutputs.remove(entry.id);
                if (output != null) output.release();
                sendEmbeddedRelease(entry.id);
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
        textureView.setOnTouchListener((v, event) -> {
            longClickDetector.onTouchEvent(event);
            
            // Проверяем, попал ли touch в область кнопок управления
            if (controls != null && controls.getVisibility() == View.VISIBLE) {
                int[] location = new int[2];
                controls.getLocationOnScreen(location);
                int touchX = (int) event.getRawX();
                int touchY = (int) event.getRawY();
                if (touchX >= location[0] && touchX < location[0] + controls.getWidth()
                        && touchY >= location[1] && touchY < location[1] + controls.getHeight()) {
                    // Touch попал в область кнопок - пропускаем дальше
                    return false;
                }
            }
            
            boolean gestureFinished = event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            v.getParent().requestDisallowInterceptTouchEvent(!gestureFinished);
            sendEmbeddedTouch(entry.id, event);
            return true;
        });
        
        if (controls != null) controls.bringToFront();
    }

    private void releaseEmbeddedWidget(String widgetId, View widgetView) {
        embeddedWidgetSurfaces.remove(widgetId);
        Surface output = embeddedWidgetOutputs.remove(widgetId);
        if (output != null) output.release();
        
        ViewGroup container = widgetView.findViewById(R.id.appWidgetRoot);
        if (container == null) container = (ViewGroup) widgetView;
        
        // Удалить TextureView (он обычно последний добавленный)
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View child = container.getChildAt(i);
            if (child instanceof TextureView) {
                container.removeViewAt(i);
            }
        }
        
        View controls = widgetView.findViewById(R.id.appWidgetControls);
        if (controls != null) controls.setVisibility(View.GONE);

        View expandBtn = widgetView.findViewById(R.id.appWidgetExpand);
        if (expandBtn != null) expandBtn.setVisibility(View.GONE);

        View closeBtn = widgetView.findViewById(R.id.appWidgetClose);
        if (closeBtn != null) closeBtn.setVisibility(View.GONE);
        
        sendEmbeddedRelease(widgetId);
        
        // Возвращаем лончер
        AppWidgetStore.Entry entry = AppWidgetStore.find(sharedPreferences, widgetId);
        if (entry != null) {
            populateAppWidgetLauncher(widgetView, entry);
        }
    }

    private void sendEmbeddedSurface(String widgetId, String pkg, int dpi, Surface surface,
                                     int width, int height) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null || surface == null) return;
        try {
            Message message = Message.obtain(null, MSG_SPLIT_LAUNCH_VD, 1, 0);
            Bundle data = new Bundle();
            data.putString("left", pkg);
            data.putString("widgetId", widgetId);
            data.putBoolean("embeddedSurface", true);
            data.putParcelable("surface", surface);
            data.putInt("width", width);
            data.putInt("height", height);
            data.putInt("leftDpi", AppWidgetStore.normalizeDpi(dpi));
            message.setData(data);
            message.replyTo = GlobalVars.clientMessenger;
            GlobalVars.serviceMessenger.send(message);
            Log.i(TAG, "sendEmbeddedSurface widget=" + widgetId + " pkg=" + pkg
                    + " size=" + width + "x" + height);
        } catch (RemoteException e) {
            Log.w(TAG, "sendEmbeddedSurface failed: " + e.getMessage());
        }
    }

    private void sendEmbeddedTouch(String widgetId, MotionEvent event) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) return;
        try {
            Message message = Message.obtain(null, MSG_SPLIT_LAUNCH_VD, 1, 0);
            Bundle data = new Bundle();
            data.putString("widgetId", widgetId);
            data.putBoolean("embeddedTouch", true);
            data.putParcelable("event", MotionEvent.obtain(event));
            message.setData(data);
            message.replyTo = GlobalVars.clientMessenger;
            GlobalVars.serviceMessenger.send(message);
        } catch (RemoteException e) {
            Log.w(TAG, "sendEmbeddedTouch failed: " + e.getMessage());
        }
    }

    private void sendEmbeddedRelease(String widgetId) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) return;
        try {
            Message message = Message.obtain(null, MSG_SPLIT_LAUNCH_VD, 1, 0);
            Bundle data = new Bundle();
            data.putString("widgetId", widgetId);
            data.putBoolean("embeddedRelease", true);
            message.setData(data);
            message.replyTo = GlobalVars.clientMessenger;
            GlobalVars.serviceMessenger.send(message);
        } catch (RemoteException e) {
            Log.w(TAG, "sendEmbeddedRelease failed: " + e.getMessage());
        }
    }

    /** LIGHT: обычный запуск приложения на дефолтном дисплее (без VirtualDisplay). */
    private void launchAppNormally(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) { showSnack("Не удалось открыть приложение"); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Log.i(TAG, "launchAppNormally " + pkg);
        } catch (Exception e) {
            showSnack("Не удалось открыть приложение");
            Log.w(TAG, "launchAppNormally " + pkg + ": " + e.getMessage());
        }
    }

    /** Native трактует пустой right как отдельную physical task целевого пакета. */
    private void sendAppWindow(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        int dpi = AppDpiStore.get(sharedPreferences, pkg);
        try {
            Message m = Message.obtain(null, MSG_SPLIT_LAUNCH_VD, 1, 0);
            Bundle b = new Bundle();
            b.putString("left", pkg);
            b.putString("right", "");     // пусто = не VD, а physical task + системный frame clamp
            b.putInt("leftDpi", dpi);      // hook использует зеркальный Settings.Global per-package DPI
            b.putInt("rightDpi", 0);
            m.setData(b);
            m.replyTo = GlobalVars.clientMessenger;
            GlobalVars.serviceMessenger.send(m);
            Log.i(TAG, "sendAppWindow " + pkg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /** Клик по плитке сплита: открываем VD-хост (per-app DPI, живой ресайз, свап). Закрытие — в самом хосте. */
    private void onSplitTileClick(SplitStore.Preset preset) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) { showSnack("Сервис не готов"); return; }
        sendSplitVd(preset);
    }

    /** Индекс пресета в сохранённом списке — по нему Native вернёт выставленную рукой пропорцию. */
    private int presetIndex(SplitStore.Preset preset) {
        List<SplitStore.Preset> all = SplitStore.load(sharedPreferences);
        for (int i = 0; i < all.size(); i++) {
            SplitStore.Preset p = all.get(i);
            if (p.l.equals(preset.l) && p.r.equals(preset.r) && p.ratio == preset.ratio) return i;
        }
        return -1;
    }

    /** Запуск сплита на VirtualDisplay: пакеты + соотношение + per-app DPI (из {@link AppDpiStore}). */
    private void sendSplitVd(SplitStore.Preset preset) {
        if (preset.l == null || preset.l.isEmpty() || preset.r == null || preset.r.isEmpty()) return;
        int lDpi = AppDpiStore.get(sharedPreferences, preset.l);
        int rDpi = AppDpiStore.get(sharedPreferences, preset.r);
        try {
            Message m = Message.obtain(null, MSG_SPLIT_LAUNCH_VD, preset.ratio, 0);
            Bundle b = new Bundle();
            b.putString("left", preset.l);
            b.putString("right", preset.r);
            b.putInt("leftDpi", lDpi);
            b.putInt("rightDpi", rDpi);
            // Изменяемая пропорция: доля левого окна + индекс пресета, чтобы Native вернул новое
            // значение обратно (SPLIT_RATIO_SAVE) и оно пережило перезапуск сплита.
            b.putBoolean("resizable", preset.resizable);
            b.putFloat("split", SplitStore.leftFraction(preset));
            b.putInt("presetIdx", presetIndex(preset));
            b.putString("presetId", preset.id);
            m.setData(b);
            m.replyTo = GlobalVars.clientMessenger;
            GlobalVars.serviceMessenger.send(m);
            Log.i(TAG, "sendSplitVd left=" + preset.l + " right=" + preset.r
                    + " ratio=" + preset.ratio + " lDpi=" + lDpi + " rDpi=" + rDpi);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (String widgetId : new ArrayList<>(embeddedWidgetSurfaces.keySet())) {
            sendEmbeddedRelease(widgetId);
        }
        embeddedWidgetSurfaces.clear();
        for (Surface output : embeddedWidgetOutputs.values()) output.release();
        embeddedWidgetOutputs.clear();
        try { unregisterReceiver(tripReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(batteryHeatReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(settingSyncReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(powerHoldStatusReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(embeddedLeftReceiver); } catch (Exception ignored) {}
        uiHandler.removeCallbacks(tripTick);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        uiHandler.removeCallbacks(tripTick);
        releaseMessengerBinding("onDestroy");
        super.onDestroy();
    }
}
