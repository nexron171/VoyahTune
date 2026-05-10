package ru.big.town.anative;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
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
        if (customCommand == "") return new byte[][]{{}};
        String[] cmds = customCommand.split("\n");
        return arraysStr2arraysBytes(cmds);
    }

    public static byte[][] getCustomCommandStarButton1() {
        if (customCommandStarButton1 == "") return new byte[][]{{}};
        String[] cmds = customCommandStarButton1.split("\n");
        return arraysStr2arraysBytes(cmds);
    }
    public static byte[][] getCustomCommandStarButton2() {
        if (customCommandStarButton2 == "") return new byte[][]{{}};
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

    public static void setCanValues(int cmdNum, byte[][] cmds) {
        //printBytesArrayToLog("$$$ MAIN setCanValues $$$",cmds);
        for (byte[] cmd : cmds) {
            if (cmd.length == 10) cis_can_control_bytes(cmdNum, cmd);
        }
    }

    public static void initValueModes(Context context) {

        Cursor cursor = context.getContentResolver().query(Uri
                        .parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                null, null,
                null, null);
        if (cursor != null && cursor.getCount() != 0) {
            cursor.moveToFirst();
            driveMode = cursor.getString(0);
            energy = cursor.getString(1);
            recycle = cursor.getString(2);
            customCommand = cursor.getString(3);
            customCommandCount = cursor.getInt(4);
            customCommandStarButton1 = cursor.getString(5);
            customCommandStarButton2 = cursor.getString(6);

        } else {
            Log.w("$$$ MainActivity initValueModes", "Content provider not found");
        }
    }

    public static void runCmds() {
        Log.i("$$$ MainActivity runCmds $$$", "driveMode: " + driveMode + " energy: " + energy + " recycle: " + recycle);
        setCanValues(1, getEnergyCanCommand(energy));
        setCanValues(1, getDriveModeCanCommand(driveMode));
        setCanValues(1, getRecEnergyCanCommand(recycle));
        //Toast.makeText(this, "Значения установлены!", Toast.LENGTH_SHORT).show();
    }
    public static void setDriveMode(String driveMode){
        setCanValues(1, getDriveModeCanCommand(driveMode));
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
        SetModesService.worker(2,3500);
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