package ru.big.town.restoremode;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Единый список порядка всех элементов главного экрана.
 * Хранится JSON-массивом объектов {type: "split"|"app"|"widget"|"dial", id: <...>} под ключом «tileOrder».
 */
public class TileOrderStore {
    private static final String KEY = "tileOrder";

    public static class Tile {
        public static final String TYPE_SPLIT = "split";
        public static final String TYPE_APP = "app";
        public static final String TYPE_WIDGET = "widget";
        public static final String TYPE_APP_WIDGET = "appWidget";
        public static final String TYPE_DIAL = "dial";
        
        public String type;      // "split", "app" или "widget"
        public String id;        // id пресета, имя пакета, id виджета или id dial-карточки
        
        public Tile(String type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    /** Загрузить список плиток, иначе создать дефолтный по существующим Store-ам. */
    static List<Tile> load(SharedPreferences p) {
        List<Tile> out = new ArrayList<>();
        String stored = p.getString(KEY, "");
        
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
        } else {
            // Первый запуск — мигрируем существующие сплиты и приложения + добавляем виджеты
            List<SplitStore.Preset> splits = SplitStore.load(p);
            for (SplitStore.Preset ps : splits) {
                if (ps.ready()) {
                    out.add(new Tile(Tile.TYPE_SPLIT, ps.id));
                }
            }
            List<String> apps = AppShortcutStore.load(p);
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
            out.add(new Tile(Tile.TYPE_WIDGET, "cardBatteryHeat"));
            // Native-виджеты (запуск приложений, громкость, запущенные приложения)
            out.add(new Tile(Tile.TYPE_WIDGET, "launchAppsWidget"));
            // Сохранить миграцию
            if (!out.isEmpty()) {
                save(p, out);
            }
        }
        return out;
    }

    /** Сохранить список плиток. */
    static void save(SharedPreferences p, List<Tile> list) {
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

    /** Проверить, существует ли этот элемент (сплит готов к отображению, приложение установлено, виджет включен). */
    static boolean exists(SharedPreferences p, Tile t, android.content.pm.PackageManager pm) {
        if (Tile.TYPE_SPLIT.equals(t.type)) {
            // Проверяем, что сплит с таким id существует и готов
            List<SplitStore.Preset> splits = SplitStore.load(p);
            for (SplitStore.Preset ps : splits) {
                if (ps.id.equals(t.id) && ps.ready()) return true;
            }
            return false;
        } else if (Tile.TYPE_APP.equals(t.type)) {
            // Ярлык существует, только если приложение установлено и остаётся в списке ярлыков главного экрана
            if (!AppShortcutStore.load(p).contains(t.id)) return false;
            try {
                pm.getApplicationInfo(t.id, 0);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        } else if (Tile.TYPE_WIDGET.equals(t.type)) {
            // Все известные виджеты считаем существующими (видимость управляется отдельно)
            return isKnownWidget(t.id);
        } else if (Tile.TYPE_APP_WIDGET.equals(t.type)) {
            AppWidgetStore.Entry entry = AppWidgetStore.find(p, t.id);
            if (entry == null) return false;
            try {
                pm.getApplicationInfo(entry.packageName, 0);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        } else if (Tile.TYPE_DIAL.equals(t.type)) {
            for (DialWidgetStore.Entry entry : DialWidgetStore.load(p)) {
                if (entry.id.equals(t.id)) return true;
            }
            return false;
        }
        return false;
    }

    /** Проверить, это известный виджет. */
    static boolean isKnownWidget(String widgetId) {
        // Список всех известных виджетов на главном экране
         return widgetId.equals("tripCard") ||
               widgetId.equals("cardPowerHold") ||
               widgetId.equals("cardWashMode") ||
               widgetId.equals("cardAutoLight") ||
               widgetId.equals("cardPedestrian") ||
               widgetId.equals("cardForcedEv") ||
             widgetId.equals("cardBatteryHeat") ||
             widgetId.equals("cardSettings") ||
             widgetId.equals("cardAndroidSettings") ||
             // Native-виджеты
             widgetId.equals("launchAppsWidget");
    }

    /** Синхронизировать список плиток: удалить несуществующие, добавить новые из SplitStore и AppShortcutStore. */
    static void sync(SharedPreferences p, android.content.pm.PackageManager pm) {
        List<Tile> tiles = load(p);

        // Миграция прежней одиночной карточки набора в новый список.
        List<DialWidgetStore.Entry> dials = DialWidgetStore.load(p);
        if (dials.isEmpty()) {
            String oldNumber = p.getString("dialWidgetNumber", "");
            if (!oldNumber.isEmpty()) {
                DialWidgetStore.Entry entry = new DialWidgetStore.Entry();
                entry.name = p.getString("dialWidgetContactName", "Набрать номер");
                entry.number = oldNumber;
                dials.add(entry);
                DialWidgetStore.save(p, dials);
            }
        }
        
        // Удалить несуществующие элементы
        for (int i = tiles.size() - 1; i >= 0; i--) {
            if (!exists(p, tiles.get(i), pm)) {
                tiles.remove(i);
            }
        }
        
        // Добавить новые сплиты (если IS_FULL)
        List<SplitStore.Preset> splits = SplitStore.load(p);
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
        
        // Добавить новые приложения
        List<String> apps = AppShortcutStore.load(p);
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
        String[] knownWidgets = {"tripCard", "cardPowerHold", "cardWashMode", "cardAutoLight",
                 "cardPedestrian", "cardForcedEv", "cardBatteryHeat",
                     "cardSettings", "cardAndroidSettings",
                     "launchAppsWidget"};
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
            if (!found) tiles.add(new Tile(Tile.TYPE_DIAL, entry.id));
        }

        // Добавить новые виджеты приложений в конец, сохранив порядок уже существующих.
        for (AppWidgetStore.Entry entry : AppWidgetStore.load(p)) {
            boolean found = false;
            for (Tile tile : tiles) {
                if (Tile.TYPE_APP_WIDGET.equals(tile.type) && tile.id.equals(entry.id)) {
                    found = true;
                    break;
                }
            }
            if (!found) tiles.add(new Tile(Tile.TYPE_APP_WIDGET, entry.id));
        }
        
        save(p, tiles);
    }
}
