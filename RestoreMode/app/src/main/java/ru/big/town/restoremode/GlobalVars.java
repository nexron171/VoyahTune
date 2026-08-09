package ru.big.town.restoremode;

import android.content.SharedPreferences;
import android.os.Messenger;

public class GlobalVars {
    static  SharedPreferences     sharedPreferences=null;
    static SharedPreferences.Editor editor=null;
    static Messenger serviceMessenger = null;
    static Messenger clientMessenger = null;
    static boolean isBound = false;
    private static int connectedClients = 0;

    static synchronized void clientConnected(Messenger messenger) {
        connectedClients++;
        serviceMessenger = messenger;
        isBound = true;
    }

    static synchronized void clientDisconnected() {
        if (connectedClients > 0) connectedClients--;
        if (connectedClients == 0) {
            serviceMessenger = null;
            isBound = false;
        }
    }
}
