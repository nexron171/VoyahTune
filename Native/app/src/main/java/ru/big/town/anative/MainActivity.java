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
    /** Форсированный электрорежим (колонка 19 провайдера RestoreMode). */
    private static boolean forcedEv = false;

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
        if (cmds == null) {   // неизвестное имя режима (несовпадение ключа) → пустой набор, а не NPE
            Log.w("$$$ MAIN arraysStr2arraysBytes $$$", "cmds=null (неизвестный режим?) → пустой набор");
            return new byte[0][];
        }
        int indexCmd = 0;
        byte[][] cmdsBytes = new byte[cmds.length][10];
        for (String cmd : cmds) {
            cmdsBytes[indexCmd] = parseHexBinary(cmd);
            indexCmd++;
        }
        // Не логируем каждый разобранный frame: один wake-restore создаёт десятки таких строк,
        // а серия proximity wake/sleep превращала форматирование и logd I/O в отдельный усилитель
        // нагрузки. В debug-режиме фактически отправляемые кадры уже логирует CanSender.
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
        // Интеллектуальный режим: тег радио в UI = "SMART" (в верхнем регистре, см. activity_advance.xml),
        // а сохранённое значение попадает сюда как есть → ключ ДОЛЖЕН быть "SMART". "Smart" — алиас на
        // случай иного написания/легаси (Bundle-ключи регистрозависимы; раньше был только "Smart" → NPE).
        String[] smart = new String[]{"68 08 03 00 00 f0 2c 14 18 00"};
        energyMode.putStringArray("SMART", smart);
        energyMode.putStringArray("Smart", smart);
        energyMode.putStringArray("EV", new String[]{"68 08 03 00 00 f0 2c 24 18 00"});
        energyMode.putStringArray("REV", new String[]{"68 08 03 00 00 f0 2c 34 18 00"});
        energyMode.putStringArray("SREV", new String[]{"68 08 03 00 00 f0 2c 44 18 00"});

        String[] cmds = energyMode.getStringArray(mode);
        return arraysStr2arraysBytes(cmds);
    }

    //------------- Нативные CAN-команды режимов вождения -------------------------------------------
    public static boolean sendDriveModeCommand(Context context, String mode) {
        return NativeDriveModeTransport.send(mode);
    }

    /** Вариант для wake-restore; все native-записи сериализуются внутри CanSender. */
    public static boolean sendDriveModeCommand(String mode) {
        return NativeDriveModeTransport.send(mode);
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

    //------------- Метод получения команд CAN «Форсированный EV»  ---------------------------------
    /**
     * Принудительный электрорежим: держит машину на электротяге, не давая запуститься генератору.
     * Это НЕ то же самое, что режим энергии «Электро» — тот лишь выбирает приоритет, а этот форсирует.
     * Байты из Docs/CAN-команды.odt («Форсе EV»); та же группа сообщений 0x68, что и режим энергии.
     */
    public static byte[][] getForcedEvCanCommand(boolean on) {
        Log.i("$$$ MainActivity getForcedEvCanCommand $$$", "forced EV " + (on ? "ON" : "OFF"));
        return arraysStr2arraysBytes(new String[]{
                on ? "68 08 02 00 00 f0 2c 54 08 00"
                   : "68 08 02 00 00 f0 2c 24 08 00"});
    }

    /** Немедленно применить форсированный EV (тоггл с главного экрана / из настроек). */
    public static boolean sendForcedEvCommand(boolean on) {
        return setCanValues(1, getForcedEvCanCommand(on), "forced EV " + (on ? "on" : "off"));
    }

    //------------- Управление режимом наружного света через штатный CanBusService -------------------
    public static boolean setHeadlights(Context context, boolean on){
        String command = on ? "LOW_BEAM" : "OUT_LAMP_OFF";
        Log.i("$$$ MainActivity setHeadlights $$$", "OEM CAN: " + command);
        if (CanSender.isDebugMode()) {
            Log.i("$$$ MainActivity setHeadlights $$$", "EMULATE OEM TX58: " + command + " state=1");
            return true;
        }
        return HeadlightCanTransport.send(context, on);
    }

    /** Отдельная пара для кнопок руля: ближний свет ↔ штатный автоматический режим. */
    public static boolean setHeadlightsAutoLow(Context context, boolean lowBeam){
        String command = lowBeam ? "LOW_BEAM" : "AUTO_LAMP_SWITCH";
        Log.i("$$$ MainActivity setHeadlights $$$", "OEM CAN: " + command);
        if (CanSender.isDebugMode()) {
            Log.i("$$$ MainActivity setHeadlights $$$", "EMULATE OEM TX58: " + command + " state=1");
            return true;
        }
        return HeadlightCanTransport.sendAutoPair(context, lowBeam);
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

        // Открытие Native остаётся ручным recovery-path при пропущенном power/screen callback. Движок
        // дедебаунсит этот триггер с service-start и не создаёт параллельную прямую CAN-отправку.
        ApplyEngine.scheduleApply("Native activity opened");
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

    /** Restore-команда обязательна: пустой набор нельзя засчитать как успешный CAN pass. */
    private static boolean sendRequiredCanValues(int cmdNum, byte[][] cmds, String label) {
        if (cmds == null || cmds.length == 0) {
            Log.e("$$$ MainActivity runCmds $$$", "No CAN frames for required " + label);
            return false;
        }
        return setCanValues(cmdNum, cmds, label);
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
                forcedEv = cursor.getColumnCount() > 19 && cursor.getInt(19) == 1;
                // col 12 — «Режим отладки»: эмуляция CAN в логи вместо реальной отправки
                boolean debugMode = cursor.getColumnCount() > 12 && cursor.getInt(12) == 1;
                // col 13 — «Сервисный режим дворников в холодную погоду»: старт/стоп WiperColdService
                boolean wiperColdMode = cursor.getColumnCount() > 13 && cursor.getInt(13) == 1;
                // cols 14,15 — команды кнопок на руле (короткое/долгое нажатие)
                if (cursor.getColumnCount() > 14) customCommandStarButton1 = cursor.getString(14);
                if (cursor.getColumnCount() > 15) customCommandStarButton2 = cursor.getString(15);
                // col 18 — «Пауза музыки при открытии двери водителя»: второй потребитель сигнала двери
                boolean pauseMediaOnDoor = cursor.getColumnCount() > 18 && cursor.getInt(18) == 1;
                applyModeSideEffects(context, debugMode, wiperColdMode, pauseMediaOnDoor);
                saveModesCache(context, debugMode, wiperColdMode, pauseMediaOnDoor);
                ApplyEngine.noteLoadedModes(driveMode, energy, driveEnabled, energyEnabled);
                Log.i(MODES_LOG, "FRESH: driveEnabled=" + driveEnabled
                        + " recycleEnabled=" + recycleEnabled + " energyEnabled=" + energyEnabled
                        + " disablePedestrianSound=" + disablePedestrianSound
                        + " debugMode=" + debugMode + " wiperColdMode=" + wiperColdMode
                        + " pauseMediaOnDoor=" + pauseMediaOnDoor);
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
    private static void saveModesCache(Context context, boolean debugMode, boolean wiperColdMode, boolean pauseMediaOnDoor) {
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
                .putBoolean("cacheForcedEv", forcedEv)
                .putBoolean("cacheDebugMode", debugMode)
                .putBoolean("cacheWiperColdMode", wiperColdMode)
                .putBoolean("cachePauseMediaOnDoor", pauseMediaOnDoor)
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
        forcedEv = p.getBoolean("cacheForcedEv", false);
        boolean debugMode     = p.getBoolean("cacheDebugMode", false);
        boolean wiperColdMode = p.getBoolean("cacheWiperColdMode", false);
        boolean pauseMediaOnDoor = p.getBoolean("cachePauseMediaOnDoor", false);
        applyModeSideEffects(context, debugMode, wiperColdMode, pauseMediaOnDoor);
        ApplyEngine.noteLoadedModes(driveMode, energy, driveEnabled, energyEnabled);
        Log.i(MODES_LOG, "CACHE: driveEnabled=" + driveEnabled
                + " recycleEnabled=" + recycleEnabled + " energyEnabled=" + energyEnabled
                + " disablePedestrianSound=" + disablePedestrianSound
                + " debugMode=" + debugMode + " wiperColdMode=" + wiperColdMode
                + " pauseMediaOnDoor=" + pauseMediaOnDoor);
        return true;
    }

    /** Побочные эффекты настроек, не зависящие от отправки CAN: режим отладки и сервис-реактор двери водителя. */
    private static void applyModeSideEffects(Context context, boolean debugMode, boolean wiperColdMode, boolean pauseMediaOnDoor) {
        CanSender.setDebugMode(debugMode);
        applyDoorReactor(context, wiperColdMode, pauseMediaOnDoor);
    }

    // Power Hold (leave car) — быстрая активация с главного экрана. Две CAN-команды активации.
    private static final String[] LEAVE_CAR_FRAMES = {
            "6c 08 00 3e 64 21 c7 00 00 00",
            "77 08 00 00 00 00 00 1f 00 00",
    };
    public static boolean sendLeaveCarCommand() {
        return setCanValues(1, arraysStr2arraysBytes(LEAVE_CAR_FRAMES), "leave car (power hold)");
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
    public static boolean sendWashModeCommand() {
        return setCanValues(1, arraysStr2arraysBytes(WASH_MODE_FRAMES), "wash mode");
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
    public static boolean sendBatteryHeatCommand() {
        if (BATTERY_HEAT_FRAMES.length == 0) {
            Log.w("$$$ MainActivity batteryHeat $$$",
                    "sendBatteryHeatCommand: CAN-команда прогрева ещё не задана (заглушка BATTERY_HEAT_FRAMES)");
            return false;
        }
        return setCanValues(1, arraysStr2arraysBytes(BATTERY_HEAT_FRAMES), "battery preheat");
    }

    /** Немедленно применить звук пешеходов (тоггл с главного экрана). disabled=true → заглушить. */
    public static boolean sendPedestrianSoundCommand(boolean disabled) {
        return setCanValues(1, getPedestrianSoundCanCommand(disabled),
                "pedestrian sound " + (disabled ? "off" : "on"));
    }

    /**
     * Старт/стоп {@link WiperColdService} — сервиса-реактора на открытие двери водителя. У него теперь
     * два независимых потребителя сигнала двери: «Сервисный режим дворников» ({@code wiperCold}) и
     * «Пауза музыки при открытии двери» ({@code pauseMediaOnDoor}). Оба флага дублируем в NativePrefs —
     * сам сервис читает их и гейтит соответствующее действие; {@link SetModesService} по {@code wiperCold}
     * решает про power-on reset дворников. Сервис живёт, пока включён хотя бы один потребитель.
     */
    public static void applyDoorReactor(Context context, boolean wiperEnabled, boolean pauseMediaOnDoor) {
        if (context == null) return;
        Log.i("$$$ DoorReactor $$$", "applyDoorReactor: wiper=" + wiperEnabled + " pauseMedia=" + pauseMediaOnDoor);
        context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                .edit().putBoolean("wiperCold", wiperEnabled)
                       .putBoolean("pauseMediaOnDoor", pauseMediaOnDoor).apply();
        Intent intent = new Intent(context, WiperColdService.class);
        if (wiperEnabled || pauseMediaOnDoor) {
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
        // CAN ещё не готов на раннем wake — прекращаем проход на ПЕРВОЙ ошибке. Иначе один retry
        // всё равно открывал HAL для всех 5–7 кадров и за 120с создавал сотни бесполезных ioctl.
        if (energyEnabled && !sendRequiredCanValues(1, getEnergyCanCommand(energy),
                "energy mode: " + energy)) return false;
        if (driveEnabled && !sendDriveModeCommand(driveMode)) return false;
        if (recycleEnabled && !sendRequiredCanValues(1, getRecEnergyCanCommand(recycle),
                "recuperation level: " + recycle)) return false;
        // «Отключить звук для пешеходов» — бинарное состояние, применяем всегда
        if (!sendRequiredCanValues(1, getPedestrianSoundCanCommand(disablePedestrianSound),
                "pedestrian sound mode " + (disablePedestrianSound ? "off" : "on"))) return false;
        // Форсированный EV применяем ТОЛЬКО когда он включён — и обязательно ПОСЛЕ команды энергии,
        // чтобы он её перекрыл. Команду «выкл» здесь не шлём намеренно: её байты (…2c 24 08 00)
        // содержат значение энергии «Электро», т.е. отправка на каждом применении переводила бы
        // энергорежим в электро и затирала выбор пользователя (Авто/Топливо/Сохранение).
        // Выключение уходит явным действием пользователя — см. sendForcedEvCommand(false).
        if (forcedEv && !sendRequiredCanValues(1, getForcedEvCanCommand(true), "forced EV on")) return false;
        return true;
    }
    public static void setDriveMode(String driveMode){
        sendDriveModeCommand(driveMode);
    }

    // Провайдер настроек RestoreMode — источник истины режимов (его читает loadModes/ApplyEngine и UI VoyahTune).
    private static final Uri MODES_PROVIDER_URI =
            Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/");

    /**
     * Текущий СОХРАНЁННЫЙ режим (тот, что восстанавливается на пробуждении и показан в UI VoyahTune).
     * Читаем из провайдера RestoreMode; фолбэк — статик Native.
     * Нужно кнопке руля, чтобы циклировать ОТНОСИТЕЛЬНО реального режима (правильный первый клик).
     * @param isEnergy true → энергорежим, иначе режим вождения.
     */
    public static String currentSavedMode(Context context, boolean isEnergy) {
        return currentSavedMode(context, isEnergy ? "energy" : "driveMode");
    }

    /** Вариант для driveMode/energy/recycle; нужен назначаемой кнопке рекуперации. */
    public static String currentSavedMode(Context context, String modeKey) {
        int column = modeColumn(modeKey);
        if (column < 0) return null;
        Cursor c = null;
        try {
            c = context.getContentResolver().query(MODES_PROVIDER_URI, null, null, null, null);
            if (c != null && c.getCount() != 0 && c.getColumnCount() > column) {
                c.moveToFirst();
                String v = c.getString(column);
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Exception e) {
            Log.w(MODES_LOG, "currentSavedMode: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return "energy".equals(modeKey) ? energy : "recycle".equals(modeKey) ? recycle : driveMode;
    }

    /** Быстрая проверка уже загруженного snapshot без повторного запроса к provider на каждый VState. */
    static boolean isLoadedMode(boolean isEnergy, String mode) {
        if (mode == null) return false;
        return mode.equals(isEnergy ? energy : driveMode);
    }

    /**
     * Сохранить «последний активированный» режим как ИСТОЧНИК ИСТИНЫ: пишем в pref RestoreMode через
     * провайдер (переживёт пробуждение + попадёт в UI VoyahTune), плюс освежаем статик Native и его кэш
     * (fallback «глухого» пробуждения). Вызывает кнопка руля (SetModesReceiverDynamic.cycleMode); после
     * снятия value-ID на голове — синк внешней смены режима (см. TripStatsService).
     * @param isEnergy true → энергорежим (pref "energy"), иначе режим вождения (pref "driveMode").
     */
    public static void persistSavedMode(Context context, boolean isEnergy, String mode) {
        persistSavedMode(context, isEnergy ? "energy" : "driveMode", mode);
    }

    /** Сохраняет driveMode/energy/recycle после явного действия пользователя. */
    public static void persistSavedMode(Context context, String modeKey, String mode) {
        if (context == null || mode == null || mode.isEmpty()) return;
        if (modeColumn(modeKey) < 0) return;
        boolean written = false;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(modeKey, mode);
            // update() провайдера возвращает число записанных ключей (>0 = успех). Провайдер может быть на
            // миг недоступен (перезапуск/переустановка) → ловим исключение и НЕ считаем запись успешной.
            written = context.getContentResolver().update(MODES_PROVIDER_URI, cv, null, null) > 0;
        } catch (Exception e) {
            Log.w(MODES_LOG, "persistSavedMode provider: " + e.getMessage());
        }
        // Статик — состояние текущей сессии (совпадает с только что отправленным в CAN режимом), обновляем всегда.
        if ("energy".equals(modeKey)) energy = mode;
        else if ("recycle".equals(modeKey)) recycle = mode;
        else driveMode = mode;
        if (!"recycle".equals(modeKey)) ApplyEngine.noteSavedMode("energy".equals(modeKey), mode);
        // Уведомить UI VoyahTune, чтобы селектор режима следил за текущим в реальном времени — даже когда
        // режим сменили штатным меню машины или кнопкой руля при ОТКРЫТОМ экране «Настройки автомобиля».
        try {
            Intent bi = new Intent("ru.big.town.anative.MODE_SYNCED");
            bi.setPackage("ru.big.town.restoremode");
            bi.putExtra("isEnergy", "energy".equals(modeKey));
            bi.putExtra("modeKey", modeKey);
            bi.putExtra("mode", mode);
            context.sendBroadcast(bi);
        } catch (Exception ignored) {}
        if (written) {
            // Провайдер (источник истины) записан → синхронно освежаем кэш, чтобы «глухое» пробуждение
            // (провайдер недоступен) восстановило именно этот режим и кэш НЕ расходился с провайдером.
            // cacheValid НЕ трогаем: его выставляет только ПОЛНЫЙ снимок saveModesCache; частичный — нельзя.
            try {
                context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE).edit()
                        .putString(modeCacheKey(modeKey), mode).apply();
            } catch (Exception ignored) {}
            Log.i(MODES_LOG, "persistSavedMode " + modeKey + "=" + mode + " (provider ok)");
        } else {
            // Не записали в источник истины → кэш НЕ трогаем (иначе разъедется с провайдером и на
            // пробуждении provider-first всё равно вернёт старое). Режим применён в CAN, но не переживёт сон.
            Log.w(MODES_LOG, "persistSavedMode " + modeKey + "=" + mode
                    + " — провайдер НЕ записан, режим не переживёт пробуждение");
        }
    }

    private static int modeColumn(String modeKey) {
        if ("driveMode".equals(modeKey)) return 0;
        if ("energy".equals(modeKey)) return 1;
        if ("recycle".equals(modeKey)) return 2;
        return -1;
    }

    private static String modeCacheKey(String modeKey) {
        if ("energy".equals(modeKey)) return "cacheEnergy";
        if ("recycle".equals(modeKey)) return "cacheRecycle";
        return "cacheDriveMode";
    }

    /** Прочитать сохранённое состояние бинарного действия кнопки руля. */
    public static boolean currentSavedToggle(Context context, String key) {
        int column = "disablePedestrianSound".equals(key) ? 11 : "forcedEv".equals(key) ? 19 : -1;
        if (column < 0) return false;
        Cursor c = null;
        try {
            c = context.getContentResolver().query(MODES_PROVIDER_URI, null, null, null, null);
            if (c != null && c.getCount() != 0 && c.getColumnCount() > column) {
                c.moveToFirst();
                return c.getInt(column) == 1;
            }
        } catch (Exception e) {
            Log.w(MODES_LOG, "currentSavedToggle " + key + ": " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return "forcedEv".equals(key) ? forcedEv : disablePedestrianSound;
    }

    /** Сохранить бинарное действие и синхронизировать открытый UI VoyahTune. */
    public static void persistSavedToggle(Context context, String key, boolean value) {
        if (context == null || (!"forcedEv".equals(key) && !"disablePedestrianSound".equals(key))) return;
        boolean written = false;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(key, value);
            written = context.getContentResolver().update(MODES_PROVIDER_URI, cv, null, null) > 0;
        } catch (Exception e) {
            Log.w(MODES_LOG, "persistSavedToggle provider " + key + ": " + e.getMessage());
        }
        if ("forcedEv".equals(key)) forcedEv = value; else disablePedestrianSound = value;
        try {
            Intent bi = new Intent("ru.big.town.anative.SETTING_SYNCED");
            bi.setPackage("ru.big.town.restoremode");
            bi.putExtra("key", key);
            bi.putExtra("value", value);
            context.sendBroadcast(bi);
        } catch (Exception ignored) {}
        if (written) {
            try {
                context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean("forcedEv".equals(key) ? "cacheForcedEv" : "cacheDisablePedestrianSound", value)
                        .apply();
            } catch (Exception ignored) {}
            Log.i(MODES_LOG, "persistSavedToggle " + key + "=" + value + " (provider ok)");
        } else {
            Log.w(MODES_LOG, "persistSavedToggle " + key + "=" + value + " — провайдер НЕ записан");
        }
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
