package ru.voyahtune.agent.modules;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

public final class AndroidBrightnessDelivery implements BrightnessModule.Delivery {
    private final Context context;

    public AndroidBrightnessDelivery(Context context) {
        this.context = context;
    }

    @Override
    public void send(int screen, int percent) {
        Bundle arguments = new Bundle();
        arguments.putInt("screen", screen);
        arguments.putInt("percent", percent);
        context.getContentResolver().call(
                Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                "screenBrightnessManual", null, arguments);
    }
}
