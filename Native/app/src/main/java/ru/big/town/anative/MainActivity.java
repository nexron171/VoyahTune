package ru.big.town.anative;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import ru.big.town.anative.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    public static String driveMode = "INDIVIDUAL";
    private static String energy = "SREV";
    private static String recycle = "LOW";
    private static String customCommand = "";
    public static int customCommandCount = 1;
    public static String customCommandStarButton1 = "";
    public static String customCommandStarButton2 = "";

    private static boolean driveEnabled   = false;
    private static boolean recycleEnabled = false;
    private static boolean energyEnabled  = false;
    private static boolean disablePedestrianSound = false;

    //-------------- Вспомогательная шляпа не паримся ---------------------
    public static void printBytesArrayToLog(String TAG, byte[][] bytes) {
        for (byte[] b : bytes) {
            Log.i(TAG, printHexBinary(b));
        }
    }

    public static String printHexBinary(byte[] data) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : data) {
            hexString.append(String.format("%02X ", b));
        }
        return hexString.toString();
    }

    private static int hexToBin(char ch) {
        if ('0' <= ch && ch <= '9') {
            return ch - '0';
        }
        if ('A' <= ch && ch <= 'F') {
            return ch - 'A' + 10;
        }
        if ('a' <= ch && ch <= 'f') {
            return ch - 'a' + 10;
        }
        return -1;
    }

    public static byte[] parseHexBinary(String s) {
        s = s.replace(" ", "");
        final int len = s.length();

        // "111" is not a valid hex encoding.
        if (len % 2 != 0) {
            throw new IllegalArgumentException("hexBinary needs to be even-length: " + s);
        }

        byte[] out = new byte[len / 2];

        for (int i = 0; i < len; i += 2) {
            int h = hexToBin(s.charAt(i));
            int l = hexToBin(s.charAt(i + 1));
            if (h == -1 || l == -1) {
                throw new IllegalArgumentException("contains illegal character for hexBinary: " + s);
            }

            out[i / 2] = (byte) (h * 16 + l);
        }

        return out;
    }

    public static byte[][] arraysStr2arraysBytes(String[] cmds) {
        int indexCmd = 0;
        byte[][] cmdsBytes = new byte[cmds.length][10];
        for (String cmd : cmds) {
            cmdsBytes[indexCmd] = parseHexBinary(cmd);
            indexCmd++;
        }
        printBytesArrayToLog("$$$  MAIN arraysStr2arraysBytes $$$", cmdsBytes);
        return cmdsBytes;
    }
    //-------------- Вспомогательная шляпа не паримся ---------------------


    //------------- Загружаем нашу JNI ------------------------------------
    static {
        System.loadLibrary("anative");
    }

    public static native int cis_can_control_bytes(int cmdNum, byte[] bArr);


    private ActivityMainBinding binding;

    //------------- Метод получения команд CAN режимов энергии  -------------------------------------
    public static byte[][] getCustomCommand() {
        if (customCommand == null || customCommand.isEmpty()) return new byte[][]{{}};
        String[] cmds = customCommand.split("\n");
        return arraysStr2arraysBytes(cmds);
    }

    public static byte[][] getCustomCommandStarButton1() {
        if (customCommandStarButton1 == null || customCommandStarButton1.isEmpty()) return new byte[][]{{}};
        String[] cmds = customCommandStarButton1.split("\n");
        return arraysStr2arraysBytes(cmds);
    }
    public static byte[][] getCustomCommandStarButton2() {
        if (customCommandStarButton2 == null || customCommandStarButton2.isEmpty()) return new byte[][]{{}};
        String[] cmds = customCommandStarButton2.split("\n");
        return arraysStr2arraysBytes(cmds);
    }

    public static byte[][] getEnergyCanCommand(String mode) {
        Bundle energyMode = new Bundle();
        energyMode.putStringArray("Smart", new String[]{"68 08 03 00 00 f0 2c 14 18 00"});
        energyMode.putStringArray("EV", new String[]{"68 08 03 00 00 f0 2c 24 18 00"});
        energyMode.putStringArray("REV", new String[]{"68 08 03 00 00 f0 2c 34 18 00"});
        energyMode.putStringArray("SREV", new String[]{"68 08 03 00 00 f0 2c 44 18 00"});

        String[] cmds = energyMode.getStringArray(mode);
        return arraysStr2arraysBytes(cmds);
    }

    //------------- Метод получения команд CAN режимов вождения  ------------------------------------
    public static byte[][] getDriveModeCanCommand(String mode) {
        Bundle energyMode = new Bundle();
        energyMode.putStringArray("ECO", new String[]{
                "6c 08 00 3e 5a 01 88 01 00 20",
                "68 08 02 00 00 f0 2c 04 08 00"
        });
        energyMode.putStringArray("COMFORT", new String[]{
                "6c 08 00 3e 5a 01 88 01 00 40",
                "68 08 02 00 00 f0 2c 04 10 00"
        });
        energyMode.putStringArray("SPORT", new String[]{
                "6c 08 00 3e 5a 01 88 01 00 60",
                "68 08 03 00 00 f0 2c 04 18 00",
                "6f 08 0d 00 80 13 83 00 00 40"
        });
        energyMode.putStringArray("OUTING", new String[]{
                "6c 08 00 3e 5a 01 88 01 00 80",
                "68 08 02 00 00 f0 2c 04 18 00"
        });
        energyMode.putStringArray("SNOW", new String[]{
                "6c 08 40 3e 5a 01 88 01 00 c0",
                "68 08 02 00 00 f0 2c 04 10 00"
        });
        energyMode.putStringArray("INDIVIDUAL", new String[]{
                "6c 08 00 3e 5a 01 88 01 00 a0",
                "68 08 03 00 00 f0 2c 04 18 00"
        });


        String[] cmds = energyMode.getStringArray(mode);
        return arraysStr2arraysBytes(cmds);
    }

    //------------- Метод получения команд CAN режимов рекуперации  ---------------------------------
    public static byte[][] getRecEnergyCanCommand(String mode) {
        Bundle energyMode = new Bundle();
        energyMode.putStringArray("LOW", new String[]{
                "6c 08 40 3e 5a 01 88 01 00 00"
        });
        energyMode.putStringArray("MEDIUM", new String[]{
                "6c 08 60 3e 5a 01 88 01 00 00"
        });
        energyMode.putStringArray("HIGH", new String[]{
                "6c 08 80 3e 5a 01 88 01 00 00"
        });

        String[] cmds = energyMode.getStringArray(mode);
        return arraysStr2arraysBytes(cmds);
    }

    //------------- Метод получения команд CAN «Отключить звук для пешеходов»  ----------------------
    public static byte[][] getPedestrianSoundCanCommand(boolean disabled) {
        Log.i("$$$ MainActivity getPedestrianSoundCanCommand $$$",
                "pedestrian sound " + (disabled ? "DISABLED (mute)" : "ENABLED (default)"));
        if (disabled) {
            // Звук оповещения пешеходов ВЫКЛ
            return arraysStr2arraysBytes(new String[]{"6a 08 00 03 00 00 00 10 7c 00"});
        } else {
            // Звук оповещения пешеходов ВКЛ
            return arraysStr2arraysBytes(new String[]{"6a 08 00 03 00 00 00 20 7c 00"});
        }
    }

    //------------- Методы получения команд CAN управления фарами  ----------------------------------
    public static void setHeadlights(boolean on){
        Log.i("$$$ MainActivity setHeadlights $$$", "sending CAN: headlights " + (on ? "ON" : "OFF"));
        if (on) {
            setCanValues(1, arraysStr2arraysBytes(new String[]{
                    "1f 08 00 10 ff f8 00 04 02 7f",
                    "6f 08 08 00 80 11 43 04 00 40",
                    "76 08 04 00 00 00 00 00 00 00"
            }), "headlights: ON (low beam / manual)");
        } else {
            setCanValues(1, arraysStr2arraysBytes(new String[]{
                    "6f 08 08 00 80 11 43 00 00 40",
                    "1f 08 00 00 ff f9 00 04 02 7f",
                    "76 08 00 00 00 00 00 00 00 00"
            }), "headlights: OFF (auto)");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent serviceIntent = new Intent(this, SetModesService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        runCmds();
        //binding = ActivityMainBinding.inflate(getLayoutInflater());
        //setContentView(binding.getRoot());
        setContentView(R.layout.activity_main);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);


        // Example of a call to a native method
//        TextView tv = binding.sampleText;
//        tv.setText("----------");
    }

    public static boolean setCanValues(int cmdNum, byte[][] cmds) {
        return setCanValues(cmdNum, cmds, null);
    }

    public static boolean setCanValues(int cmdNum, byte[][] cmds, String label) {
        //printBytesArrayToLog("$$$ MAIN setCanValues $$$",cmds);
        // Отправка идёт через CanSender: в режиме отладки команды логируются (эмуляция) с меткой,
        // иначе уходят в шину через cis_can_control_bytes.
        return CanSender.send(cmdNum, cmds, label);
    }

    private static final String MODES_LOG = "$$$ MainActivity loadModes";

    /**
     * Загружает настройки режимов. Источник №1 — {@link ru.big.town.restoremode}-провайдер
     * (актуальные значения). Если он ещё не поднят (частый случай сразу после пробуждения),
     * подхватываем последний удачно прочитанный снимок из локального кэша (NativePrefs),
     * чтобы не применять пустые дефолты.
     *
     * @return 2 — прочитаны свежие данные из провайдера;
     *         1 — провайдер недоступен, но применён локальный кэш;
     *         0 — данных нет ни в провайдере, ни в кэше (применять нечего).
     */
    public static int loadModes(Context context) {
        return loadModes(context, true);
    }

    /**
     * @param allowCache false — принимать только свежие данные провайдера (кэш не трогаем);
     *                   используется ApplyEngine в первых попытках, чтобы дать провайдеру
     *                   шанс подняться, прежде чем соглашаться на устаревший снимок.
     */
    public static int loadModes(Context context, boolean allowCache) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(Uri
                            .parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                    null, null,
                    null, null);
            if (cursor != null && cursor.getCount() != 0 && cursor.getColumnCount() >= 5) {
                cursor.moveToFirst();
                driveMode = cursor.getString(0);
                energy = cursor.getString(1);
                recycle = cursor.getString(2);
                customCommand = cursor.getString(3);
                customCommandCount = cursor.getInt(4);
                // cols 6,7,8 — флаги включения (0=отключено, fallback=false)
                driveEnabled   = cursor.getColumnCount() > 6 && cursor.getInt(6) == 1;
                recycleEnabled = cursor.getColumnCount() > 7 && cursor.getInt(7) == 1;
                energyEnabled  = cursor.getColumnCount() > 8 && cursor.getInt(8) == 1;
                // col 11 — «Отключить звук для пешеходов» (1=отключить, fallback=false)
                disablePedestrianSound = cursor.getColumnCount() > 11 && cursor.getInt(11) == 1;
                // col 12 — «Режим отладки»: эмуляция CAN в логи вместо реальной отправки
                boolean debugMode = cursor.getColumnCount() > 12 && cursor.getInt(12) == 1;
                // col 13 — «Сервисный режим дворников в холодную погоду»: старт/стоп WiperColdService
                boolean wiperColdMode = cursor.getColumnCount() > 13 && cursor.getInt(13) == 1;
                // cols 14,15 — команды кнопок на руле (короткое/долгое нажатие)
                if (cursor.getColumnCount() > 14) customCommandStarButton1 = cursor.getString(14);
                if (cursor.getColumnCount() > 15) customCommandStarButton2 = cursor.getString(15);
                applyModeSideEffects(context, debugMode, wiperColdMode);
                saveModesCache(context, debugMode, wiperColdMode);
                Log.i(MODES_LOG, "FRESH: driveEnabled=" + driveEnabled
                        + " recycleEnabled=" + recycleEnabled + " energyEnabled=" + energyEnabled
                        + " disablePedestrianSound=" + disablePedestrianSound
                        + " debugMode=" + debugMode + " wiperColdMode=" + wiperColdMode);
                return 2;
            } else {
                Log.w(MODES_LOG, "Content provider not ready or missing columns"
                        + (cursor != null ? " cols=" + cursor.getColumnCount() : " cursor=null"));
            }
        } catch (Exception e) {
            Log.e(MODES_LOG, "Exception reading ContentProvider: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        // Провайдер не дал данных — пробуем локальный кэш (если разрешён)
        if (!allowCache) return 0;
        return loadModesFromCache(context) ? 1 : 0;
    }

    /** Совместимость: прежнее имя. */
    public static void initValueModes(Context context) {
        loadModes(context);
    }

    private static SharedPreferences nativePrefs(Context context) {
        return context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
    }

    /** Сохраняет успешно прочитанный снимок настроек в NativePrefs (кэш на случай «глухого» пробуждения). */
    private static void saveModesCache(Context context, boolean debugMode, boolean wiperColdMode) {
        nativePrefs(context).edit()
                .putString("cacheDriveMode", driveMode)
                .putString("cacheEnergy", energy)
                .putString("cacheRecycle", recycle)
                .putString("cacheCustomCommand", customCommand)
                .putInt("cacheCustomCommandCount", customCommandCount)
                .putBoolean("cacheDriveEnabled", driveEnabled)
                .putBoolean("cacheRecycleEnabled", recycleEnabled)
                .putBoolean("cacheEnergyEnabled", energyEnabled)
                .putBoolean("cacheDisablePedestrianSound", disablePedestrianSound)
                .putBoolean("cacheDebugMode", debugMode)
                .putBoolean("cacheWiperColdMode", wiperColdMode)
                .putBoolean("cacheValid", true)
                .apply();
    }

    /** Восстанавливает настройки из кэша NativePrefs. @return true, если кэш существовал. */
    private static boolean loadModesFromCache(Context context) {
        SharedPreferences p = nativePrefs(context);
        if (!p.getBoolean("cacheValid", false)) {
            Log.w(MODES_LOG, "No cached modes available");
            return false;
        }
        driveMode          = p.getString("cacheDriveMode", driveMode);
        energy             = p.getString("cacheEnergy", energy);
        recycle            = p.getString("cacheRecycle", recycle);
        customCommand      = p.getString("cacheCustomCommand", customCommand);
        customCommandCount = p.getInt("cacheCustomCommandCount", customCommandCount);
        driveEnabled       = p.getBoolean("cacheDriveEnabled", false);
        recycleEnabled     = p.getBoolean("cacheRecycleEnabled", false);
        energyEnabled      = p.getBoolean("cacheEnergyEnabled", false);
        disablePedestrianSound = p.getBoolean("cacheDisablePedestrianSound", false);
        boolean debugMode     = p.getBoolean("cacheDebugMode", false);
        boolean wiperColdMode = p.getBoolean("cacheWiperColdMode", false);
        applyModeSideEffects(context, debugMode, wiperColdMode);
        Log.i(MODES_LOG, "CACHE: driveEnabled=" + driveEnabled
                + " recycleEnabled=" + recycleEnabled + " energyEnabled=" + energyEnabled
                + " disablePedestrianSound=" + disablePedestrianSound
                + " debugMode=" + debugMode + " wiperColdMode=" + wiperColdMode);
        return true;
    }

    /** Побочные эффекты настроек, не зависящие от отправки CAN: режим отладки и сервис дворников. */
    private static void applyModeSideEffects(Context context, boolean debugMode, boolean wiperColdMode) {
        CanSender.setDebugMode(debugMode);
        applyWiperColdMode(context, wiperColdMode);
    }

    // Power Hold (leave car) — быстрая активация с главного экрана. Две CAN-команды активации.
    private static final String[] LEAVE_CAR_FRAMES = {
            "6c 08 00 3e 64 21 c7 00 00 00",
            "77 08 00 00 00 00 00 1f 00 00",
    };
    public static void sendLeaveCarCommand() {
        setCanValues(1, arraysStr2arraysBytes(LEAVE_CAR_FRAMES), "leave car (power hold)");
    }

    // Режим мойки — машина засыпает и не реагирует на открытие дверей. Последовательность CAN-команд.
    private static final String[] WASH_MODE_FRAMES = {
            "1f 08 00 00 ff f8 00 01 02 ff",
            "6f 08 04 00 80 11 43 01 00 40",
            "76 08 01 00 00 00 00 00 00 00",
            "6f 08 04 00 40 11 43 01 00 40",
            "1f 08 00 00 ff f8 00 01 02 7f",
            "73 08 00 00 f0 ff 3f ff ff 07",
            "6f 08 04 00 80 11 43 00 00 40",
            "76 08 00 00 00 00 00 00 00 00",
    };
    public static void sendWashModeCommand() {
        setCanValues(1, arraysStr2arraysBytes(WASH_MODE_FRAMES), "wash mode");
    }

    // ------------------------------------------------------------------------
    // Прогрев высоковольтной батареи.
    //
    // CAN-команда активации прогрева ВВБ (предоставлена пользователем). Формат — как у остальных
    // команд (LEAVE_CAR_FRAMES / WASH_MODE_FRAMES): 10-байтные строки hex через пробел.
    private static final String[] BATTERY_HEAT_FRAMES = {
            "65 08 00 00 c1 c0 00 00 00 00",
    };

    /**
     * Активация прогрева батареи. Вызывается из {@link BatteryHeatService} (авто-прогрев по
     * температуре и ручной клик в виджете). Шлёт {@link #BATTERY_HEAT_FRAMES} в шину;
     * пустой массив (если когда-нибудь очистят) — безопасный no-op с логом.
     */
    public static void sendBatteryHeatCommand() {
        if (BATTERY_HEAT_FRAMES.length == 0) {
            Log.w("$$$ MainActivity batteryHeat $$$",
                    "sendBatteryHeatCommand: CAN-команда прогрева ещё не задана (заглушка BATTERY_HEAT_FRAMES)");
            return;
        }
        setCanValues(1, arraysStr2arraysBytes(BATTERY_HEAT_FRAMES), "battery preheat");
    }

    /** Немедленно применить звук пешеходов (тоггл с главного экрана). disabled=true → заглушить. */
    public static void sendPedestrianSoundCommand(boolean disabled) {
        setCanValues(1, getPedestrianSoundCanCommand(disabled),
                "pedestrian sound " + (disabled ? "off" : "on"));
    }

    /**
     * Старт/стоп {@link WiperColdService} по настройке «Сервисный режим дворников в
     * холодную погоду». Флаг дублируем в NativePrefs («wiperCold»), чтобы
     * {@link SetModesService} мог синхронно узнать состояние на power on/boot.
     */
    public static void applyWiperColdMode(Context context, boolean enabled) {
        if (context == null) return;
        Log.i("$$$ WiperCold $$$", "applyWiperColdMode: enabled=" + enabled);
        context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                .edit().putBoolean("wiperCold", enabled).apply();
        Intent intent = new Intent(context, WiperColdService.class);
        if (enabled) {
            context.startForegroundService(intent);
        } else {
            // НЕ сбрасываем wiperServiceActive: если дворники по нашей оценке в сервисном
            // режиме, их надо вернуть на ближайшем power on (даже с выключенной опцией) —
            // SetModesService.resetWiperColdOnPowerOn учитывает этот флаг.
            context.stopService(intent);
        }
    }

    /** @return true, если все включённые команды ушли без ошибки CAN. */
    public static boolean runCmds() {
        Log.i("$$$ MainActivity runCmds $$$", "driveMode: " + driveMode + " energy: " + energy + " recycle: " + recycle
                + " | driveEnabled=" + driveEnabled + " energyEnabled=" + energyEnabled + " recycleEnabled=" + recycleEnabled
                + " disablePedestrianSound=" + disablePedestrianSound);
        boolean ok = true;
        if (energyEnabled)  ok &= setCanValues(1, getEnergyCanCommand(energy),        "energy mode: " + energy);
        if (driveEnabled)   ok &= setCanValues(1, getDriveModeCanCommand(driveMode),  "drive mode: " + driveMode);
        if (recycleEnabled) ok &= setCanValues(1, getRecEnergyCanCommand(recycle),    "recuperation level: " + recycle);
        // «Отключить звук для пешеходов» — бинарное состояние, применяем всегда
        ok &= setCanValues(1, getPedestrianSoundCanCommand(disablePedestrianSound),
                "pedestrian sound mode " + (disablePedestrianSound ? "off" : "on"));
        return ok;
    }
    public static void setDriveMode(String driveMode){
        setCanValues(1, getDriveModeCanCommand(driveMode), "drive mode: " + driveMode);
    }

    public void onButtonClick(View v) {
        Log.i("$$$ MainActivity click $$$", "");
//                IntentFilter filter = new IntentFilter();
//        filter.addAction("android.os.action.POWER_SAVE_MODE_CHANGED");
//        filter.addAction("android.intent.action.SCREEN_ON");
//        filter.addAction("com.android.server.jobscheduler.GARAGE_MODE_StarButton");
//        filter.addAction("ru.big.town.anative.APPLY_DRIVE_MODES");
//        filter.addAction("ru.big.town.anative.APPLY_DRIVE_MODES_FROM_POWERMANAGER");
//
//        // Register receiver with filter
//        BroadcastReceiver setModesReceiver = new SetModesReceiver();
//
//        LocalBroadcastManager.getInstance(this).registerReceiver(setModesReceiver, filter);
        //LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("ru.big.town.anative.APPLY_DRIVE_MODES"));
        ApplyEngine.scheduleApply("MainActivity button");
        //initValueModes(getApplicationContext());
        //runCmds();
    }
    @Override
        public void onPause(){
        Log.i("$$$ MainActivity click $$$", "onPause()");
        super.onPause();
    }
    @Override
    public void onStop(){
        Log.i("$$$ MainActivity click $$$", "onStop()");
        super.onStop();
    }

}