package ru.big.town.restoremode.carplay;

import android.graphics.Color;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

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
        boolean shortcut = carPlay
                && CarPlayTarget.opensActivity(entry.packageName, entry.width, entry.height);
        widget.setBackgroundResource(shortcut ? 0 : R.drawable.card_dark_ripple);
        placeholder.setVisibility(carPlay ? View.VISIBLE : View.GONE);
        if (!carPlay) return false;

        widget.findViewById(R.id.appWidgetLauncherScroll).setVisibility(View.GONE);
        widget.findViewById(R.id.appWidgetLauncherControls).setVisibility(View.GONE);
        widget.findViewById(R.id.appWidgetOverlayControls).setVisibility(View.GONE);
        widget.findViewById(R.id.appWidgetBadge).setVisibility(View.GONE);
        ImageView button = widget.findViewById(R.id.carPlayWidgetLaunch);
        View shortcutTile = widget.findViewById(R.id.carPlayWidgetShortcut);
        placeholder.setBackgroundColor(shortcut ? Color.TRANSPARENT : Color.BLACK);
        shortcutTile.setVisibility(shortcut ? View.VISIBLE : View.GONE);
        button.setVisibility(shortcut ? View.GONE : View.VISIBLE);
        if (shortcut) {
            ImageView icon = shortcutTile.findViewById(R.id.tileIco);
            icon.setImageResource(R.drawable.carplay_home_logo);
            TextView title = shortcutTile.findViewById(R.id.tileTitle);
            title.setText(R.string.carplay_home_title);
            shortcutTile.setOnClickListener(view -> launch.run());
            shortcutTile.setOnLongClickListener(drag);
        }
        button.setOnClickListener(view -> launch.run());
        button.setOnLongClickListener(drag);
        placeholder.setOnClickListener(view -> launch.run());
        placeholder.setOnLongClickListener(drag);
        return true;
    }
}
