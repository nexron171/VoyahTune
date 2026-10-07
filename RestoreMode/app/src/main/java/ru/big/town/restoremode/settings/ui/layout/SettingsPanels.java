package ru.big.town.restoremode.settings.ui.layout;

import android.view.View;
import android.widget.LinearLayout;

import ru.big.town.restoremode.R;
import ru.big.town.restoremode.settings.ui.list.SettingsList;
import ru.big.town.restoremode.settings.ui.style.SettingsDesign;
import ru.big.town.restoremode.settings.ui.style.SettingsPanelDrawable;

import java.util.ArrayList;
import java.util.List;

public final class SettingsPanels {
    private SettingsPanels() {}

    public static View widgetFragment(View content, boolean first, boolean last) {
        content.setBackground(
                new SettingsPanelDrawable(
                        content.getResources().getDisplayMetrics().density,
                        content.getContext().getColor(R.color.settings_surface),
                        content.getContext().getColor(R.color.settings_border),
                        first,
                        last));
        return content;
    }

    public static View spaced(View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = SettingsDesign.dp(view, 16);
        view.setLayoutParams(params);
        return view;
    }

    public static void addAppChipRows(
            List<SettingsList.Row> rows,
            List<String> packages,
            String keyPrefix,
            boolean startsPanel,
            java.util.function.BiFunction<LinearLayout, String, View> chipFactory) {
        // Bound each flow row so long app lists never inflate outside the viewport.
        final int chipsPerRow = 3;
        for (int index = 0; index < packages.size(); index += chipsPerRow) {
            int end = Math.min(index + chipsPerRow, packages.size());
            List<String> group = new ArrayList<>(packages.subList(index, end));
            boolean first = startsPanel && index == 0;
            boolean hasNextRow = end < packages.size();
            rows.add(
                    new SettingsList.Row(
                            keyPrefix + ":" + group.get(0),
                            parent -> {
                                SettingsFlow flow = new SettingsFlow(parent.getContext(), null);
                                for (String packageName : group) {
                                    flow.addView(chipFactory.apply(flow, packageName));
                                }
                                if (hasNextRow) {
                                    LinearLayout.LayoutParams params =
                                            new LinearLayout.LayoutParams(-1, -2);
                                    params.bottomMargin = SettingsDesign.dp(flow, 12);
                                    flow.setLayoutParams(params);
                                }
                                return appSelectionPanel(flow, first, false);
                            }));
        }
    }

    public static View appSelectionPanel(View content, boolean first, boolean last) {
        LinearLayout panel = new LinearLayout(content.getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsDesign.dp(panel, 25);
        panel.setPadding(padding, first ? padding : 0, padding, last ? padding : 0);
        panel.addView(content);
        return widgetFragment(panel, first, last);
    }
}
