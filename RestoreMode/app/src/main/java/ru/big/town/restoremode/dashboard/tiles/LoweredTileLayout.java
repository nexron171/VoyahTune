package ru.big.town.restoremode.dashboard.tiles;

import android.content.SharedPreferences;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Independent geometry over the physical screen's current tile definitions. */
public final class LoweredTileLayout {
    public static final String PREFERENCE_KEY = "loweredTileLayout";
    public static final int FULL_ROWS = 5;

    private final SharedPreferences preferences;
    private final int rows;
    private final List<TileOrderStore.Tile> tiles = new ArrayList<>();
    private final Map<String, Size> sizes = new LinkedHashMap<>();
    private final Map<String, Integer> contentRows = new LinkedHashMap<>();

    public LoweredTileLayout(
            SharedPreferences preferences,
            List<TileOrderStore.Tile> currentTiles,
            int rows,
            Function<TileOrderStore.Tile, int[]> standardSize) {
        this.preferences = preferences;
        this.rows = Math.max(1, Math.min(FULL_ROWS, rows));
        Map<String, TileOrderStore.Tile> remaining = new LinkedHashMap<>();
        for (TileOrderStore.Tile tile : currentTiles) {
            remaining.put(key(tile), tile);
        }
        for (JSONObject saved : readSavedTiles()) {
            String key = saved.optString("type") + ":" + saved.optString("id");
            TileOrderStore.Tile tile = remaining.remove(key);
            if (tile != null) {
                add(
                        tile,
                        saved.optInt("columns"),
                        saved.optInt("rows"),
                        saved.optInt("contentRows", saved.optInt("rows")));
            }
        }
        for (TileOrderStore.Tile tile : remaining.values()) {
            int[] size = standardSize.apply(tile);
            add(tile, size[0], size[1], size[1]);
        }
    }

    private List<JSONObject> readSavedTiles() {
        List<JSONObject> result = new ArrayList<>();
        try {
            JSONArray entries = new JSONArray(preferences.getString(PREFERENCE_KEY, "[]"));
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry != null
                        && entry.optInt("columns") >= 1
                        && entry.optInt("columns") <= 12
                        && entry.optInt("rows") >= 1
                        && entry.optInt("rows") <= FULL_ROWS) {
                    result.add(entry);
                }
            }
        } catch (JSONException ignored) {
            // Invalid saved geometry falls back to the current raised layout.
        }
        return result;
    }

    private void add(TileOrderStore.Tile tile, int columns, int height, int contentHeight) {
        tiles.add(tile);
        sizes.put(
                key(tile),
                new Size(Math.max(1, Math.min(12, columns)), Math.max(1, Math.min(rows, height))));
        contentRows.put(
                key(tile),
                supportedContentRows(
                        tile, columns, sizes.get(key(tile)).getHeight(), contentHeight));
    }

    public List<TileOrderStore.Tile> tiles() {
        return new ArrayList<>(tiles);
    }

    public int[] dimensions(TileOrderStore.Tile tile) {
        Size size = sizes.get(key(tile));
        return new int[] {size.getWidth(), size.getHeight()};
    }

    public int[] contentDimensions(TileOrderStore.Tile tile) {
        int[] size = dimensions(tile);
        size[1] = contentRows.get(key(tile));
        return size;
    }

    public void move(int from, int before) {
        if (from < 0 || from >= tiles.size()) {
            return;
        }
        TileOrderStore.Tile moved = tiles.remove(from);
        int destination = Math.max(0, Math.min(before > from ? before - 1 : before, tiles.size()));
        tiles.add(destination, moved);
        if (destination != from) {
            save();
        }
    }

    public void resize(TileOrderStore.Tile tile, int columns, int height) {
        if (!sizes.containsKey(key(tile))
                || columns < 1
                || columns > 12
                || height < 1
                || height > rows) {
            return;
        }
        Size previous = sizes.put(key(tile), new Size(columns, height));
        if (previous.getWidth() != columns || previous.getHeight() != height) {
            int contentHeight =
                    previous.getHeight() == height ? contentRows.get(key(tile)) : height;
            contentRows.put(key(tile), supportedContentRows(tile, columns, height, contentHeight));
            save();
        }
    }

    private static int supportedContentRows(
            TileOrderStore.Tile tile, int columns, int height, int preferredHeight) {
        int contentHeight = Math.max(height, Math.min(FULL_ROWS, preferredHeight));
        if (!TileOrderStore.Tile.TYPE_WIDGET.equals(tile.type)
                || !TileSizeStore.isResizable(tile.id)
                || TileSizeStore.supportsSize(tile.id, columns, contentHeight)) {
            return contentHeight;
        }
        for (int candidate = height; candidate <= FULL_ROWS; candidate++) {
            if (TileSizeStore.supportsSize(tile.id, columns, candidate)) {
                return candidate;
            }
        }
        return contentHeight;
    }

    public boolean supportsWidgetSize(String widgetId, int columns, int height) {
        if (height < 1 || height > rows) {
            return false;
        }
        int maximum = height == rows ? FULL_ROWS : height;
        for (int fullHeight = height; fullHeight <= maximum; fullHeight++) {
            if (TileSizeStore.supportsSize(widgetId, columns, fullHeight)) {
                return true;
            }
        }
        return false;
    }

    public void save() {
        JSONArray entries = new JSONArray();
        try {
            for (TileOrderStore.Tile tile : tiles) {
                Size size = sizes.get(key(tile));
                JSONObject entry = new JSONObject();
                entry.put("type", tile.type);
                entry.put("id", tile.id);
                entry.put("columns", size.getWidth());
                entry.put("rows", size.getHeight());
                entry.put("contentRows", contentRows.get(key(tile)));
                entries.put(entry);
            }
        } catch (JSONException error) {
            throw new IllegalStateException("Cannot serialize lowered tile layout", error);
        }
        preferences.edit().putString(PREFERENCE_KEY, entries.toString()).apply();
    }

    private static String key(TileOrderStore.Tile tile) {
        return tile.type + ":" + tile.id;
    }
}
