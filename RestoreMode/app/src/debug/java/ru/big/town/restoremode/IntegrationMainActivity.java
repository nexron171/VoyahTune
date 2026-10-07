package ru.big.town.restoremode;

import android.content.Intent;

/** Endpoint substitution is absent from the release APK. */
public final class IntegrationMainActivity extends MainActivity {
    @Override protected Intent nativeServiceIntent() {
        if(getIntent().getBooleanExtra("realNativeWithMockVehicle",false))
            return new Intent().setClassName("ru.big.town.anative","ru.big.town.anative.NativeContractService");
        return new Intent().setClassName("ru.big.town.restoremode.test","ru.big.town.restoremode.MockNativeService");
    }
}
