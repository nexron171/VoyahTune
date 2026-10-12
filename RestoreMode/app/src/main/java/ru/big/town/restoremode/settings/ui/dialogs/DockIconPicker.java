package ru.big.town.restoremode.settings.ui.dialogs;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.common.DockIconStyle;
import ru.big.town.restoremode.R;

import java.util.function.Consumer;

public final class DockIconPicker {
    private DockIconPicker() {}

    public static int label(DockIconStyle style) {
        switch (style) {
            case NAVIGATION:
                return R.string.dock_icon_navigation;
            case MUSIC:
                return R.string.dock_icon_music;
            default:
                return R.string.dock_icon_default;
        }
    }

    public static void show(
            Context context,
            int slot,
            String packageName,
            DockIconStyle selected,
            Consumer<DockIconStyle> onSelected) {
        DockIconStyle[] styles = DockIconStyle.values();
        String[] labels = new String[styles.length];
        for (int index = 0; index < styles.length; index++) {
            labels[index] = context.getString(label(styles[index]));
        }

        MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(context, R.style.SettingsDialog);
        Context dialogContext = builder.getContext();
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(
                        dialogContext, android.R.layout.simple_list_item_single_choice, labels) {
                    @Override
                    public View getView(int position, View recycled, ViewGroup parent) {
                        TextView row = (TextView) super.getView(position, recycled, parent);
                        Drawable icon = icon(dialogContext, styles[position], packageName);
                        if (icon != null) {
                            float density = context.getResources().getDisplayMetrics().density;
                            boolean dockCanvas =
                                    styles[position] != DockIconStyle.DEFAULT
                                            || CarPlayTarget.isCarPlay(packageName);
                            int width = Math.round((dockCanvas ? 93 : 40) * density);
                            int height = Math.round((dockCanvas ? 72 : 40) * density);
                            icon.setBounds(0, 0, width, height);
                        }
                        row.setCompoundDrawablesRelative(icon, null, null, null);
                        return row;
                    }
                };
        builder.setTitle(context.getString(R.string.dock_icon_title, slot))
                .setSingleChoiceItems(
                        adapter,
                        selected.ordinal(),
                        (dialog, position) -> {
                            onSelected.accept(styles[position]);
                            dialog.dismiss();
                        })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static Drawable icon(Context context, DockIconStyle style, String packageName) {
        if (style == DockIconStyle.NAVIGATION) {
            return context.getDrawable(R.drawable.dock_navigation_icon);
        }
        if (style == DockIconStyle.MUSIC) {
            return context.getDrawable(R.drawable.dock_music_icon);
        }
        if (CarPlayTarget.isCarPlay(packageName)) {
            return context.getDrawable(R.drawable.carplay_dock_logo);
        }
        try {
            return context.getPackageManager().getApplicationIcon(packageName);
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return context.getPackageManager().getDefaultActivityIcon();
        }
    }
}
