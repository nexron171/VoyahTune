package ru.big.town.restoremode.dashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import ru.big.town.restoremode.widgets.apps.AppWidgetSessionStore;

/**
 * Clears session launch memory when Native explicitly closes a task that was running in a tile. The
 * manifest receiver also runs while Dashboard is not alive, so "Close all" cannot resurrect the
 * closed applications on the next screen opening.
 */
public class EmbeddedTaskClosedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !DashboardActivity.ACTION_EMBEDDED_TASK_LEFT.equals(intent.getAction())
                || !intent.getBooleanExtra(DashboardActivity.EXTRA_EMBEDDED_TASK_CLOSED, false)) {
            return;
        }
        String packageName = intent.getStringExtra(DashboardActivity.EXTRA_EMBEDDED_TASK_PKG);
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        for (String name :
                new String[] {
                    DashboardProfile.SETTINGS_PREFERENCES, DashboardProfile.PASSENGER_PREFERENCES
                }) {
            SharedPreferences preferences =
                    context.getSharedPreferences(name, Context.MODE_PRIVATE);
            String widgetId = intent.getStringExtra("widgetId");
            if (widgetId == null) {
                AppWidgetSessionStore.forgetPackage(context, preferences, packageName);
            } else {
                AppWidgetSessionStore.forgetLaunch(
                        preferences,
                        AppWidgetSessionStore.sessionId(context, preferences),
                        widgetId);
            }
        }
    }
}
