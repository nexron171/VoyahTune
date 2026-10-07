package ru.big.town.restoremode.integration;

import android.content.SharedPreferences;
import android.os.Messenger;

public class GlobalVars {
    public static SharedPreferences sharedPreferences = null;
    public static SharedPreferences.Editor editor = null;
    public static Messenger serviceMessenger = null;
    public static Messenger clientMessenger = null;
    public static boolean isBound = false;
    private static int connectedClients = 0;

    public static synchronized void clientConnected(Messenger messenger) {
        connectedClients++;
        serviceMessenger = messenger;
        isBound = true;
    }

    public static synchronized void clientDisconnected() {
        if (connectedClients > 0) {
            connectedClients--;
        }
        if (connectedClients == 0) {
            serviceMessenger = null;
            isBound = false;
        }
    }
}
