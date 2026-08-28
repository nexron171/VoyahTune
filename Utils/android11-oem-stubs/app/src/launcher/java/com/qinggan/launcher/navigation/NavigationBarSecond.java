package com.qinggan.launcher.navigation;

import android.view.View;

/**
 * Synthetic OD passenger-dock surface used only to validate launcherdock.js hooks.
 *
 * <p>The production passenger bar is not a NavigationBarMain subclass. Its two
 * central layout controls are the stock Air and Seat buttons; there are no
 * remappable mScreenUpItemViewN fields on this class.</p>
 */
public class NavigationBarSecond implements INavigationBar, View.OnClickListener {
    public int mScreenId = 1;
    public View mScreenUpAirView;
    public View mScreenUpSeatView;
    public View mScreenUpHomeView;
    public View mScreenUpAllAppView;
    public View mScreenUpTemperatureContentView;
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
}
