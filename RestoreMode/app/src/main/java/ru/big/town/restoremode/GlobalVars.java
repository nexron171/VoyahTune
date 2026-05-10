package ru.big.town.restoremode;

import android.content.SharedPreferences;
import android.os.Messenger;

public class GlobalVars {
    static  SharedPreferences     sharedPreferences=null;
    static SharedPreferences.Editor editor=null;
    static Messenger serviceMessenger = null;
    static Messenger clientMessenger = null;
    static boolean isBound = true;
}
