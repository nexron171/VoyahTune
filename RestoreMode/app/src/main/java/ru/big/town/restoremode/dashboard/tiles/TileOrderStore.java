package ru.big.town.restoremode.dashboard.tiles;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.restoremode.apps.split.SplitStore;
import ru.big.town.restoremode.scenarios.ScenarioStore;
import ru.big.town.restoremode.widgets.apps.AppShortcutStore;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;
import ru.big.town.restoremode.widgets.brightness.BrightnessWidgetLayout;
import ru.big.town.restoremode.widgets.climate.CabinTemperatureWidgetLayout;
import ru.big.town.restoremode.widgets.clock.ClockWidgetView;
import ru.big.town.restoremode.widgets.dials.DialWidgetStore;
import ru.big.town.restoremode.widgets.energy.VehicleWidgets;
import ru.big.town.restoremode.widgets.headlights.HeadlightWidgetLayout;
import ru.big.town.restoremode.widgets.links.LinkShortcutStore;
import ru.big.town.restoremode.widgets.media.MediaWidgetLayout;
import ru.big.town.restoremode.widgets.radio.RadioWidgetLayout;
import ru.big.town.restoremode.widgets.system.SystemWidgetLayout;

import java.util.ArrayList;
import java.util.List;

/** Screen-scoped tile order with shared definitions for widgets and scenarios. */
public class TileOrderStore {
    private static final String KEY = "tileOrder";
    private static final String[] KNOWN_WIDGETS = {
        HeadlightWidgetLayout.ID,
        BrightnessWidgetLayout.ID,
        CabinTemperatureWidgetLayout.ID,
        RadioWidgetLayout.ID,
        ClockWidgetView.ID,
        SystemWidgetLayout.CPU,
        SystemWidgetLayout.RAM,
        SystemWidgetLayout.CLEAR,
        MediaWidgetLayout.MEDIA,
        VehicleWidgets.ENERGY,
        VehicleWidgets.CONSUMPTION,
        VehicleWidgets.TRIP,
        VehicleWidgets.TIRES,
        VehicleWidgets.ODO,
        "tripCard",
        "cardPowerHold",
        "cardWashMode",
        "cardRearFog",
        "cardHotspot",
        "cardAutoLight",
        "cardPedestrian",
        "cardForcedEv",
        "cardSuspensionMaintenance",
        "cardBatteryHeat",
        "suspensionWidget",
        "cardSettings",
        "cardAndroidSettings",
        "cardVoiceCommand",
        "cardScenarios",
        "launchAppsWidget",
        "taskManagerTile",
        "passengerDashboardTile"
    };

    public static class Tile {
        public static final String TYPE_SPLIT = "split";
        public static final String TYPE_APP = "app";
        public static final String TYPE_WIDGET = "widget";
        public static final String TYPE_APP_WIDGET = "appWidget";
        public static final String TYPE_LINK = "link";
        public static final String TYPE_DIAL = "dial";
        public static final String TYPE_SCENARIO = "scenario";

        public String type;
        public String id;

        public Tile(String type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    /** Load the saved order or initialize it from the configured stores. */
    public static List<Tile> load(SharedPreferences preferences) {
        return load(preferences, preferences, false);
    }

    /**
     * Loads a screen-scoped order. Splits and scenarios are definitions shared by both screens, so
     * they are read from {@code content}; app widgets, shortcuts and dials come from {@code
     * layout}.
     */
    public static List<Tile> load(
            SharedPreferences layout, SharedPreferences content, boolean passenger) {
        List<Tile> tiles = new ArrayList<>();
        String stored = layout.getString(KEY, "");

        if (!stored.isEmpty()) {
            try {
                JSONArray entries = new JSONArray(stored);
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    String type = entry.optString("type", "");
                    String id = entry.optString("id", "");
                    if (!type.isEmpty() && !id.isEmpty()) {
                        tiles.add(new Tile(type, id));
                    }
                }
            } catch (Exception ignored) {
            }
        } else if (passenger) {
            for (String app : AppShortcutStore.load(layout)) {
                tiles.add(new Tile(Tile.TYPE_APP, app));
            }
            for (String widgetId : KNOWN_WIDGETS) {
                tiles.add(new Tile(Tile.TYPE_WIDGET, widgetId));
            }
            for (DialWidgetStore.Entry entry : DialWidgetStore.load(layout)) {
                tiles.add(new Tile(Tile.TYPE_DIAL, entry.id));
            }
            for (AppWidgetStore.Entry entry : AppWidgetStore.load(layout)) {
                tiles.add(new Tile(Tile.TYPE_APP_WIDGET, entry.id));
            }
            if (!tiles.isEmpty()) {
                save(layout, tiles);
            }
        } else {
            List<SplitStore.Preset> splits = SplitStore.load(content);
            for (SplitStore.Preset preset : splits) {
                if (preset.ready()) {
                    tiles.add(new Tile(Tile.TYPE_SPLIT, preset.id));
                }
            }
            List<String> apps = AppShortcutStore.load(layout);
            for (String app : apps) {
                tiles.add(new Tile(Tile.TYPE_APP, app));
            }
            tiles.add(new Tile(Tile.TYPE_WIDGET, "tripCard"));
            tiles.add(new Tile(Tile.TYPE_WIDGET, "cardPowerHold"));
            tiles.add(new Tile(Tile.TYPE_WIDGET, "cardWashMode"));
            tiles.add(new Tile(Tile.TYPE_WIDGET, "cardAutoLight"));
            tiles.add(new Tile(Tile.TYPE_WIDGET, "cardPedestrian"));
            tiles.add(new Tile(Tile.TYPE_WIDGET, "cardForcedEv"));
            tiles.add(new Tile(Tile.TYPE_WIDGET, "cardSuspensionMaintenance"));
            tiles.add(new Tile(Tile.TYPE_WIDGET, "cardBatteryHeat"));
            tiles.add(new Tile(Tile.TYPE_WIDGET, "suspensionWidget"));
            tiles.add(new Tile(Tile.TYPE_WIDGET, "cardVoiceCommand"));
            tiles.add(new Tile(Tile.TYPE_WIDGET, "launchAppsWidget"));
            tiles.add(new Tile(Tile.TYPE_WIDGET, "taskManagerTile"));
            if (!tiles.isEmpty()) {
                save(layout, tiles);
            }
        }
        return tiles;
    }

    /** Save only this screen's tile order. */
    public static void save(SharedPreferences preferences, List<Tile> list) {
        JSONArray entries = new JSONArray();
        try {
            for (Tile tile : list) {
                JSONObject entry = new JSONObject();
                entry.put("type", tile.type);
                entry.put("id", tile.id);
                entries.put(entry);
            }
        } catch (Exception ignored) {
        }
        preferences.edit().putString(KEY, entries.toString()).apply();
    }

    /** Visibility is applied by the dashboard after validating the tile definition. */
    static boolean exists(SharedPreferences preferences, Tile tile, PackageManager packageManager) {
        return exists(preferences, preferences, tile, packageManager, false);
    }

    static boolean exists(
            SharedPreferences layout,
            SharedPreferences content,
            Tile tile,
            PackageManager packageManager,
            boolean passenger) {
        if (Tile.TYPE_SPLIT.equals(tile.type)) {
            if (passenger) {
                return false;
            }
            List<SplitStore.Preset> splits = SplitStore.load(content);
            for (SplitStore.Preset preset : splits) {
                if (preset.id.equals(tile.id) && preset.ready()) {
                    return true;
                }
            }
            return false;
        } else if (Tile.TYPE_APP.equals(tile.type)) {
            if (!AppShortcutStore.load(layout).contains(tile.id)) {
                return false;
            }
            try {
                packageManager.getApplicationInfo(tile.id, 0);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        } else if (Tile.TYPE_WIDGET.equals(tile.type)) {
            // Hidden widgets retain their saved positions in both screen profiles.
            return isKnownWidget(tile.id);
        } else if (Tile.TYPE_APP_WIDGET.equals(tile.type)) {
            AppWidgetStore.Entry entry = AppWidgetStore.find(layout, tile.id);
            if (entry == null) {
                return false;
            }
            try {
                packageManager.getApplicationInfo(
                        CarPlayTarget.isCarPlay(entry.packageName)
                                ? CarPlayTarget.PACKAGE
                                : entry.packageName,
                        0);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        } else if (Tile.TYPE_LINK.equals(tile.type)) {
            return LinkShortcutStore.find(layout, tile.id) != null;
        } else if (Tile.TYPE_DIAL.equals(tile.type)) {
            for (DialWidgetStore.Entry entry : DialWidgetStore.load(layout)) {
                if (entry.id.equals(tile.id)) {
                    return true;
                }
            }
            return false;
        } else if (Tile.TYPE_SCENARIO.equals(tile.type)) {
            ScenarioStore.Scenario scenario = ScenarioStore.find(content, tile.id);
            return scenario != null && scenario.enabled && scenario.showTile;
        }
        return false;
    }

    /** Both dashboards share the same built-in widget catalog. */
    static boolean isKnownWidget(String widgetId) {
        for (String known : KNOWN_WIDGETS) {
            if (known.equals(widgetId)) {
                return true;
            }
        }
        return false;
    }

    /** Preserve saved positions while appending new definitions and removing unavailable tiles. */
    public static void sync(SharedPreferences preferences, PackageManager packageManager) {
        sync(preferences, preferences, packageManager, false);
    }

    public static void sync(
            SharedPreferences layout,
            SharedPreferences content,
            PackageManager packageManager,
            boolean passenger) {
        List<Tile> tiles = load(layout, content, passenger);

        List<DialWidgetStore.Entry> dials = DialWidgetStore.load(layout);
        if (dials.isEmpty()) {
            String oldNumber = layout.getString("dialWidgetNumber", "");
            if (!oldNumber.isEmpty()) {
                DialWidgetStore.Entry entry = new DialWidgetStore.Entry();
                entry.name = layout.getString("dialWidgetContactName", "Набрать номер");
                entry.number = oldNumber;
                dials.add(entry);
                DialWidgetStore.save(layout, dials);
            }
        }

        for (int i = tiles.size() - 1; i >= 0; i--) {
            if (!exists(layout, content, tiles.get(i), packageManager, passenger)) {
                tiles.remove(i);
            }
        }

        if (!passenger) {
            List<SplitStore.Preset> splits = SplitStore.load(content);
            for (SplitStore.Preset preset : splits) {
                if (preset.ready()) {
                    boolean found = false;
                    for (Tile tile : tiles) {
                        if (Tile.TYPE_SPLIT.equals(tile.type) && tile.id.equals(preset.id)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        tiles.add(new Tile(Tile.TYPE_SPLIT, preset.id));
                    }
                }
            }
        }

        List<String> apps = AppShortcutStore.load(layout);
        for (String app : apps) {
            boolean found = false;
            for (Tile tile : tiles) {
                if (Tile.TYPE_APP.equals(tile.type) && tile.id.equals(app)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                tiles.add(new Tile(Tile.TYPE_APP, app));
            }
        }

        for (String widgetId : KNOWN_WIDGETS) {
            boolean found = false;
            for (Tile tile : tiles) {
                if (Tile.TYPE_WIDGET.equals(tile.type) && tile.id.equals(widgetId)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                tiles.add(new Tile(Tile.TYPE_WIDGET, widgetId));
            }
        }

        for (DialWidgetStore.Entry entry : dials) {
            boolean found = false;
            for (Tile tile : tiles) {
                if (Tile.TYPE_DIAL.equals(tile.type) && tile.id.equals(entry.id)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                tiles.add(new Tile(Tile.TYPE_DIAL, entry.id));
            }
        }

        for (AppWidgetStore.Entry entry : AppWidgetStore.load(layout)) {
            boolean found = false;
            for (Tile tile : tiles) {
                if (Tile.TYPE_APP_WIDGET.equals(tile.type) && tile.id.equals(entry.id)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                tiles.add(new Tile(Tile.TYPE_APP_WIDGET, entry.id));
            }
        }

        for (LinkShortcutStore.Entry entry : LinkShortcutStore.load(layout)) {
            boolean found = false;
            for (Tile tile : tiles) {
                if (Tile.TYPE_LINK.equals(tile.type) && tile.id.equals(entry.id)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                tiles.add(new Tile(Tile.TYPE_LINK, entry.id));
            }
        }

        for (ScenarioStore.Scenario scenario : ScenarioStore.load(content)) {
            if (!scenario.enabled || !scenario.showTile) {
                continue;
            }
            boolean found = false;
            for (Tile tile : tiles) {
                if (Tile.TYPE_SCENARIO.equals(tile.type) && tile.id.equals(scenario.id)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                tiles.add(new Tile(Tile.TYPE_SCENARIO, scenario.id));
            }
        }

        save(layout, tiles);
    }
}
