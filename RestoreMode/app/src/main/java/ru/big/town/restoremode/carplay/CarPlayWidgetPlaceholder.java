package ru.big.town.restoremode.carplay;

import android.view.View;
import android.view.ViewGroup;

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
        View button = widget.findViewById(R.id.carPlayWidgetLaunch);
        int size =
                CarPlayTarget.opensActivity(entry.packageName, entry.width, entry.height)
                        ? 64
                        : 112;
        ViewGroup.LayoutParams params = button.getLayoutParams();
        params.width =
                params.height =
                        Math.round(size * widget.getResources().getDisplayMetrics().density);
        button.setLayoutParams(params);
        button.setOnClickListener(view -> launch.run());
        button.setOnLongClickListener(drag);
        placeholder.setOnLongClickListener(drag);
        return true;
    }
}
