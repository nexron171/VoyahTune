package ru.voyahtune.agent.modules;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

public final class AndroidDrivePersistence implements VehicleAirModule.Persistence {
    private static final Uri ENDPOINT = Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/");
    private final Context context;

    public AndroidDrivePersistence(Context context) {
        if (context == null) throw new IllegalArgumentException("Application context required for drive persistence");
        this.context = context;
    }

    @Override
    public void save(String mode) {
        Bundle arguments = new Bundle();
        arguments.putString("mode", mode);
        Bundle response = context.getContentResolver().call(ENDPOINT, "driveHookV2", "user", arguments);
        if (response == null || response.getInt("protocol", -1) != 2) {
            throw new IllegalStateException("Drive persistence provider protocol unavailable");
        }
    }
}
