package ru.big.town.restoremode;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import ru.big.town.common.EnergyWidgetProtocol;
import ru.big.town.common.EnergyWidgetSettings;

final class EnergyWidgetPreferences {
    private EnergyWidgetPreferences() {}
    static float capacity(SharedPreferences prefs,String key,float fallback) {
        try {
            float value=prefs.getFloat(key,fallback);
            return EnergyWidgetSettings.validCapacity(value)?value:fallback;
        } catch(ClassCastException ignored) { return fallback; }
    }
    static void sync(Messenger nativeService,SharedPreferences prefs) throws RemoteException {
        Message msg=Message.obtain(null,EnergyWidgetProtocol.CONFIGURE);
        Bundle b=new Bundle();b.putInt(EnergyWidgetProtocol.SCHEMA,EnergyWidgetProtocol.VERSION);
        b.putFloat(EnergyWidgetProtocol.BATTERY_KWH,capacity(prefs,EnergyWidgetSettings.BATTERY_KEY,43));
        b.putFloat(EnergyWidgetProtocol.TANK_LITERS,capacity(prefs,EnergyWidgetSettings.TANK_KEY,56));
        msg.setData(b);nativeService.send(msg);
    }
}
