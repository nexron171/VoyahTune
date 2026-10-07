package ru.big.town.restoremode.settings.sections.apps;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppsSettingsViewModel extends ViewModel {
    private final MutableLiveData<Map<String, String>> labels =
            new MutableLiveData<>(Collections.emptyMap());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean requested;
    private volatile boolean cleared;

    LiveData<Map<String, String>> labels() {
        return labels;
    }

    Map<String, String> currentLabels() {
        return labels.getValue();
    }

    void load(Context application) {
        if (requested) {
            return;
        }
        requested = true;
        worker.execute(
                () -> {
                    Map<String, String> discovered = new HashMap<>();
                    try {
                        PackageManager packages = application.getPackageManager();
                        Intent launcher =
                                new Intent(Intent.ACTION_MAIN)
                                        .addCategory(Intent.CATEGORY_LAUNCHER);
                        for (ResolveInfo info : packages.queryIntentActivities(launcher, 0)) {
                            if ((info.activityInfo.applicationInfo.flags
                                            & ApplicationInfo.FLAG_SYSTEM)
                                    == 0) {
                                discovered.put(
                                        info.activityInfo.packageName,
                                        info.loadLabel(packages).toString());
                            }
                        }
                        List<String> names = new ArrayList<>(discovered.keySet());
                        names.sort(
                                (first, second) ->
                                        discovered
                                                .get(first)
                                                .compareToIgnoreCase(discovered.get(second)));
                        Map<String, String> sorted = new LinkedHashMap<>();
                        for (String name : names) {
                            sorted.put(name, discovered.get(name));
                        }
                        if (!cleared) {
                            labels.postValue(Collections.unmodifiableMap(sorted));
                        }
                    } catch (RuntimeException exception) {
                        Log.w("SettingsApps", "App catalog unavailable", exception);
                    }
                });
    }

    @Override
    protected void onCleared() {
        cleared = true;
        worker.shutdownNow();
    }
}
