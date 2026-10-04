package ru.big.town.anative;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

public class LaunchAppsWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_launch_apps);
            Intent adapter = new Intent(context, LaunchAppsRemoteViewsService.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .setData(Uri.parse("voyahtune://launch-apps/" + id));
            views.setRemoteAdapter(R.id.launch_apps_list, adapter);
            // Collection items supply only action/package. The explicit receiver is fixed by
            // this template; Android 12+ requires mutability for RemoteViews fill-in intents.
            Intent actions = new Intent(context, WidgetActionReceiver.class)
                    .setData(Uri.parse("voyahtune://launch-app-actions/" + id));
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            views.setPendingIntentTemplate(R.id.launch_apps_list,
                    PendingIntent.getBroadcast(context, id, actions, flags));
            manager.updateAppWidget(id, views);
            manager.notifyAppWidgetViewDataChanged(id, R.id.launch_apps_list);
        }
    }
}
