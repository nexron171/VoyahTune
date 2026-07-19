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
    static final int MSG_SPLIT_LAUNCH_VD    = 34; // сплит/одиночное приложение на VirtualDisplay (per-app DPI)
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
    private View tripCard, cardPowerHold, cardWashMode, cardAutoLight, cardPedestrian;
    private boolean tripActive = false, tripInDrive = false;
    private long tripAccumMs = 0L, tripDriveStartElapsed = 0L;
    private String lastTripsJson = "[]"; // снимок лога для экрана истории

    // Тоггл-карточки на главном (автосвет / звук пешеходов): нейтральные, состояние — капсула-тег
    private TextView autoLightBadge, pedestrianBadge;
    private boolean autoLightOn, pedestrianOn;

    // Сплиты — прокручиваемая сетка плиток по пресетам из «Разделение экрана»
    private android.widget.GridLayout splitTilesGrid;

    // Левый док-лончер (CarPlay-style): вертикальная колонка иконок = дубли плиток сплитов + ярлыков
    private android.widget.LinearLayout dockIcons;

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

            editor.putString("StarButton", StarButton);
            editor.putInt("StarButtonCount", StarButtonCount);
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

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "onServiceConnected()");
            GlobalVars.serviceMessenger = new Messenger(service);
            GlobalVars.isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            GlobalVars.serviceMessenger = null;
            GlobalVars.isBound = false;
        }
    };

    private void bindToMessengerService() {
        Log.i(TAG, "bindToMessengerService() begin");

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "ru.big.town.anative",
                "ru.big.town.anative.SetModesService"
        ));
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
        Log.i(TAG, "bindToMessengerService() end");

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

    /** Перечитать состояние из prefs (напр. после «Дополнительно») и обновить вид. */
    private void refreshToggles() {
        autoLightOn  = sharedPreferences.getBoolean("autoLight", false);
        pedestrianOn = !sharedPreferences.getBoolean("disablePedestrianSound", false);
        updateToggleVisuals();
    }

    /** Состояние карточки — капсула-тег: голубая «активно» / серая «не активно». */
    private void updateToggleVisuals() {
        applyBadge(autoLightBadge, autoLightOn);
        applyBadge(pedestrianBadge, pedestrianOn);
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

        // Инсеты раздаём двум колонкам: док прижат к левому краю (его фон тянется на всю
        // высоту, а иконки/кубик уходят под статус-бар и над навбаром), контент — правее.
        final View dock = findViewById(R.id.dock);
        final View mainContent = findViewById(R.id.mainContent);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            dock.setPadding(sb.left, sb.top, 0, sb.bottom);
            mainContent.setPadding(0, sb.top, sb.right, sb.bottom);
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
        splitTilesGrid = findViewById(R.id.splitTilesGrid);
        dockIcons      = findViewById(R.id.dockIcons);

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

        resultIntent.putExtra("StarButton", StarButton);
        resultIntent.putExtra("StarButtonCount", StarButtonCount);
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
        Log.i("$$$ Main onButtonClickAdvance $$$", String.format("%s %d", StarButton, StarButtonCount));
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
        refreshToggles();   // подхватить изменения, сделанные в «Дополнительно»
        applyMainScreenVisibility();
        renderSplitTiles();
        renderDock();
    }

    // Правило дока: максимум 6 плиток. Сплиты (до 3) идут первыми, приложения занимают ОСТАТОК
    // до 6 (т.е. до 6 − число_сплитов). Нет сплитов → до 6 приложений; 2 сплита → до 4 приложений.
    private static final int DOCK_MAX = 6;
    private static final int DOCK_MAX_SPLITS = 3;

    /** Готовые сплиты для дока (не больше DOCK_MAX_SPLITS, в порядке пресетов). */
    private java.util.List<SplitStore.Preset> dockSplitList() {
        java.util.List<SplitStore.Preset> out = new java.util.ArrayList<>();
        for (SplitStore.Preset p : SplitStore.load(sharedPreferences)) {
            if (!p.ready()) continue;
            out.add(p);
            if (out.size() >= DOCK_MAX_SPLITS) break;
        }
        return out;
    }

    /** Ярлыки приложений для дока: занимают остаток до DOCK_MAX после сплитов. */
    private java.util.List<String> dockAppList(int splitsShown) {
        int cap = DOCK_MAX - splitsShown;
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : AppShortcutStore.load(sharedPreferences)) {
            if (out.size() >= cap) break;
            out.add(p);
        }
        return out;
    }

    /** Наполнить левый док иконками: те же сплиты и ярлыки, что и на главном, но компактно (CarPlay). */
    private void renderDock() {
        if (dockIcons == null) return;
        dockIcons.removeAllViews();
        android.content.pm.PackageManager pm = getPackageManager();
        android.view.LayoutInflater inf = android.view.LayoutInflater.from(this);
        float d = getResources().getDisplayMetrics().density;
        int gap = (int) (7 * d);

        // Сплиты (две мини-иконки) — открывают VD-хост
        java.util.List<SplitStore.Preset> splits = dockSplitList();
        for (SplitStore.Preset ps : splits) {
            final SplitStore.Preset preset = ps;
            View item = inf.inflate(R.layout.item_dock_split, dockIcons, false);
            android.widget.ImageView l = item.findViewById(R.id.dockIcoLeft);
            android.widget.ImageView r = item.findViewById(R.id.dockIcoRight);
            try { l.setImageDrawable(pm.getApplicationIcon(ps.l)); } catch (Exception ignored) {}
            try { r.setImageDrawable(pm.getApplicationIcon(ps.r)); } catch (Exception ignored) {}
            item.setOnClickListener(v -> onSplitTileClick(preset));
            addDockItem(item, gap);
        }

        // Ярлыки приложений (одна иконка) — открывают приложение полноэкранно на VD
        for (String pkg : dockAppList(splits.size())) {
            final String p = pkg;
            View item = inf.inflate(R.layout.item_dock_app, dockIcons, false);
            android.widget.ImageView ico = item.findViewById(R.id.dockIco);
            try { ico.setImageDrawable(pm.getApplicationIcon(pkg)); } catch (Exception ignored) {}
            item.setOnClickListener(v -> onAppTileClick(p));
            addDockItem(item, gap);
        }
    }

    private void addDockItem(View item, int bottomGap) {
        // WRAP_CONTENT по ширине + gravity=center_horizontal у колонки → иконки строго по центру дока.
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomGap;
        dockIcons.addView(item, lp);
    }

    /** Домик в доке — выход на системный главный экран (мы уже на нашем «лончере»). */
    public void onDockHome(View v) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "onDockHome (system home) failed: " + e.getMessage());
        }
    }

    // Модель дока для SplitHostActivity: те же ярлыки/сплиты, чтобы док был и поверх открытых окон.
    // Формат: dockApps = "pkg|dpi"; dockSplits = "lpkg|rpkg|ratio|leftDpi|rightDpi".
    private String[] buildDockApps() {
        java.util.List<String> apps = dockAppList(dockSplitList().size());
        String[] out = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            String p = apps.get(i);
            out[i] = p + "|" + AppDpiStore.get(sharedPreferences, p);
        }
        return out;
    }

    private String[] buildDockSplits() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SplitStore.Preset p : dockSplitList()) {
            out.add(p.l + "|" + p.r + "|" + p.ratio
                    + "|" + AppDpiStore.get(sharedPreferences, p.l)
                    + "|" + AppDpiStore.get(sharedPreferences, p.r));
        }
        return out.toArray(new String[0]);
    }

    /** Скрыть/показать карточки главного экрана по настройкам раздела «Главный экран». */
    private void applyMainScreenVisibility() {
        setCardVisible(tripCard,       "showTripTimer");
        setCardVisible(cardPowerHold,  "showPowerHold");
        setCardVisible(cardWashMode,   "showWashMode");
        setCardVisible(cardAutoLight,  "showAutoLight");
        setCardVisible(cardPedestrian, "showPedestrian");
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
        java.util.List<SplitStore.Preset> list = SplitStore.load(sharedPreferences);
        android.content.pm.PackageManager pm = getPackageManager();
        android.view.LayoutInflater inf = android.view.LayoutInflater.from(this);
        float d = getResources().getDisplayMetrics().density;
        int tileH = (int) (150 * d), m = (int) (6 * d);
        final int cols = 5;
        int shown = 0;

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

        // Плитки-ярлыки приложений (обычный запуск на весь экран), в той же сетке
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

    /** Клик по плитке-ярлыку: открыть приложение полноэкранно на нашем VirtualDisplay (per-app DPI, те же отступы). */
    private void onAppTileClick(String pkg) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) { showSnack("Сервис не готов"); return; }
        sendAppVd(pkg);
    }

    /** Запуск одиночного приложения полноэкранно на нашем VirtualDisplay (пустой right = single mode). */
    private void sendAppVd(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        int dpi = AppDpiStore.get(sharedPreferences, pkg);
        try {
            Message m = Message.obtain(null, MSG_SPLIT_LAUNCH_VD, 1, 0);
            Bundle b = new Bundle();
            b.putString("left", pkg);
            b.putString("right", "");     // пусто = одиночное полноэкранное окно на VD
            b.putInt("leftDpi", dpi);
            b.putInt("rightDpi", 0);
            b.putStringArray("dockApps", buildDockApps());
            b.putStringArray("dockSplits", buildDockSplits());
            m.setData(b);
            m.replyTo = GlobalVars.clientMessenger;
            GlobalVars.serviceMessenger.send(m);
            Log.i(TAG, "sendAppVd " + pkg + " dpi=" + dpi);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /** Клик по плитке сплита: открываем VD-хост (per-app DPI, живой ресайз, свап). Закрытие — в самом хосте. */
    private void onSplitTileClick(SplitStore.Preset preset) {
        if (!GlobalVars.isBound || GlobalVars.serviceMessenger == null) { showSnack("Сервис не готов"); return; }
        sendSplitVd(preset);
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
            b.putStringArray("dockApps", buildDockApps());
            b.putStringArray("dockSplits", buildDockSplits());
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
        uiHandler.removeCallbacks(tripTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(tripTick);
        if (GlobalVars.isBound) {
            unbindService(connection);
            GlobalVars.isBound = false;
        }
    }
}
