package ru.big.town.restoremode;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Пресеты «Разделения экрана» в DrivePreferences ("splitPresets", JSON-массив). */
public class SplitStore {

    static final String KEY = "splitPresets";
    // 0=3:4, 1=1:1, 2=4:3, 3=5:2, 4=2:5
    static final String[] RATIO_LABELS = {"3:4", "1:1", "4:3", "5:2", "2:5"};

    public static class Preset {
        public String l = "", ll = "", r = "", rl = "";
        public int ratio = 1;
        public boolean ready() { return !l.isEmpty() && !r.isEmpty(); }
    }

    static List<Preset> load(SharedPreferences p) {
        List<Preset> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Preset ps = new Preset();
                ps.l  = o.optString("l", "");
                ps.ll = o.optString("ll", "");
                ps.r  = o.optString("r", "");
                ps.rl = o.optString("rl", "");
                ps.ratio = o.optInt("ratio", 1);
                out.add(ps);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void save(SharedPreferences p, List<Preset> list) {
        JSONArray a = new JSONArray();
        try {
            for (Preset ps : list) {
                JSONObject o = new JSONObject();
                o.put("l", ps.l);
                o.put("ll", ps.ll);
                o.put("r", ps.r);
                o.put("rl", ps.rl);
                o.put("ratio", ps.ratio);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        p.edit().putString(KEY, a.toString()).apply();
    }
}
