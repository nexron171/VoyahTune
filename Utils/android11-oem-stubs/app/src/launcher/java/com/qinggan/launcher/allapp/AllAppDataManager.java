package com.qinggan.launcher.allapp;

import java.util.ArrayList;
import java.util.List;

/** Synthetic H97C dual-display All Apps store. */
public final class AllAppDataManager {
    private static final List<AppBean> MAIN_APPS = new ArrayList<>();
    private static final List<AppBean> SECOND_APPS = new ArrayList<>();

    static {
        MAIN_APPS.add(template("stub.main"));
        SECOND_APPS.add(template("stub.second"));
    }

    private AllAppDataManager() {
    }

    private static AppBean template(String packageName) {
        AppBean bean = new AppBean(android.R.drawable.sym_def_app_icon,
                android.R.string.unknownName, packageName);
        bean.setSubType(packageName);
        return bean;
    }

    public static List<AppBean> getAllApps(int screenId) {
        return screenId == 0 ? MAIN_APPS : SECOND_APPS;
    }

    public static void reload() {
        // Stub.
    }
}
