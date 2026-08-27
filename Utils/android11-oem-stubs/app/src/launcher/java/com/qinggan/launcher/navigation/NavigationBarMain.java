package com.qinggan.launcher.navigation;

import android.view.View;

/**
 * Synthetic OD-firmware surface used only to resolve launcherdock.js primary hooks.
 * It is not an implementation of the production launcher navigation bar.
 */
public class NavigationBarMain implements View.OnClickListener {
    public int mScreenId = 0;
    public View mScreenUpItemView1;
    public View mScreenUpItemView2;
    public View mScreenUpItemView3;
    public View mScreenUpItemView4;
    public View mScreenUpHomeView;
    public View mScreenUpAllAppView;
    public View mScreenUpRadioGroup;
    public View mScreenUpView;
    public View mScreenDownView;

    public void updateTheme() {
        // Stub.
    }

    public void initScreenUpViews() {
        // Stub.
    }

    public void updateSelectedApp(String packageName, String activityName) {
        // Stub.
    }

    @Override
    public void onClick(View view) {
        // Stub.
    }

    public void dismiss() {
        // Stub.
    }

    public void doScreenLift(int type) {
        // Stub.
    }

    public void startLauncherMain(boolean home) {
        // Stub.
    }

    public void openAllApp() {
        // Stub.
    }
}
