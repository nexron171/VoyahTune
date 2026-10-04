package com.qinggan.app.vehiclesetting.fragments.driveassistance.adas;

/** Pure getter fixtures only: no subscription provider, TSP threads, observers or CAN. */
public final class DriveAssistanceAdasStatusManager {
    public boolean getSubscriptionStatus() {
        return false;
    }

    public boolean getExpireStatus() {
        return false;
    }

    public int getRemainDay() {
        return 0;
    }

    public int getLearnStatus() {
        return 0;
    }
}
