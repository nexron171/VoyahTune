package ru.big.town.anative;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

public class LaunchAppsWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_launch_apps);
            int index = 0;
            for (WidgetSupport.LaunchableApp app : WidgetSupport.launchableApps(context)) {
                RemoteViews item = new RemoteViews(context.getPackageName(), R.layout.widget_launch_app_item);
                item.setImageViewBitmap(R.id.launch_app_icon, WidgetSupport.iconBitmap(app.icon, 64));
                item.setTextViewText(R.id.launch_app_label, app.label);

                // Общий requestCode-префикс для виджета, уникальная часть = id * 1000 + index
                int baseCode = id * 1000 + index;

                // Кнопка 1: Freeform-открытие (окно на виртуальном дисплее)
                PendingIntent openPending = WidgetSupport.appPendingIntent(context, WidgetSupport.ACTION_OPEN_APP,
                        app.packageName, baseCode);
                item.setOnClickPendingIntent(R.id.widget_btn_open, openPending);

                // Кнопка 2: Развернуть на весь экран (fullscreen) — через SplitHostActivity single pane
                PendingIntent fullscreenPending = WidgetSupport.appPendingIntent(context, WidgetSupport.ACTION_FULLSCREEN_LAUNCH,
                        app.packageName, baseCode + 1);
                item.setOnClickPendingIntent(R.id.widget_btn_fullscreen, fullscreenPending);

                // Кнопка 3: Закрытие приложения (крестик)
                PendingIntent closePending = WidgetSupport.appPendingIntent(context, WidgetSupport.ACTION_CLOSE_APP,
                        app.packageName, baseCode + 2);
                item.setOnClickPendingIntent(R.id.widget_btn_close, closePending);

                // Клик по всей плитке — то же, что freeform-открытие (для обратной совместимости)
                item.setOnClickPendingIntent(R.id.launch_app_item, openPending);

                views.addView(R.id.launch_apps_list, item);
                index++;
            }
            manager.updateAppWidget(id, views);
        }
    }
}
