package ru.big.town.restoremode;


import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
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
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;


public class MainActivity extends AppCompatActivity {
    private String driveMode="INDIVIDUAL";
    private String energy="SREV";
    private  String recycle="LOW";
    private String StarButton="";
    private int StarButtonCount=1;

    private String StarButtonStarButton1="";
    private String StarButtonStarButton2="";

    private String customCommand="";
    private int customCommandCount=1;

    private SharedPreferences sharedPreferences;
    static final int MSG_RESULT             = 4;
    static final int MSG_APPLY_DRIVE_MODES  = 1;
    static final int MSG_AUTO_LIGHT_ENABLE  = 10;
    static final int MSG_AUTO_LIGHT_DISABLE = 11;
    static final int MSG_LEAVE_CAR          = 20;
    static final int MSG_APPLY_PEDESTRIAN   = 21;
    static final int MSG_WASH_MODE          = 23;
    static final int MSG_SPLIT_LAUNCH_VD    = 34; // single → physical WM-clamped task; pair → VD split
    static final int MSG_APPLY_FORCED_EV    = 35; // форсированный электрорежим (arg1: 1=вкл)
    static final int REQUEST_CODE           = 1;
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
    private boolean tripActive = false, tripInDrive = false;
    private long tripAccumMs = 0L, tripDriveStartElapsed = 0L;
    private String lastTripsJson = "[]"; // снимок лога для экрана истории

    // Тоггл-карточки на главном (автосвет / звук пешеходов): нейтральные, состояние — капсула-тег
    private TextView autoLightBadge, pedestrianBadge, forcedEvBadge;
    private boolean autoLightOn, pedestrianOn, forcedEvOn;

    // -------- Виджет «Прогрев батареи» --------
    static final String ACTION_BATTERY_HEAT_UPDATE   = "ru.big.town.anative.BATTERY_HEAT_UPDATE";
    static final String ACTION_REQUEST_BATTERY_HEAT  = "ru.big.town.anative.REQUEST_BATTERY_HEAT";
    static final String ACTION_BATTERY_HEAT_ACTIVATE = "ru.big.town.anative.BATTERY_HEAT_ACTIVATE";
    private static final int BH_UNKNOWN = Integer.MIN_VALUE;
    private static final int BH_TEMP_INVALID = -9999;
    private View cardBatteryHeat;
    private android.widget.ImageView batteryHeatIcon;
    private TextView batteryHeatState, batteryHeatTemp, batteryHeatStatus, batteryHeatFail;
    // Палитра состояний прогрева (цвет = состояние термоменеджмента ВВБ)
    private static final int BH_COLOR_COLD    = 0xFF3D7FD0; // синий — на улице холодно, прогрев уместен
    private static final int BH_COLOR_HEATING = 0xFF35B06A; // зелёный — идёт прогрев
    private static final int BH_COLOR_NORMAL  = 0xFF6B7280; // серый — норма / нет данных
    private static final int BH_COLOR_WARN    = 0xFFD0A92F; // жёлтый — внимание (прогрев невозможен)
    private static final int BH_COLOR_FAULT   = 0xFFD04A4A; // красный — неисправность ВВБ

    // Сплиты — прокручиваемая сетка плиток по пресетам из «Разделение экрана»
    private android.widget.GridLayout splitTilesGrid;

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
        int preheat = intent.getIntExtra("preheatSet",    BH_UNKNOWN);
        int bms     = intent.getIntExtra("bmsState",      BH_UNKNOWN);
        int autoCtl = intent.getIntExtra("autoCtrl",      BH_UNKNOWN);
        int fail    = intent.getIntExtra("failReason",    BH_UNKNOWN);

        int threshold = intent.getIntExtra("tempThreshold", 10);
        boolean tempValid = temp != BH_TEMP_INVALID && temp != BH_UNKNOWN;

        batteryHeatTemp.setText(tempValid ? "за бортом: " + temp + " °C" : "за бортом: —");

        String preheatTxt = (bms == 9) ? "идёт" : bhOnOff(preheat);
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

        applyBatteryHeatIndicator(status, bms, fail, temp, tempValid, threshold);
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
                                           int temp, boolean tempValid, int threshold) {
        int color; String label;
        boolean anyData = status != BH_UNKNOWN || bms != BH_UNKNOWN || fail != BH_UNKNOWN || tempValid;
        if (bms == 8) {                              // BMS_STATE_FAULT
            color = BH_COLOR_FAULT;   label = "Неисправность";
        } else if (status == 1 || bms == 9) {        // активный нагрев / preheat
            color = BH_COLOR_HEATING; label = "Прогрев";
        } else if (fail >= 1 && fail <= 4) {         // прогрев невозможен
            color = BH_COLOR_WARN;    label = "Внимание";
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
        showSnack("Запуск прогрева батареи…");
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
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
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
        autoLightBadge  = findViewById(R.id.autoLightBadge);
        pedestrianBadge = findViewById(R.id.pedestrianBadge);
        forcedEvBadge   = findViewById(R.id.forcedEvBadge);
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
            mainContent.setPadding(nativeDock + sb.left, top, sb.right, sb.bottom);
            return insets;
        });

        sharedPreferences = getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        GlobalVars.sharedPreferences=sharedPreferences;

        tripTimer  = findViewById(R.id.tripTimer);
        tripStatus = findViewById(R.id.tripStatus);
        tripCard   = findViewById(R.id.tripCard);
        cardPowerHold  = findViewById(R.id.cardLeaveCar);
        cardWashMode   = findViewById(R.id.cardWashMode);
        cardAutoLight  = findViewById(R.id.cardAutoLight);
        cardPedestrian = findViewById(R.id.cardPedestrian);
        cardForcedEv   = findViewById(R.id.cardForcedEv);
        cardBatteryHeat   = findViewById(R.id.cardBatteryHeat);
        batteryHeatIcon   = findViewById(R.id.batteryHeatIcon);
        batteryHeatState  = findViewById(R.id.batteryHeatState);
        batteryHeatTemp   = findViewById(R.id.batteryHeatTemp);
        batteryHeatStatus = findViewById(R.id.batteryHeatStatus);
        batteryHeatFail   = findViewById(R.id.batteryHeatFail);
        splitTilesGrid = findViewById(R.id.splitTilesGrid);

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

        resultIntent.putExtra("StarButtonStarButton1", StarButtonStarButton1);
        resultIntent.putExtra("StarButtonStarButton2", StarButtonStarButton2);
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
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Power Hold")
                .setMessage("Режим будет активен сразу после подтверждения, дополнительной индикации активности не последует - просто заприте машину и убедитесь, что она не уснула")
                .setPositiveButton("Активировать", (d, w) -> {
                    boolean ok = sendMessageToService(MSG_LEAVE_CAR);
                    showSnack(ok ? "Power Hold режим активирован" : "Сервис не готов");
                    Log.i(TAG, "onButtonLeaveCar sent=" + ok);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Режим мойки — машина засыпает и не реагирует на открытие дверей. */
    public void onCardWashMode(View v){
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.DarkDialog)
                .setTitle("Режим мойки")
                .setMessage("Машина уснёт и не будет реагировать на открытие дверей. Активировать режим мойки?")
                .setPositiveButton("Активировать", (d, w) -> {
                    boolean ok = sendMessageToService(MSG_WASH_MODE);
                    showSnack(ok ? "Режим мойки активирован" : "Сервис не готов");
                    Log.i(TAG, "onCardWashMode sent=" + ok);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Snackbar вместо Toast — в Android Automotive системные тосты приложений не показываются. */
    private void showSnack(String text) {
        com.google.android.material.snackbar.Snackbar.make(
                findViewById(R.id.main), text,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
    }

    /** Открыть системный экран настроек Android. */
    public void onCardAndroidSettings(View v){
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(this,
                    "Не удалось открыть настройки Android", android.widget.Toast.LENGTH_SHORT).show();
            Log.w(TAG, "onCardAndroidSettings failed: " + e.getMessage());
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
        Log.i("$$$ Main onButtonClickAdvanceStarButton $$$", String.format("%s %s", StarButtonStarButton1, StarButtonStarButton2));
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
                "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE", null, RECEIVER_EXPORTED);
        Intent bhReq = new Intent(ACTION_REQUEST_BATTERY_HEAT);
        bhReq.setPackage("ru.big.town.anative");
        sendBroadcast(bhReq);
        refreshToggles();   // подхватить изменения, сделанные в «Дополнительно»
        applyMainScreenVisibility();
        renderSplitTiles();
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

    /** Рисует плитки сплитов по готовым пресетам (иконки приложений + соотношение), 5 в ряд. */
    private void renderSplitTiles() {
        if (splitTilesGrid == null) return;
        splitTilesGrid.removeAllViews();
        // Якоря 5 колонок (равные доли ширины) → плитки всегда 1/5, не растягиваются на всю ширину
        for (int c = 0; c < 5; c++) {
            android.widget.Space anchor = new android.widget.Space(this);
            android.widget.GridLayout.LayoutParams alp = new android.widget.GridLayout.LayoutParams();
            alp.width = 0; alp.height = 0;
            alp.columnSpec = android.widget.GridLayout.spec(c, 1, 1f);
            alp.rowSpec = android.widget.GridLayout.spec(0);
            anchor.setLayoutParams(alp);
            splitTilesGrid.addView(anchor);
        }
        android.content.pm.PackageManager pm = getPackageManager();
        android.view.LayoutInflater inf = android.view.LayoutInflater.from(this);
        float d = getResources().getDisplayMetrics().density;
        int tileH = (int) (150 * d), m = (int) (6 * d);
        final int cols = 5;
        int shown = 0;

        // Плитки сплитов — только в full (в light сплита нет; ниже остаются лишь ярлыки приложений).
        if (BuildConfig.IS_FULL) {
            java.util.List<SplitStore.Preset> list = SplitStore.load(sharedPreferences);
            for (int i = 0; i < list.size(); i++) {
                SplitStore.Preset ps = list.get(i);
                if (!ps.ready()) continue;             // на главный попадают только полностью заданные
                final SplitStore.Preset preset = ps;

                View tile = inf.inflate(R.layout.item_split_tile, splitTilesGrid, false);
                android.widget.ImageView icoL = tile.findViewById(R.id.tileIcoLeft);
                android.widget.ImageView icoR = tile.findViewById(R.id.tileIcoRight);
                TextView title = tile.findViewById(R.id.tileTitle);
                TextView ratio = tile.findViewById(R.id.tileRatio);
                TextView state = tile.findViewById(R.id.tileState);

                try { icoL.setImageDrawable(pm.getApplicationIcon(ps.l)); } catch (Exception ignored) {}
                try { icoR.setImageDrawable(pm.getApplicationIcon(ps.r)); } catch (Exception ignored) {}
                title.setText(ps.ll + "  |  " + ps.rl);
                ratio.setText(SplitStore.RATIO_LABELS[Math.max(0, Math.min(4, ps.ratio))]);
                state.setText("");
                tile.setOnClickListener(v -> onSplitTileClick(preset));

                android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
                lp.width = 0;
                lp.height = tileH;
                lp.columnSpec = android.widget.GridLayout.spec(shown % cols, 1, 1f);
                lp.rowSpec = android.widget.GridLayout.spec(shown / cols);
                lp.setMargins(m, m, m, m);
                tile.setLayoutParams(lp);
                splitTilesGrid.addView(tile);
                shown++;
            }
        }

        // Плитки-ярлыки приложений (full — окно на физическом display, light — обычный запуск)
        for (String pkg : AppShortcutStore.load(sharedPreferences)) {
            final String p = pkg;
            View tile = inf.inflate(R.layout.item_app_tile, splitTilesGrid, false);
            android.widget.ImageView ico = tile.findViewById(R.id.tileIco);
            TextView title = tile.findViewById(R.id.tileTitle);
            String name = pkg;
            try {
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                name = pm.getApplicationLabel(ai).toString();
                ico.setImageDrawable(pm.getApplicationIcon(ai));
            } catch (Exception ignored) {
            }
            title.setText(name);
            tile.setOnClickListener(v -> onAppTileClick(p));

            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = tileH;
            lp.columnSpec = android.widget.GridLayout.spec(shown % cols, 1, 1f);
            lp.rowSpec = android.widget.GridLayout.spec(shown / cols);
            lp.setMargins(m, m, m, m);
            tile.setLayoutParams(lp);
            splitTilesGrid.addView(tile);
            shown++;
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
        java.util.List<SplitStore.Preset> all = SplitStore.load(sharedPreferences);
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
        try { unregisterReceiver(tripReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(batteryHeatReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(settingSyncReceiver); } catch (Exception ignored) {}
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
