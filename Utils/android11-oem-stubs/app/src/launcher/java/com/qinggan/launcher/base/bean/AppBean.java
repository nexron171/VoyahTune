package com.qinggan.launcher.base.bean;

import java.util.List;

/**
 * Synthetic launcher-base value object matching the AppBean surface used by launcherdock.js.
 * Resource values are inert placeholders; this harness does not render the OEM launcher.
 */
public class AppBean {
    private final int icon;
    private final int nameRes;
    private final String packageName;
    private final int type;
    private List<Object> appBeans;
    private String subType;

    public AppBean(int icon, int nameRes, String packageName) {
        this.icon = icon;
        this.nameRes = nameRes;
        this.packageName = packageName;
        this.type = 1;
    }

    public int getIcon() {
        return icon;
    }

    public int getNameRes() {
        return nameRes;
    }

    public String getPackageName() {
        return packageName;
    }

    public int getType() {
        return type;
    }

    public List<Object> getAppBeans() {
        return appBeans;
    }

    public String getSubType() {
        return subType;
    }

    public void setSubType(String subType) {
        this.subType = subType;
    }
}
