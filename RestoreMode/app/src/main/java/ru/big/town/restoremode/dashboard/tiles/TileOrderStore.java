package ru.big.town.restoremode.dashboard.tiles;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import ru.big.town.common.CarPlayTarget;
import ru.big.town.restoremode.apps.split.SplitStore;
import ru.big.town.restoremode.scenarios.ScenarioStore;
import ru.big.town.restoremode.widgets.apps.AppShortcutStore;
import ru.big.town.restoremode.widgets.apps.AppWidgetStore;
import ru.big.town.restoremode.widgets.dials.DialWidgetStore;
import ru.big.town.restoremode.widgets.energy.VehicleWidgets;
import ru.big.town.restoremode.widgets.headlights.HeadlightWidgetLayout;
import ru.big.town.restoremode.widgets.media.MediaWidgetLayout;
import ru.big.town.restoremode.widgets.system.SystemWidgetLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Единый список порядка всех элементов главного экрана. Хранится JSON-массивом объектов {type:
 * "split"|"app"|"widget"|"dial", id: <...>} под ключом «tileOrder».
 */
public class TileOrderStore {
    private static final String KEY = "tileOrder";
    private static final String[] PASSENGER_WIDGETS = {
        SystemWidgetLayout.CPU,
        SystemWidgetLayout.RAM,
        SystemWidgetLayout.CLEAR,
        MediaWidgetLayout.MEDIA,
        "tripCard",
        "taskManagerTile"
    };

    public static class Tile {
        public static final String TYPE_SPLIT = "split";
        public static final String TYPE_APP = "app";
        public static final String TYPE_WIDGET = "widget";
        public static final String TYPE_APP_WIDGET = "appWidget";
        public static final String TYPE_DIAL = "dial";
        public static final String TYPE_SCENARIO = "scenario";

        public String type; // "split", "app" или "widget"
        public String id; // id пресета, имя пакета, id виджета или id dial-карточки

        public Tile(String type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    /** Загрузить список плиток, иначе создать дефолтный по существующим Store-ам. */
    public static List<Tile> load(SharedPreferences p) {
        return load(p, p, false);
    }

    /**
     * Loads a screen-scoped order. Splits and scenarios are definitions shared by both screens,
     * so they are read from {@code content}; app widgets, shortcuts and dials come from
     * {@code layout}.
     */
    public static List<Tile> load(
            SharedPreferences layout, SharedPreferences content, boolean passenger) {
        List<Tile> out = new ArrayList<>();
        String stored = layout.getString(KEY, "");

        if (!stored.isEmpty()) {
            // Есть сохранённый порядок — загружаем его
            try {
                JSONArray a = new JSONArray(stored);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String type = o.optString("type", "");
                    String id = o.optString("id", "");
                    if (!type.isEmpty() && !id.isEmpty()) {
                        out.add(new Tile(type, id));
                    }
                }
            } catch (Exception ignored) {
            }
        } else if (passenger) {
            for (String app : AppShortcutStore.load(layout)) {
                out.add(new Tile(Tile.TYPE_APP, app));
            }
            for (String widgetId : PASSENGER_WIDGETS) {
                out.add(new Tile(Tile.TYPE_WIDGET, widgetId));
            }
            for (DialWidgetStore.Entry entry : DialWidgetStore.load(layout)) {
                out.add(new Tile(Tile.TYPE_DIAL, entry.id));
            }
            for (AppWidgetStore.Entry entry : AppWidgetStore.load(layout)) {
                out.add(new Tile(Tile.TYPE_APP_WIDGET, entry.id));
            }
            if (!out.isEmpty()) {
                save(layout, out);
            }
        } else {
            // Первый запуск — мигрируем существующие сплиты и приложения + добавляем виджеты
            List<SplitStore.Preset> splits = SplitStore.load(content);
            for (SplitStore.Preset ps : splits) {
                if (ps.ready()) {
                    out.add(new Tile(Tile.TYPE_SPLIT, ps.id));
                }
            }
            List<String> apps = AppShortcutStore.load(layout);
            for (String app : apps) {
                out.add(new Tile(Tile.TYPE_APP, app));
            }
            // Добавить все известные виджеты по умолчанию
            out.add(new Tile(Tile.TYPE_WIDGET, "tripCard"));
            out.add(new Tile(Tile.TYPE_WIDGET, "cardPowerHold"));
            out.add(new Tile(Tile.TYPE_WIDGET, "cardWashMode"));
            out.add(new Tile(Tile.TYPE_WIDGET, "cardAutoLight"));
            out.add(new Tile(Tile.TYPE_WIDGET, "cardPedestrian"));
            out.add(new Tile(Tile.TYPE_WIDGET, "cardForcedEv"));
            out.add(new Tile(Tile.TYPE_WIDGET, "cardSuspensionMaintenance"));
            out.add(new Tile(Tile.TYPE_WIDGET, "cardBatteryHeat"));
            out.add(new Tile(Tile.TYPE_WIDGET, "suspensionWidget"));
            out.add(new Tile(Tile.TYPE_WIDGET, "cardVoiceCommand"));
            // Native-виджеты (запуск приложений, громкость, запущенные приложения)
            out.add(new Tile(Tile.TYPE_WIDGET, "launchAppsWidget"));
            // Диспетчер задач: 1x1 с числом запущенных приложений.
            out.add(new Tile(Tile.TYPE_WIDGET, "taskManagerTile"));
            // Сохранить миграцию
            if (!out.isEmpty()) {
                save(layout, out);
            }
        }
        return out;
    }

    /** Сохранить список плиток. */
    public static void save(SharedPreferences p, List<Tile> list) {
        JSONArray a = new JSONArray();
        try {
            for (Tile t : list) {
                JSONObject o = new JSONObject();
                o.put("type", t.type);
                o.put("id", t.id);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        p.edit().putString(KEY, a.toString()).apply();
    }

    /**
     * Проверить, существует ли этот элемент (сплит готов к отображению, приложение установлено,
     * виджет включен).
     */
    static boolean exists(SharedPreferences p, Tile t, android.content.pm.PackageManager pm) {
        return exists(p, p, t, pm, false);
    }

    static boolean exists(
            SharedPreferences layout,
            SharedPreferences content,
            Tile t,
            android.content.pm.PackageManager pm,
            boolean passenger) {
        if (Tile.TYPE_SPLIT.equals(t.type)) {
            if (passenger) {
                return false;
            }
            // Проверяем, что сплит с таким id существует и готов
            List<SplitStore.Preset> splits = SplitStore.load(content);
            for (SplitStore.Preset ps : splits) {
                if (ps.id.equals(t.id) && ps.ready()) {
                    return true;
                }
            }
            return false;
        } else if (Tile.TYPE_APP.equals(t.type)) {
            // Ярлык существует, только если приложение установлено и остаётся в списке ярлыков
            // главного экрана
            if (!AppShortcutStore.load(layout).contains(t.id)) {
                return false;
            }
            try {
                pm.getApplicationInfo(t.id, 0);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        } else if (Tile.TYPE_WIDGET.equals(t.type)) {
            // Все известные виджеты считаем существующими (видимость управляется отдельно)
            return isKnownWidget(t.id) && (!passenger || isPassengerWidget(t.id));
        } else if (Tile.TYPE_APP_WIDGET.equals(t.type)) {
            AppWidgetStore.Entry entry = AppWidgetStore.find(layout, t.id);
            if (entry == null) {
                return false;
            }
            try {
                pm.getApplicationInfo(
                        CarPlayTarget.isCarPlay(entry.packageName)
                                ? CarPlayTarget.PACKAGE
                                : entry.packageName,
                        0);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        } else if (Tile.TYPE_DIAL.equals(t.type)) {
            for (DialWidgetStore.Entry entry : DialWidgetStore.load(layout)) {
                if (entry.id.equals(t.id)) {
                    return true;
                }
            }
            return false;
        } else if (Tile.TYPE_SCENARIO.equals(t.type)) {
            if (passenger) {
                return false;
            }
            // Плитка существует, пока сценарий включён и она запрошена в его настройках.
            ScenarioStore.Scenario scenario = ScenarioStore.find(content, t.id);
            return scenario != null && scenario.enabled && scenario.showTile;
        }
        return false;
    }

    /** Проверить, это известный виджет. */
    static boolean isKnownWidget(String widgetId) {
        if (VehicleWidgets.isWidget(widgetId)
                || SystemWidgetLayout.isWidget(widgetId)
                || MediaWidgetLayout.isWidget(widgetId)) {
            return true;
        }
        // Список всех известных виджетов на главном экране
        return widgetId.equals(HeadlightWidgetLayout.ID)
                || widgetId.equals("suspensionWidget")
                || widgetId.equals("tripCard")
                || widgetId.equals("cardPowerHold")
                || widgetId.equals("cardWashMode")
                || widgetId.equals("cardAutoLight")
                || widgetId.equals("cardPedestrian")
                || widgetId.equals("cardForcedEv")
                || widgetId.equals("cardSuspensionMaintenance")
                || widgetId.equals("cardBatteryHeat")
                || widgetId.equals("cardVoiceCommand")
                || widgetId.equals("cardScenarios")
                || widgetId.equals("cardSettings")
                || widgetId.equals("cardAndroidSettings")
                ||
                // Native-виджеты
                widgetId.equals("launchAppsWidget")
                || widgetId.equals("taskManagerTile");
    }

    private static boolean isPassengerWidget(String widgetId) {
        for (String allowed : PASSENGER_WIDGETS) {
            if (allowed.equals(widgetId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Синхронизировать список плиток: удалить несуществующие, добавить новые из SplitStore и
     * AppShortcutStore.
     */
    public static void sync(SharedPreferences p, android.content.pm.PackageManager pm) {
        sync(p, p, pm, false);
    }

    public static void sync(
            SharedPreferences layout,
            SharedPreferences content,
            android.content.pm.PackageManager pm,
            boolean passenger) {
        List<Tile> tiles = load(layout, content, passenger);

        // Миграция прежней одиночной карточки набора в новый список.
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

        // Удалить несуществующие элементы
        for (int i = tiles.size() - 1; i >= 0; i--) {
            if (!exists(layout, content, tiles.get(i), pm, passenger)) {
                tiles.remove(i);
            }
        }

        // Добавить новые сплиты
        if (!passenger) {
            List<SplitStore.Preset> splits = SplitStore.load(content);
            for (SplitStore.Preset ps : splits) {
                if (ps.ready()) {
                    // Проверяем, есть ли он уже в списке плиток
                    boolean found = false;
                    for (Tile t : tiles) {
                        if (Tile.TYPE_SPLIT.equals(t.type) && t.id.equals(ps.id)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        tiles.add(new Tile(Tile.TYPE_SPLIT, ps.id));
                    }
                }
            }
        }

        // Добавить новые приложения
        List<String> apps = AppShortcutStore.load(layout);
        for (String app : apps) {
            // Проверяем, есть ли оно уже в списке плиток
            boolean found = false;
            for (Tile t : tiles) {
                if (Tile.TYPE_APP.equals(t.type) && t.id.equals(app)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                tiles.add(new Tile(Tile.TYPE_APP, app));
            }
        }

        // Добавить известные виджеты, которые ещё не в списке
        String[] knownWidgets =
                passenger
                        ? PASSENGER_WIDGETS
                        : new String[] {
                            HeadlightWidgetLayout.ID,
                            SystemWidgetLayout.CPU,
                            SystemWidgetLayout.RAM,
                            SystemWidgetLayout.CLEAR,
                            MediaWidgetLayout.MEDIA,
                            "energyWidget",
                            "energyConsumptionWidget",
                            "energyTripWidget",
                            "tirePressureWidget",
                            "odometerWidget",
                            "tripCard",
                            "cardPowerHold",
                            "cardWashMode",
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
                            "taskManagerTile"
                        };
        for (String widgetId : knownWidgets) {
            boolean found = false;
            for (Tile t : tiles) {
                if (Tile.TYPE_WIDGET.equals(t.type) && t.id.equals(widgetId)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                tiles.add(new Tile(Tile.TYPE_WIDGET, widgetId));
            }
        }

        // Добавить новые карточки набора в конец, сохранив порядок уже существующих.
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

        // Добавить новые виджеты приложений в конец, сохранив порядок уже существующих.
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

        // Плитки сценариев: добавляются только для включённых сценариев с запрошенной плиткой
        // (несуществующие/выключенные уже удалены выше через exists()).
        if (!passenger) {
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
        }

        save(layout, tiles);
    }
}
