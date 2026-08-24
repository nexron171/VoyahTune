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
    public View mScreenUpAllAppView;

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
}
