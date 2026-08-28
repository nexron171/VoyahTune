package com.qinggan.launcher.navigation;

/**
 * Synthetic controller shared by the OD driver and passenger navigation bars.
 * The real screen identity belongs to this instance, not to a passenger-specific
 * controller class.
 */
public class NavigationBarController {
    public int mScreenId;
    public INavigationBar mNavigationBar;

    public void doScreenLift(int type) {
        // Stub.
    }

    public void show() {
        // Stub.
    }

    public void dismiss() {
        // Stub.
    }

    public boolean isShowing() {
        return false;
    }
}
