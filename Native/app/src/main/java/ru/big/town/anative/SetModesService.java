package ru.big.town.anative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.car.hardware.power.CarPowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.util.Arrays;
import java.util.List;


public class SetModesService extends Service {

    private Messenger clientMessenger;
    static final int MSG_APPLY_DRIVE_MODES          = 1;
    static final int MSG_APPLY_DRIVE_MODES_STAR_BUTTON = 2;
    static final int MSG_RESULT                     = 4;
    static final int STATE_ON                       = 6;
    static final int STATE_SHUTDOWN_PREPARE         = 7;
    static final int MSG_AUTO_LIGHT_ENABLE          = 10; // включить автосвет (уличный датчик + фолбэк салонный)
    static final int MSG_AUTO_LIGHT_DISABLE         = 11; // выключить автосвет
    static final int MSG_LEAVE_CAR                  = 20; // быстрая активация leave car / power hold
    static final int MSG_APPLY_PEDESTRIAN           = 21; // применить звук пешеходов (arg1: 1=заглушить)
    static final int MSG_REBOOT                     = 22; // перезагрузка системы (голова)
    static final int MSG_WASH_MODE                  = 23; // активация режима мойки
    static final int MSG_FLOATING_BACK              = 24; // плавающая кнопка «Назад» (arg1: 1=вкл)
    static final int MSG_FLOATING_BACK_SIDE         = 25; // сторона кнопки (arg1: 0 лево, 1 верх, 2 право)
    static final int MSG_GRANT_INSTALL              = 26; // выдать app-op установки из неизв. источников (data: "pkg")
    static final int MSG_CLOSE_ALL                  = 27; // закрыть все сторонние приложения (forceStopPackage)
    static final int MSG_SET_THEME                  = 28; // тема системы/приложений (arg1: 0 авто, 1 светлая, 2 тёмная)
    static final int MSG_LOGGING_ENABLE             = 32; // вкл/выкл захват логов в файл (arg1: 1=вкл)
    static final int MSG_LOGGING_SHARE              = 33; // «Выгрузить логи» → share лог-файла
    static final int MSG_SPLIT_LAUNCH_VD            = 34; // сплит на VirtualDisplay (data left/right, arg1=ratio, data leftDpi/rightDpi)
    static final String ACTION_REQUEST_LOG = "ru.big.town.anative.REQUEST_LOG";
    static final String ACTION_LOG_UPDATE  = "ru.big.town.anative.LOG_UPDATE";
    static final String ACTION_LOGGING_SET   = "ru.big.town.anative.LOGGING_SET";   // extra "on" bool
    static final String ACTION_LOGGING_SHARE = "ru.big.town.anative.LOGGING_SHARE";
    static final String RESTOREMODE_PKG   = "ru.big.town.restoremode";
    static final String RESTOREMODE_MAIN  = "ru.big.town.restoremode.MainActivity";
    static final String FLOATING_BACK_A11Y = "ru.big.town.anative/ru.big.town.anative.BackButtonService";
    static final String TAG = "$$$ SetModesService $$$";

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_APPLY_DRIVE_MODES:
                    clientMessenger = msg.replyTo;
                    // MSG_RESULT отправим по ЗАВЕРШЕНИИ цикла применения, чтобы клиент держал
                    // кнопку «Применить» заблокированной всё время отправки.
                    final Messenger replyTo = msg.replyTo;
                    ApplyEngine.applyNow(8, 250, () -> notifyApplyDone(replyTo));
                    Log.i(TAG, "handleMessage() MSG_APPLY_DRIVE_MODES");
                    break;
                case MSG_APPLY_DRIVE_MODES_STAR_BUTTON:
                    clientMessenger = msg.replyTo;
                    worker(1, 100, MSG_APPLY_DRIVE_MODES_STAR_BUTTON, msg.arg1);
                    Log.i(TAG, "handleMessage() MSG_APPLY_DRIVE_MODES_STAR_BUTTON");
                    notifyApplyDone(msg.replyTo);
                    break;

                case MSG_AUTO_LIGHT_ENABLE:
                    Log.i(TAG, "handleMessage() MSG_AUTO_LIGHT_ENABLE");
                    saveAutoLightState(true);
                    startLightSensorService();
                    break;

                case MSG_AUTO_LIGHT_DISABLE:
                    Log.i(TAG, "handleMessage() MSG_AUTO_LIGHT_DISABLE");
                    saveAutoLightState(false);
                    stopLightSensorService();
                    break;

                case MSG_LEAVE_CAR:
                    Log.i(TAG, "handleMessage() MSG_LEAVE_CAR");
                    MainActivity.sendLeaveCarCommand();
                    break;

                case MSG_WASH_MODE:
                    Log.i(TAG, "handleMessage() MSG_WASH_MODE");
                    MainActivity.sendWashModeCommand();
                    break;

                case MSG_FLOATING_BACK:
                    Log.i(TAG, "handleMessage() MSG_FLOATING_BACK arg1=" + msg.arg1);
                    setFloatingBackEnabled(msg.arg1 == 1);
                    break;

                case MSG_FLOATING_BACK_SIDE:
                    Log.i(TAG, "handleMessage() MSG_FLOATING_BACK_SIDE arg1=" + msg.arg1);
                    setFloatingBackSide(msg.arg1);
                    break;

                case MSG_GRANT_INSTALL: {
                    String pkg = (msg.getData() != null) ? msg.getData().getString("pkg") : null;
                    Log.i(TAG, "handleMessage() MSG_GRANT_INSTALL pkg=" + pkg + " uid=" + msg.arg1);
                    grantInstallPermission(pkg, msg.arg1);
                    break;
                }

                case MSG_CLOSE_ALL:
                    Log.i(TAG, "handleMessage() MSG_CLOSE_ALL");
                    closeAllApps();
                    break;

                case MSG_APPLY_PEDESTRIAN:
                    Log.i(TAG, "handleMessage() MSG_APPLY_PEDESTRIAN arg1=" + msg.arg1);
                    MainActivity.sendPedestrianSoundCommand(msg.arg1 == 1);
                    break;

                case MSG_REBOOT:
                    Log.i(TAG, "handleMessage() MSG_REBOOT");
                    rebootSystem();
                    break;

                case MSG_SET_THEME:
                    Log.i(TAG, "handleMessage() MSG_SET_THEME arg1=" + msg.arg1);
                    applyTheme(msg.arg1);
                    break;

                case MSG_SPLIT_LAUNCH_VD: {
                    if (!BuildConfig.IS_FULL) { Log.i(TAG, "MSG_SPLIT_LAUNCH_VD игнор (light-сборка)"); break; }
                    android.os.Bundle d = msg.getData();
                    String left = (d != null) ? d.getString("left") : null;
                    String right = (d != null) ? d.getString("right") : null;
                    int lDpi = (d != null) ? d.getInt("leftDpi", 0) : 0;
                    int rDpi = (d != null) ? d.getInt("rightDpi", 0) : 0;
                    // Изменяемая пропорция: разрешение тянуть делитель, стартовая доля левого окна и
                    // индекс пресета (по нему хост вернёт новое значение в RestoreMode).
                    boolean resizable = (d != null) && d.getBoolean("resizable", false);
                    float split = (d != null) ? d.getFloat("split", 0f) : 0f;
                    int presetIdx = (d != null) ? d.getInt("presetIdx", -1) : -1;
                    Log.i(TAG, "handleMessage() MSG_SPLIT_LAUNCH_VD left=" + left + " right=" + right
                            + " ratio=" + msg.arg1 + " lDpi=" + lDpi + " rDpi=" + rDpi
                            + " resizable=" + resizable + " split=" + split + " preset=" + presetIdx);
                    launchVirtualSplit(left, right, msg.arg1, lDpi, rDpi, resizable, split, presetIdx);
                    break;
                }

                case MSG_LOGGING_ENABLE:
                    Log.i(TAG, "handleMessage() MSG_LOGGING_ENABLE arg1=" + msg.arg1);
                    setLoggingEnabled(msg.arg1 == 1);
                    break;

                case MSG_LOGGING_SHARE:
                    Log.i(TAG, "handleMessage() MSG_LOGGING_SHARE");
                    shareLogFile();
                    break;

                default:
                    Log.i(TAG, "handleMessage() default");
                    super.handleMessage(msg);
            }
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
    }

    /**
     * Вкл/выкл плавающую кнопку «Назад»: добавляем/убираем {@link BackButtonService} в
     * ENABLED_ACCESSIBILITY_SERVICES (нужен WRITE_SECURE_SETTINGS — есть у Native как priv-app).
     * Сам сервис по подключению рисует оверлей, по отключению — убирает.
     */
    private void setFloatingBackEnabled(boolean enable) {
        prefs().edit().putBoolean("floatingBack", enable).apply();
        writeFloatingBackA11y(enable);
    }

    /** Пишет наличие/отсутствие BackButtonService в ENABLED_ACCESSIBILITY_SERVICES (без правки prefs). */
    private void writeFloatingBackA11y(boolean present) {
        try {
            android.content.ContentResolver cr = getContentResolver();
            String current = android.provider.Settings.Secure.getString(
                    cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            if (current != null) {
                for (String s : current.split(":")) if (!s.isEmpty()) set.add(s);
            }
            if (present) set.add(FLOATING_BACK_A11Y); else set.remove(FLOATING_BACK_A11Y);

            StringBuilder sb = new StringBuilder();
            for (String s : set) {
                if (sb.length() > 0) sb.append(":");
                sb.append(s);
            }
            android.provider.Settings.Secure.putString(
                    cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, sb.toString());
            android.provider.Settings.Secure.putInt(
                    cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, set.isEmpty() ? 0 : 1);
            Log.i(TAG, "floating back a11y " + (present ? "ON" : "OFF") + "; enabled_a11y='" + sb + "'");
        } catch (Exception e) {
            Log.e(TAG, "writeFloatingBackA11y failed: " + e.getMessage());
        }
    }

    /** Сторона кнопки: 0 лево, 1 верх, 2 право. При смене оси (верх↔бок) сбрасываем смещение на центр. */
    private void setFloatingBackSide(int side) {
        int old = prefs().getInt("floatingBackSide", BackButtonService.SIDE_LEFT);
        boolean axisChanged = (old == BackButtonService.SIDE_TOP) != (side == BackButtonService.SIDE_TOP);
        SharedPreferences.Editor ed = prefs().edit().putInt("floatingBackSide", side);
        if (axisChanged) ed.putInt("floatingBackOffset", -1);
        ed.apply();
        BackButtonService.updatePosition();
    }

    /**
     * На пробуждении/загрузке гарантируем плавающую кнопку «Назад», если она включена.
     * Просто перезапись secure-настройки тем же значением НЕ перебиндивает сервис и не
     * пересоздаёт оверлей (окно снимается при засыпании) — поэтому:
     *  1) если сервис доступности жив → просим его пере-показать оверлей ({@code reshow});
     *  2) если не жив → форсим переустановку a11y (off→on), чтобы система его подняла.
     */
    private void reassertFloatingBack() {
        if (!prefs().getBoolean("floatingBack", false)) return;
        if (BackButtonService.reshow()) {
            Log.i(TAG, "reassertFloatingBack: сервис жив → оверлей пере-показан");
            return;
        }
        Log.i(TAG, "reassertFloatingBack: сервис не подключён → форс-переустановка a11y");
        writeFloatingBackA11y(false);
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> writeFloatingBackA11y(true), 800);
    }

    // Автозапуск RestoreMode: дебаунс, чтобы серия wake-состояний подряд не открывала окно повторно.
    // ВАЖНО: инициализация «давно», иначе near-boot (elapsedRealtime мал) первый запуск блокируется дебаунсом.
    private long lastAutoLaunch = Long.MIN_VALUE / 2;
    private static final long AUTO_LAUNCH_DEBOUNCE_MS = 60_000L;

    /** На пробуждении: если включён «Автозапуск VoyahTune», открываем RestoreMode поверх. */
    /** Читает «Автозапуск VoyahTune» из ContentProvider RestoreMode (единый источник, колонка 16). */
    private boolean readAutoLaunchFromProvider() {
        try {
            android.database.Cursor c = getContentResolver().query(
                    android.net.Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                    null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst() && c.getColumnCount() > 16) return c.getInt(16) == 1;
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "readAutoLaunchFromProvider: " + e.getMessage());
        }
        return false;
    }

    private void maybeAutoLaunchRestoreMode() {
        if (!readAutoLaunchFromProvider()) {
            Log.i(TAG, "maybeAutoLaunchRestoreMode: autoLaunch=false — пропуск");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastAutoLaunch < AUTO_LAUNCH_DEBOUNCE_MS) {
            Log.i(TAG, "maybeAutoLaunchRestoreMode: пропуск (дебаунс)");
            return;
        }
        lastAutoLaunch = now;
        try {
            Intent i = new Intent();
            i.setClassName(RESTOREMODE_PKG, RESTOREMODE_MAIN);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // Прямой запуск (Native — priv-app с START_ACTIVITIES_FROM_BACKGROUND).
            try { startActivity(i); } catch (Exception ignored) {}

            // + fullScreenIntent: на кастомной мультидисплейной ROM прямой запуск из фона не
            // выводится на передний план (лаунчер держит home). fullScreenIntent система
            // показывает принудительно (как входящий звонок), обходя приоритет home.
            String CH = "autolaunch_channel";
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CH, "Автозапуск VoyahTune", NotificationManager.IMPORTANCE_HIGH));
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, i,
                        android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                Notification n = new NotificationCompat.Builder(this, CH)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle("VoyahTune")
                        .setContentText("Открытие приложения")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_CALL)
                        .setFullScreenIntent(pi, true)
                        .setAutoCancel(true)
                        .setOngoing(false)
                        .build();
                nm.notify(4242, n);
                // Через 3с снимаем нотификацию — она нужна только как триггер fullScreenIntent.
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> { try { nm.cancel(4242); } catch (Exception ignored) {} }, 3000);
            }
            Log.i(TAG, "maybeAutoLaunchRestoreMode: RestoreMode запущен (+fullScreenIntent)");
        } catch (Exception e) {
            Log.e(TAG, "maybeAutoLaunchRestoreMode failed: " + e.getMessage());
        }
    }

    /**
     * Выдаёт приложению право «установка из неизвестных источников» — app-op
     * REQUEST_INSTALL_PACKAGES (код 66) = MODE_ALLOWED. Требует MANAGE_APP_OPS_MODES
     * (signature|privileged) — есть у Native как priv-app. setMode вызываем рефлексией
     * (метод @SystemApi/@hide; priv-app освобождён от hidden-api ограничений).
     */
    private void grantInstallPermission(String pkg, int uidHint) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            int uid = uidHint;
            if (uid <= 0) uid = getPackageManager().getPackageUid(pkg, 0);
            android.app.AppOpsManager aom =
                    (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            java.lang.reflect.Method setMode = android.app.AppOpsManager.class.getMethod(
                    "setMode", int.class, int.class, String.class, int.class);
            // OP_REQUEST_INSTALL_PACKAGES = 66, MODE_ALLOWED = 0
            setMode.invoke(aom, 66, uid, pkg, android.app.AppOpsManager.MODE_ALLOWED);
            Log.i(TAG, "grantInstall: " + pkg + " uid=" + uid + " -> ALLOWED");
        } catch (Exception e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException
                    && e.getCause() != null) ? e.getCause() : e;
            Log.e(TAG, "grantInstall failed for " + pkg + ": " + cause);
        }
    }

    /**
     * Закрывает все сторонние приложения через {@link android.app.ActivityManager#forceStopPackage}
     * (рефлексия; нужен FORCE_STOP_PACKAGES — есть у Native как priv-app). Force-stop сбрасывает
     * сохранённое состояние → приложения стартуют с нуля. Трогаем ТОЛЬКО не-системные пакеты и
     * исключаем свои/лаунчер/вендорские, чтобы не уронить оболочку головы.
     */
    private void closeAllApps() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            android.content.pm.PackageManager pm = getPackageManager();

            String home = null;
            android.content.pm.ResolveInfo hr = pm.resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            if (hr != null && hr.activityInfo != null) home = hr.activityInfo.packageName;

            java.lang.reflect.Method forceStop =
                    android.app.ActivityManager.class.getMethod("forceStopPackage", String.class);

            int count = 0;
            for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                String pkg = ai.packageName;
                if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue; // только сторонние
                if (pkg.equals("ru.big.town.restoremode") || pkg.equals("ru.big.town.anative")) continue;
                if (pkg.equals(home)) continue;
                if (pkg.startsWith("com.qinggan") || pkg.startsWith("com.android.car")) continue;
                try {
                    forceStop.invoke(am, pkg);
                    count++;
                } catch (Exception e) {
                    Throwable c = (e instanceof java.lang.reflect.InvocationTargetException
                            && e.getCause() != null) ? e.getCause() : e;
                    Log.e(TAG, "forceStop failed " + pkg + ": " + c);
                }
            }
            Log.i(TAG, "closeAllApps: остановлено " + count + " сторонних приложений");
        } catch (Exception e) {
            Log.e(TAG, "closeAllApps failed: " + e.getMessage());
        }
    }

    /**
     * Сплит на VirtualDisplay ({@link SplitHostActivity}): каждое приложение на своём VD с
     * заданным DPI, живой ресайз пропорций, свап по двойному тапу. Единственный движок сплита.
     * freeform-настройки нужны, чтобы приложения на VD были resizable.
     */
    private void launchVirtualSplit(String leftPkg, String rightPkg, int ratio, int leftDpi, int rightDpi) {
        launchVirtualSplit(leftPkg, rightPkg, ratio, leftDpi, rightDpi, false, 0f, -1);
    }

    /**
     * ВНИМАНИЕ: пустой rightPkg — это ШТАТНЫЙ одиночный режим (ярлык приложения с главного экрана
     * VoyahTune), а не ошибка. Именно поэтому запуск идёт здесь, а не через
     * SplitHostActivity.launchSplit — тот пустой правый пакет отвергает и ярлыки молча не открывались.
     */
    private void launchVirtualSplit(String leftPkg, String rightPkg, int ratio, int leftDpi, int rightDpi,
                                    boolean resizable, float split, int presetIdx) {
        if (leftPkg == null || leftPkg.isEmpty()) return; // rightPkg пуст = одиночный полноэкранный режим
        if (rightPkg == null) rightPkg = "";
        try {
            android.provider.Settings.Global.putInt(getContentResolver(), "enable_freeform_support", 1);
            android.provider.Settings.Global.putInt(getContentResolver(), "force_resizable_activities", 1);
        } catch (Exception e) {
            Log.w(TAG, "freeform settings: " + e.getMessage());
        }
        try {
            Intent i = new Intent(this, SplitHostActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra(SplitHostActivity.EXTRA_LEFT, leftPkg);
            i.putExtra(SplitHostActivity.EXTRA_RIGHT, rightPkg);
            i.putExtra(SplitHostActivity.EXTRA_RATIO, ratio);
            i.putExtra(SplitHostActivity.EXTRA_LEFT_DPI, leftDpi);
            i.putExtra(SplitHostActivity.EXTRA_RIGHT_DPI, rightDpi);
            i.putExtra(SplitHostActivity.EXTRA_RESIZABLE, resizable);
            i.putExtra(SplitHostActivity.EXTRA_SPLIT, split);
            i.putExtra(SplitHostActivity.EXTRA_PRESET_IDX, presetIdx);
            startActivity(i);
            Log.i(TAG, "launchVirtualSplit host started");
        } catch (Exception e) {
            Log.e(TAG, "launchVirtualSplit failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Логирование в файл (экран «Логирование» в RestoreMode)
    // -------------------------------------------------------------------------

    /** Вкл/выкл захват всего вывода Native в файл (persist в NativePrefs). */
    private void setLoggingEnabled(boolean enable) {
        prefs().edit().putBoolean("logging", enable).apply();
        if (enable) NativeLog.get().start(getApplicationContext());
        else NativeLog.get().stopAndDelete(getApplicationContext()); // выкл → удаляем файл
    }

    /** На старте сервиса восстанавливаем захват логов, если был включён. */
    private void restoreLoggingState() {
        if (prefs().getBoolean("logging", false)) {
            NativeLog.get().start(getApplicationContext());
        }
    }

    /** «Выгрузить логи»: share лог-файла через FileProvider (LocalSend и любое приложение). */
    private void shareLogFile() {
        try {
            java.io.File f = NativeLog.get().logFile(getApplicationContext());
            if (f == null || !f.exists()) {
                Log.w(TAG, "shareLogFile: файла нет");
                return;
            }
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "ru.big.town.anative.fileprovider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            // ClipData — чтобы grant применился и к превью чузера, и к выбранному приложению
            send.setClipData(android.content.ClipData.newRawUri(f.getName(), uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "Выгрузить логи");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
            Log.i(TAG, "shareLogFile: share " + uri);
        } catch (Exception e) {
            Log.e(TAG, "shareLogFile failed: " + e.getMessage());
        }
    }

    /** Запрос снимка логов от UI → отдаём последние строки + состояние. */
    private final android.content.BroadcastReceiver logRequestReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (ACTION_LOGGING_SET.equals(a)) {
                setLoggingEnabled(intent.getBooleanExtra("on", false));
                return;
            }
            if (ACTION_LOGGING_SHARE.equals(a)) {
                shareLogFile();
                return;
            }
            // ACTION_REQUEST_LOG → снимок ленты
            Intent out = new Intent(ACTION_LOG_UPDATE);
            out.putExtra("log", NativeLog.get().snapshot());
            out.putExtra("running", NativeLog.get().isRunning());
            out.putExtra("path", NativeLog.get().logFile(getApplicationContext()).getAbsolutePath());
            sendBroadcast(out);
        }
    };


    /** Перезагрузка системы (головы). Требует REBOOT (signature|privileged) — выдаётся priv-app. */
    private void rebootSystem() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                Log.i(TAG, "rebootSystem: PowerManager.reboot()");
                pm.reboot(null);
            } else {
                Log.e(TAG, "rebootSystem: PowerManager == null");
            }
        } catch (Exception e) {
            Log.e(TAG, "rebootSystem failed: " + e.getMessage());
        }
    }

    /**
     * Переопределение темы системы (и приложений, следующих системной DayNight-теме).
     * mode: 0=Авто, 1=Светлая (NIGHT_NO), 2=Тёмная (NIGHT_YES) — совпадает с {@code UiModeManager.MODE_NIGHT_*}
     * и значениями {@code Settings.Secure.ui_night_mode}. Пишем secure-настройку (WRITE_SECURE_SETTINGS,
     * переживёт ребут) И зовём {@code UiModeManager.setNightMode} для мгновенного применения (в try — на
     * части прошивок нужен signature-пермишен MODIFY_DAY_NIGHT_MODE; тогда применится по secure-настройке).
     */
    private void applyTheme(int mode) {
        if (mode < 0 || mode > 3) mode = 0;
        try {
            android.provider.Settings.Secure.putInt(getContentResolver(), "ui_night_mode", mode);
        } catch (Exception e) {
            Log.w(TAG, "applyTheme secure ui_night_mode: " + e.getMessage());
        }
        try {
            android.app.UiModeManager ui = (android.app.UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
            if (ui != null) ui.setNightMode(mode);
        } catch (Exception e) {
            Log.w(TAG, "applyTheme setNightMode (нет MODIFY_DAY_NIGHT_MODE?): " + e.getMessage());
        }
        Log.i(TAG, "applyTheme mode=" + mode);
    }

    private void saveAutoLightState(boolean enabled) {
        prefs().edit().putBoolean("autoLight", enabled).apply();
        Log.i(TAG, "saveAutoLightState: " + enabled);
    }

    private void restoreAutoLightState() {
        boolean autoLight = prefs().getBoolean("autoLight", false);
        Log.i(TAG, "restoreAutoLightState: autoLight=" + autoLight);
        if (autoLight) {
            startLightSensorService();
        }
    }

    /**
     * На power on: если включена опция «Сервисный режим дворников в холодную погоду»,
     * отправляем {@link WiperColdService} команду reset (вернуть дворники в обычный режим).
     * Сам сервис решит, слать ли toggle (только если считает режим активным).
     */
    private void resetWiperColdOnPowerOn() {
        // Шлём reset, если опция включена ЛИБО персист говорит, что дворники в сервисном
        // режиме (wiperServiceActive). Второе условие важно: если опцию выключили, пока
        // дворники подняты, их всё равно надо вернуть на power on — безусловно, независимо
        // от температуры (решение о самой отправке принимает WiperColdService по флагу).
        boolean enabled = prefs().getBoolean("wiperCold", false);
        boolean active  = prefs().getBoolean("wiperServiceActive", false);
        if (!enabled && !active) return;
        Intent intent = new Intent(this, WiperColdService.class);
        intent.setAction(WiperColdService.ACTION_POWER_ON_RESET);
        startForegroundService(intent);
        Log.i(TAG, "resetWiperColdOnPowerOn: sent POWER_ON_RESET (enabled=" + enabled
                + " active=" + active + ")");
    }

    /** Форвардит STATE_ON в TripStatsService (граница новой поездки). */
    private void forwardPowerOnToTripStats() {
        Intent intent = new Intent(this, TripStatsService.class);
        intent.setAction(TripStatsService.ACTION_POWER_ON);
        startForegroundService(intent);
    }

    /** Стартует TripStatsService (учёт поездок работает всегда). */
    private void startTripStatsService() {
        Intent intent = new Intent(this, TripStatsService.class);
        startForegroundService(intent);
    }

    /** Стартует BatteryHeatService (статус ВВБ для виджета + авто-прогрев по температуре). */
    private void startBatteryHeatService() {
        Intent intent = new Intent(this, BatteryHeatService.class);
        startForegroundService(intent);
    }

    /** Стартует NowPlayingService (ридер метаданных активной медиа-сессии для наших поверхностей). */
    private void startNowPlayingService() {
        Intent intent = new Intent(this, NowPlayingService.class);
        startForegroundService(intent);
    }

    /** Восстанавливает WiperColdService (реактор двери) на старте, если включён хотя бы один его
     *  потребитель: сервисный режим дворников ({@code wiperCold}) или пауза музыки при открытии
     *  двери ({@code pauseMediaOnDoor}). */
    private void restoreWiperColdState() {
        boolean wiper       = prefs().getBoolean("wiperCold", false);
        boolean pauseMedia  = prefs().getBoolean("pauseMediaOnDoor", false);
        Log.i(TAG, "restoreWiperColdState: wiperCold=" + wiper + " pauseMediaOnDoor=" + pauseMedia);
        if (wiper || pauseMedia) {
            Intent intent = new Intent(this, WiperColdService.class);
            startForegroundService(intent);
        }
    }

    private void startLightSensorService() {
        Intent intent = new Intent(this, LightSensorService.class);
        startForegroundService(intent);
        Log.i(TAG, "LightSensorService started");
    }

    private void stopLightSensorService() {
        Intent intent = new Intent(this, LightSensorService.class);
        stopService(intent);
        Log.i(TAG, "LightSensorService stopped");
    }

    //private boolean isWorking = false;
    private SetModesReceiverDynamic setModesReceiverDynamic;
    private boolean receiverRegistered = false;
    private final String CHANNEL_ID = "screen_monitor_channel";
    private Car mCar;
    private CarPropertyManager mCarPropertyManager;

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate()");
        super.onCreate();
        initializeCarPowerManager();
        setModesReceiverDynamic = new SetModesReceiverDynamic();
        // Приёмник запроса снимка логов + восстановление захвата регистрируем в onCreate
        // (срабатывает и при простом bind, не только при startService).
        try {
            IntentFilter logFilter = new IntentFilter(ACTION_REQUEST_LOG);
            logFilter.addAction(ACTION_LOGGING_SET);
            logFilter.addAction(ACTION_LOGGING_SHARE);
            registerReceiver(logRequestReceiver, logFilter, RECEIVER_EXPORTED);
        } catch (Exception e) {
            Log.w(TAG, "register logRequestReceiver: " + e.getMessage());
        }
        restoreLoggingState();
        Log.i(TAG, "onCreated");
    }



    private final CarPowerManager.CarPowerStateListener mPowerStateListener =
            new CarPowerManager.CarPowerStateListener() {
                @Override
                public void onStateChanged(int state) {
                    Log.i(TAG, "Power state changed: " + state + " (" + powerStateName(state) + ")");
                    // Раньше применялось ТОЛЬКО на STATE_ON(6). Но при выходе из сна железо часто
                    // рапортует WAIT_FOR_VHAL(1)/SUSPEND_EXIT(3)/SHUTDOWN_CANCELLED(8), а ON может не
                    // прийти — из-за этого настройки не применялись до ручного «Применить».
                    // Теперь реагируем на любое «пробуждение к активному состоянию» (с дебаунсом в
                    // ApplyEngine, чтобы несколько состояний подряд не привели к дублю).
                    if (isWakeState(state)) {
                        ApplyEngine.scheduleApply("power state " + powerStateName(state));
                        // Сервисный режим дворников: на пробуждении возвращаем дворники в обычный режим
                        resetWiperColdOnPowerOn();
                        // Статистика поездок: пробуждение — граница новой поездки
                        forwardPowerOnToTripStats();
                        // Плавающая кнопка «Назад»: подстрахуемся, что она включена после пробуждения
                        reassertFloatingBack();
                        // Автозапуск VoyahTune: если включён — открыть RestoreMode
                        maybeAutoLaunchRestoreMode();
                    } else {
                        // Засыпание/выключение → следующее пробуждение должно СНАЧАЛА восстановить
                        // сохранённый режим, а не подхватить дефолт машины. Заранее глушим внешний синк
                        // режима (страховка к сбросу в scheduleApply — на случай «тёплого» процесса, где
                        // CAN-эхо может прийти раньше wake-триггера). См. ApplyEngine.restoreDoneThisCycle.
                        if (isSleepOrShutdownState(state)) ApplyEngine.resetRestoreGate();
                        Log.i(TAG, "onStateChanged() ignored state: " + state);
                    }
                }
            };

    /** Состояния питания, трактуемые как «пробуждение → нужно применить настройки». */
    private static boolean isWakeState(int state) {
        return state == CarPowerManager.CarPowerStateListener.ON               // 6
                || state == CarPowerManager.CarPowerStateListener.SUSPEND_EXIT // 3
                || state == CarPowerManager.CarPowerStateListener.WAIT_FOR_VHAL // 1
                || state == CarPowerManager.CarPowerStateListener.SHUTDOWN_CANCELLED; // 8
    }

    /** Состояния «уход в сон / выключение» — момент сбросить req3-гейт внешнего синка режима. */
    private static boolean isSleepOrShutdownState(int state) {
        return state == CarPowerManager.CarPowerStateListener.SUSPEND_ENTER     // 2
                || state == CarPowerManager.CarPowerStateListener.SHUTDOWN_ENTER    // 5
                || state == CarPowerManager.CarPowerStateListener.SHUTDOWN_PREPARE; // 7
    }

    private static String powerStateName(int state) {
        switch (state) {
            case CarPowerManager.CarPowerStateListener.WAIT_FOR_VHAL:      return "WAIT_FOR_VHAL";
            case CarPowerManager.CarPowerStateListener.SUSPEND_ENTER:      return "SUSPEND_ENTER";
            case CarPowerManager.CarPowerStateListener.SUSPEND_EXIT:       return "SUSPEND_EXIT";
            case CarPowerManager.CarPowerStateListener.SHUTDOWN_ENTER:     return "SHUTDOWN_ENTER";
            case CarPowerManager.CarPowerStateListener.ON:                 return "ON";
            case CarPowerManager.CarPowerStateListener.SHUTDOWN_PREPARE:   return "SHUTDOWN_PREPARE";
            case CarPowerManager.CarPowerStateListener.SHUTDOWN_CANCELLED: return "SHUTDOWN_CANCELLED";
            default:                                                       return "STATE_" + state;
        }
    }
//    private void handleSuspendEnter() {
//        Log.i(TAG, "SUSPEND_ENTER received - System is entering suspend-to-RAM");
//
//        // Perform cleanup operations before suspend
//        // Note: You have limited time (default 5 seconds) to complete tasks :cite[3]
//        cleanupBeforeSuspend();
//
//        Log.i(TAG, "Ready for suspend");
//    }
//    private void cleanupBeforeSuspend() {
//        // Add your cleanup logic here:
//        // - Save application state
//        // - Close network connections
//        // - Release resources
//        // - Stop ongoing operations
//
//        try {
//            // Example cleanup operations
//            Log.i(TAG, "Performing pre-suspend cleanup...");
//            Thread.sleep(500); // Simulate cleanup work
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//        }
//    }

    private void initializeCarPowerManager() {
        try {
            // Подключаемся к CarService через lifecycle-колбэк: если CarService перезапустится
            // (обычное дело на этом OEM), мы заново получим CarPowerManager и перерегистрируем
            // слушатель питания. Раньше слушатель регистрировался один раз и после рестарта
            // CarService «тихо умирал» — пробуждения переставали ловиться.
            mCar = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                    (car, ready) -> {
                        Log.i(TAG, "Car lifecycle: ready=" + ready);
                        if (ready) {
                            try {
                                GlobalVars.mCarPowerManager =
                                        (CarPowerManager) car.getCarManager(Car.POWER_SERVICE);
                                if (GlobalVars.mCarPowerManager != null) {
                                    registerPowerStateListener();
                                } else {
                                    Log.e(TAG, "Failed to get CarPowerManager");
                                }
                            } catch (Exception e) {
                                GlobalVars.mCarPowerManager = null;
                                Log.e(TAG, "getCarManager(POWER_SERVICE) failed", e);
                            }
                        } else {
                            // CarService отвалился — менеджер невалиден. Отработает fallback
                            // (SCREEN_ON/GARAGE_MODE_OFF), а на реконнекте мы перерегистрируемся.
                            GlobalVars.mCarPowerManager = null;
                        }
                    });
        } catch (Throwable e) {
            GlobalVars.mCarPowerManager = null;
            Log.e(TAG, "Error initializing CarPowerManager", e);
        }
    }

    private void registerPowerStateListener() {
        try {
            // setListener в Android 11 кидает IllegalStateException, если слушатель уже
            // установлен ("Listener must be cleared first") — защищаемся clearListener'ом
            // на случай повторного ready-колбэка без дисконнекта между ними.
            try {
                GlobalVars.mCarPowerManager.clearListener();
            } catch (Throwable ignored) {
                // слушатель не был установлен — это нормально
            }
            GlobalVars.mCarPowerManager.setListener(mPowerStateListener);
            Log.i(TAG, "CarPowerStateListener registered");
        } catch (NoSuchMethodError e) {
            Log.w(TAG, "setListener(Listener) not available on this platform, skipping");
        } catch (Throwable e) {
            Log.e(TAG, "setListener failed: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //handler.post(checkScreenState);

        //String action = "";
        //if (intent != null && intent.getAction() != null) action = intent.getAction();

        //Log.i(TAG, "onStartCommand() Intent: " + action);
        //if (!isWorking) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Monitor")
                .setContentText("Monitoring screen state")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();

        createNotificationChannel();
        startForeground(1, notification);

        // Fallback-подписку на пробуждение через броадкасты держим ВСЕГДА (belt-and-suspenders),
        // а не только когда mCarPowerManager==null: слушатель питания может «протухнуть» при
        // рестарте CarService, и тогда единственным триггером остаётся SCREEN_ON/GARAGE_MODE_OFF.
        // Дубли с power-listener гасит дебаунс в ApplyEngine.
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.KEYCODE_SWC_USER_DEFINE");
            filter.addAction("com.android.server.jobscheduler.GARAGE_MODE_OFF");
            filter.addAction("android.intent.action.SCREEN_ON");
            getApplicationContext().registerReceiver(setModesReceiverDynamic, filter, RECEIVER_EXPORTED);
            receiverRegistered = true;
        }

        // Первый вызов после старта сервиса (в т.ч. рестарт по START_STICKY после kill во сне) —
        // применяем настройки. ApplyEngine сам дождётся готовности провайдера/кэша.
        ApplyEngine.scheduleApply("service start");
        Log.i(TAG, "onStartCommand() first run!");

        // Восстанавливаем состояние автосвета
        restoreAutoLightState();
        // Восстанавливаем сервис «сервисного режима дворников»
        restoreWiperColdState();
        // Учёт поездок работает всегда
        startTripStatsService();
        // Статус ВВБ для виджета + авто-прогрев по уличной температуре
        startBatteryHeatService();
        // Ридер «сейчас играет» — стартуем с задержкой и в try, чтобы НИКАК не влиять на критичное
        // применение режима на старте (оно уже запланировано выше). Изолируем от старта.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try { startNowPlayingService(); }
            catch (Exception e) { Log.w(TAG, "startNowPlayingService: " + e.getMessage()); }
        }, 6000);
        // Плавающая кнопка «Назад»: восстановить после загрузки/рестарта сервиса
        // (с задержкой — даём системе поднять a11y-подсистему).
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::reassertFloatingBack, 3000);
        // Автозапуск VoyahTune на загрузке (если включён) — с задержкой, чтобы оболочка поднялась.
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::maybeAutoLaunchRestoreMode, 5000);
        //if(action.equals("ru.big.town.anative.APPLY_DRIVE_MODES")){
        //  Log.i(TAG, "onStartCommand() Intent is ru.big.town.anative.APPLY_DRIVE_MODES!");
        //LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES"));
        //}
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    final Messenger serviceMessenger = new Messenger(new IncomingHandler());

    @Override
    public IBinder onBind(Intent intent) {
        return serviceMessenger.getBinder();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy()");
        if (receiverRegistered) {
            try {
                getApplicationContext().unregisterReceiver(setModesReceiverDynamic);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "unregisterReceiver: not registered");
            }
            receiverRegistered = false;
        }
        try {
            unregisterReceiver(logRequestReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        // Clean up resources
        if (GlobalVars.mCarPowerManager != null) {
            try {
                GlobalVars.mCarPowerManager.clearListener();
                Log.i(TAG, "CarPowerStateListener unregistered");
            } catch (NoSuchMethodError e) {
                Log.w(TAG, "clearListener() not available on this platform");
            } catch (Exception e) {
                Log.w(TAG, "clearListener() failed: " + e.getMessage());
            }
        }

        if (mCar != null) {
            mCar.disconnect();
        }
        super.onDestroy();
    }

    /** Уведомить клиента о завершении цикла «Применить» (разблокировка кнопки). */
    static void notifyApplyDone(Messenger client) {
        if (client == null) return;
        try {
            client.send(Message.obtain(null, MSG_RESULT));
        } catch (RemoteException e) {
            Log.w(TAG, "notifyApplyDone failed: " + e.getMessage());
        }
    }
    /**
     * Команда «звёздочки» на руле: разовая отправка пресета 1/2. Выполняется на
     * последовательном потоке {@link ApplyEngine}, чтобы не отправлять в CAN одновременно
     * с циклом применения (раньше взаимное исключение обеспечивал флаг GlobalVars.running,
     * общий с worker'ом применения — сохраняем ту же гарантию, но без сырых потоков).
     */
    static public void worker(int repeat, int pause, int mode, int msg_arg1) {
        Log.i(TAG, " Call worker" +
                String.format(" repeat: %d, pause: %d, mode %d, msg_arg1: %d",
                        repeat, pause, mode, msg_arg1));
        if (GlobalVars.SAVE_CONTEXT == null || mode != MSG_APPLY_DRIVE_MODES_STAR_BUTTON) return;

        ApplyEngine.postExclusive("star button " + msg_arg1, () -> {
            MainActivity.loadModes(GlobalVars.SAVE_CONTEXT);
            Log.i(TAG, " Run customCommandStarButton");
            if (msg_arg1 == 1) MainActivity.setCanValues(1, MainActivity.getCustomCommandStarButton1(), "star button command 1");
            if (msg_arg1 == 2) MainActivity.setCanValues(1, MainActivity.getCustomCommandStarButton2(), "star button command 2");
        });
    }
}