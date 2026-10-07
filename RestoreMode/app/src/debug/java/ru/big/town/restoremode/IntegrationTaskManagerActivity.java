package ru.big.town.restoremode;

import android.content.Intent;

/** Keeps endpoint selection outside the production activity and manifest. */
public final class IntegrationTaskManagerActivity extends TaskManagerActivity {
    @Override protected Intent nativeServiceIntent() {
        if (getIntent().getBooleanExtra("unavailableNative", false))
            return new Intent().setClassName("ru.big.town.restoremode.test", "missing.Service");
        if (getIntent().getBooleanExtra("realNativeWithMockVehicle", false))
            return new Intent().setClassName("ru.big.town.anative", "ru.big.town.anative.NativeContractService");
        return new Intent().setClassName("ru.big.town.restoremode.test", "ru.big.town.restoremode.MockNativeService");
    }
}
