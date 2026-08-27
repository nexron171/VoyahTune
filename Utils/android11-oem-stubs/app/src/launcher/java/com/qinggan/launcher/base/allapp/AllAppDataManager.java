package com.qinggan.launcher.base.allapp;

import com.qinggan.launcher.base.bean.AppBean;

import java.util.ArrayList;
import java.util.List;

/**
 * Synthetic dual-display All Apps store. It exposes only the static OEM ABI resolved by the agent.
 */
public final class AllAppDataManager {
    private static final List<AppBean> MAIN_APPS = new ArrayList<>();
    private static final List<AppBean> SECOND_APPS = new ArrayList<>();

    static {
        // Positive resource IDs let the production agent obtain a safe template for synthetic beans.
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
        // Stub. The real implementation rebuilds both lists and notifies AllAppDataListener instances.
    }
}
