package ru.big.town.anative;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.util.Collections;
import java.util.List;

/** Supported RemoteViews collection keeps every app reachable when the host widget is narrow. */
public final class LaunchAppsRemoteViewsService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new AppsFactory(getApplicationContext());
    }

    private static final class AppsFactory implements RemoteViewsFactory {
        private final Context context;
        private List<WidgetSupport.LaunchableApp> apps = Collections.emptyList();

        AppsFactory(Context context) { this.context = context; }
        @Override public void onCreate() { onDataSetChanged(); }
        @Override public void onDestroy() { apps = Collections.emptyList(); }
        @Override public void onDataSetChanged() {
            long token = Binder.clearCallingIdentity();
            try { apps = WidgetSupport.launchableApps(context); }
            finally { Binder.restoreCallingIdentity(token); }
        }
        @Override public int getCount() { return apps.size(); }
        @Override public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= apps.size()) return null;
            WidgetSupport.LaunchableApp app = apps.get(position);
            RemoteViews item = new RemoteViews(context.getPackageName(), R.layout.widget_launch_app_item);
            item.setImageViewBitmap(R.id.launch_app_icon, WidgetSupport.iconBitmap(app.icon, 64));
            item.setTextViewText(R.id.launch_app_label, app.label);
            bind(item, R.id.launch_app_item, WidgetSupport.ACTION_OPEN_APP, app.packageName);
            bind(item, R.id.widget_btn_open, WidgetSupport.ACTION_OPEN_APP, app.packageName);
            bind(item, R.id.widget_btn_fullscreen, WidgetSupport.ACTION_FULLSCREEN_LAUNCH, app.packageName);
            bind(item, R.id.widget_btn_close, WidgetSupport.ACTION_CLOSE_APP, app.packageName);
            return item;
        }
        private void bind(RemoteViews item, int view, String action, String packageName) {
            item.setOnClickFillInIntent(view, new Intent(action)
                    .putExtra(WidgetSupport.EXTRA_PACKAGE, packageName));
        }
        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean hasStableIds() { return false; }
    }
}
