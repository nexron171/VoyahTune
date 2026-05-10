package ru.big.town.anative;

import android.car.hardware.power.CarPowerManager;
import android.content.Context;

public class GlobalVars {
    public static Context SAVE_CONTEXT = null;
    public static volatile int running = 0;
    public static volatile CarPowerManager mCarPowerManager = null;
    public  static volatile int buttonDriveMode = 1;
}
