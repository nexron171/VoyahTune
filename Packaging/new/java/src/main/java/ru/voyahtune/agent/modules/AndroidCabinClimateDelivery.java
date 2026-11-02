package ru.voyahtune.agent.modules;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import ru.voyahtune.agent.runtime.ModuleScope;

import java.util.function.Supplier;

public final class AndroidCabinClimateDelivery implements CabinClimateModule.Delivery {
    private static final String PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
    private static final String REQUEST = "ru.big.town.anative.CABIN_TEMPERATURE_REQUEST";
    private static final String UPDATE = "ru.big.town.anative.CABIN_TEMPERATURE_UPDATE";
    private final Context context;

    public AndroidCabinClimateDelivery(Context context) {
        this.context = context;
    }

    private static Bundle bundle(CabinClimateModule.Sample sample) {
        Bundle values = new Bundle();
        if (sample != null) {
            values.putInt("raw", sample.raw);
            values.putLong("sampledAt", sample.sampledAt);
        }
        return values;
    }

    @Override
    public void register(ModuleScope scope, Supplier<CabinClimateModule.Sample> latest)
            throws Exception {
        BroadcastReceiver receiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ignored, Intent intent) {
                        if (REQUEST.equals(intent.getAction())) {
                            scope.listener(() -> setResultExtras(bundle(latest.get()))).run();
                        }
                    }
                };
        IntentFilter filter = new IntentFilter(REQUEST);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter, PERMISSION, null);
        }
        scope.own(() -> context.unregisterReceiver(receiver));
    }

    @Override
    public void publish(CabinClimateModule.Sample sample) {
        context.sendBroadcast(
                new Intent(UPDATE).setPackage("ru.big.town.anative").putExtras(bundle(sample)),
                PERMISSION);
    }

    @Override
    public void error(Exception failure) {
        Log.w("VoyahTuneCabinClimate", "Cabin observation unavailable", failure);
    }
}
