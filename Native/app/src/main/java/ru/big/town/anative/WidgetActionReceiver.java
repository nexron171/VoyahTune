package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class WidgetActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String packageName = intent.getStringExtra(WidgetSupport.EXTRA_PACKAGE);
        if (WidgetSupport.ACTION_OPEN_APP.equals(action)) {
            // Freeform-открытие через виджет — сохраняем пакет для автозапуска при повторном открытии MainActivity
            WidgetSupport.saveLastManualApp(context, packageName);
            WidgetSupport.openApp(context, packageName);
        } else if (WidgetSupport.ACTION_SIMPLE_LAUNCH.equals(action)) {
            // Обычный запуск приложения (simpleLaunch) — без VirtualDisplay/сплита
            // Сохраняем пакет для автозапуска при повторном открытии MainActivity
            WidgetSupport.saveLastManualApp(context, packageName);
            WidgetSupport.simpleLaunch(context, packageName);
        } else if (WidgetSupport.ACTION_FULLSCREEN_LAUNCH.equals(action)) {
            // Развернуть приложение на весь экран (fullscreen) — через SplitHostActivity
            // Сохраняем пакет для автозапуска при повторном открытии MainActivity
            WidgetSupport.saveLastManualApp(context, packageName);
            WidgetSupport.fullscreenLaunch(context, packageName);
        } else if (WidgetSupport.ACTION_CLOSE_APP.equals(action)) {
            // При закрытии приложения очищаем сохранённый пакет, чтобы при следующем открытии
            // MainActivity оно не запустилось автоматически (если пользователь его закрыл).
            WidgetSupport.clearLastManualApp(context);
            WidgetSupport.stopApp(context, packageName);
        } else if (WidgetSupport.ACTION_CLOSE_ALL.equals(action)) {
            WidgetSupport.stopAllApps(context);
        }
    }
}
