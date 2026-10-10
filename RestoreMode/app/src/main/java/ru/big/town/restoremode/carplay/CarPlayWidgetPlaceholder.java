package ru.big.town.restoremode.carplay;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.restoremode.R;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;

/** The same launch affordance serves the shortcut and an inactive projection tile. */
public final class CarPlayWidgetPlaceholder {
    private CarPlayWidgetPlaceholder() {}

    public static boolean show(
            View widget,
            AppWidgetStore.Entry entry,
            Runnable launch,
            View.OnLongClickListener drag) {
        View placeholder = widget.findViewById(R.id.carPlayWidgetPlaceholder);
        boolean carPlay = entry.profiles.size() == 1 && CarPlayTarget.isCarPlay(entry.packageName);
        placeholder.setVisibility(carPlay ? View.VISIBLE : View.GONE);
        if (!carPlay) return false;

        widget.findViewById(R.id.appWidgetLauncherScroll).setVisibility(View.GONE);
        widget.findViewById(R.id.appWidgetLauncherControls).setVisibility(View.GONE);
        widget.findViewById(R.id.appWidgetOverlayControls).setVisibility(View.GONE);
        widget.findViewById(R.id.appWidgetBadge).setVisibility(View.GONE);
        ImageView button = widget.findViewById(R.id.carPlayWidgetLaunch);
        boolean shortcut = CarPlayTarget.opensActivity(entry.packageName, entry.width, entry.height);
        int size = shortcut ? 64 : 112;
        button.setImageResource(shortcut ? R.mipmap.ic_carplay_launcher : R.drawable.carplay_home_logo);
        ViewGroup.LayoutParams params = button.getLayoutParams();
        params.width =
                params.height =
                        Math.round(size * widget.getResources().getDisplayMetrics().density);
        button.setLayoutParams(params);
        button.setOnClickListener(view -> launch.run());
        button.setOnLongClickListener(drag);
        placeholder.setOnClickListener(view -> launch.run());
        placeholder.setOnLongClickListener(drag);
        return true;
    }
}
