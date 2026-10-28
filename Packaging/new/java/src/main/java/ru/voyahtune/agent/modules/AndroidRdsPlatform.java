package ru.voyahtune.agent.modules;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.RadioRestoreController;
import ru.voyahtune.agent.policy.RadioStation;
import ru.voyahtune.agent.policy.RadioWidgetController;
import ru.voyahtune.agent.runtime.AndroidMainThread;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

public final class AndroidRdsPlatform implements RdsModule.Platform {
    private static final String RESTORE = "ru.big.town.anative.RESTORE_RADIO_SELECTION";
    private static final String PERMISSION = "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
    final Context context;
    final MethodAccess methods;
    final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private Object manager;
    private Object audio;
    private Object mediaRds;
    private volatile RadioStation record;
    private volatile Object selected;
    private RdsTuneOwnership tuning;
    private RdsModule.Originals originals;
    private boolean presentationChanged;
    private final List<Runnable> startupActions = new ArrayList<>();

    public AndroidRdsPlatform(Context context, MethodAccess methods) {
        if (context == null) throw new IllegalArgumentException("Application context required for RDS");
        this.context = context;
        this.methods = methods;
    }

    @Override public void prepare() throws Exception {
        AndroidMainThread.run(() -> {
            manager = OemAccess.call(methods.type(RdsModule.MANAGER), "getInstance");
            audio = OemAccess.call(methods.type("com.qinggan.media.helper.AudioPolicyHelper"), "getInstance", context);
            mediaRds = OemAccess.read(methods.type("com.qinggan.media.helper.MediaEnum"), "RDS");
            for (String field : List.of("delayPlayStation", "delayPlay", "tempPlayStation", "actionCode", "handler")) OemAccess.read(manager, field);
            current();
            ready();
            preferences = context.getSharedPreferences("voyahtune_rds_restore", Context.MODE_PRIVATE);
            String raw = preferences.getString("station", null);
            if (raw == null) return;
            Object parsed;
            try { parsed = new JSONTokener(raw).nextValue(); }
            catch (JSONException malformed) { error(malformed); return; }
            if (parsed == JSONObject.NULL) return;
            if (!(parsed instanceof JSONObject) || !Integer.valueOf(1).equals(integer(((JSONObject) parsed).opt("schema")))) {
                throw new IllegalStateException("Unsupported saved radio schema");
            }
            Object station = ((JSONObject) parsed).opt("station");
            record = station instanceof JSONObject ? decode((JSONObject) station) : null;
        });
    }

    @Override public RadioStation saved() { return record; }

    private static RadioStation decode(JSONObject station) {
        Object fm = station.opt("fm");
        Object rds = station.opt("rds");
        Object traffic = station.opt("ta");
        return new RadioStation(fm instanceof Boolean ? (Boolean) fm : null, integer(station.opt("freq")), rds instanceof Boolean ? (Boolean) rds : null,
                integer(station.opt("pi")), traffic != null && traffic != JSONObject.NULL && !Boolean.FALSE.equals(traffic), station.optString("name", null));
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Number)) return null;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number == Math.rint(number) && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE ? (int) number : null;
    }

    private static JSONObject encode(RadioStation station) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("fm", station.fm);
        value.put("freq", station.frequency);
        value.put("rds", station.rds);
        value.put("pi", station.programmeId);
        value.put("ta", station.trafficAnnouncement);
        if (station.name != null) value.put("name", station.name);
        return value;
    }

    @Override public RadioStation snapshot(Object station) throws Exception {
        if (station == null) return null;
        String name = null;
        try {
            Object label = OemAccess.call(station, "getPSName");
            if (label != null && !label.toString().trim().isEmpty()) name = label.toString().trim();
        } catch (Exception absent) { /* A station name is optional OEM metadata. */ }
        return new RadioStation((Boolean) OemAccess.call(station, "isFMStation"), (Integer) OemAccess.call(station, "getFreq"),
                (Boolean) OemAccess.call(station, "isRDS"), (Integer) OemAccess.call(station, "getPICode"), (Boolean) OemAccess.call(station, "isTAAssert"), name);
    }

    RadioStation current() throws Exception { return snapshot(OemAccess.call(manager, "getCurStation")); }
    boolean ready() throws Exception {
        RadioStation station = current();
        return Boolean.TRUE.equals(OemAccess.call(manager, "isInit")) && "FINISH".equals(String.valueOf(OemAccess.call(manager, "getScanState")))
                && (station == null || !station.trafficAnnouncement);
    }
    boolean hasFocus() throws Exception { return !Boolean.TRUE.equals(OemAccess.call(audio, "isCall")) && Boolean.TRUE.equals(OemAccess.call(audio, "isCurMediaFocus", mediaRds)); }
    boolean playing() throws Exception { return hasFocus() && Boolean.TRUE.equals(OemAccess.call(manager, "isRdsSource")) && Boolean.TRUE.equals(OemAccess.call(manager, "isPlay")); }
    Object manager() { return manager; }
    @Override public Object selectedStation() { return selected; }
    @Override public boolean ownedStation(Object station) { return tuning.owns(station); }
    @Override public boolean hasOwnedStation() { return tuning.hasOwned(); }
    @Override public RadioStation dispatching() { return tuning.dispatching(); }
    @Override public Object band(boolean fm) throws Exception { return OemAccess.read(methods.type(RdsModule.BAND), fm ? "FM" : "MW"); }
    @Override public long playbackCalls() { return tuning.playbackCalls(); }
    @Override public void beginDispatch(RadioStation station) { tuning.beginDispatch(station); }
    @Override public void finishDispatch() throws Exception { tuning.finishDispatch(); }

    private void selected(RadioStation station) throws Exception {
        selected = tuning.command(station);
        presentationChanged = true;
        Object model = OemAccess.call(methods.type(RdsModule.MODEL), "getInstance");
        originals.call("model-update", OemAccess.read(model, "rdsInfoListener"), selected);
    }

    @Override public RadioRestoreController.IO restoration(ModuleScope scope, RdsModule.Originals originals) throws Exception {
        this.originals = originals;
        tuning = new RdsTuneOwnership(manager, methods.type(RdsModule.STATION), originals);
        return new RadioRestoreController.IO() {
            @Override public long now() { return SystemClock.elapsedRealtime(); }
            @Override public void later(long delay, Runnable action) { schedule(scope, delay, action); }
            @Override public RadioStation current() throws Exception { return AndroidRdsPlatform.this.current(); }
            @Override public boolean ready() throws Exception { return AndroidRdsPlatform.this.ready(); }
            @Override public boolean hasFocus() throws Exception { return AndroidRdsPlatform.this.hasFocus(); }
            @Override public boolean isPlaying() throws Exception { return playing(); }
            @Override public boolean acquireFocus() throws Exception {
                tuning.resumeDelay(!Boolean.TRUE.equals(OemAccess.call(manager, "isInit")) || !"FINISH".equals(String.valueOf(OemAccess.call(manager, "getScanState"))));
                OemAccess.call(manager, "requestAudioFocus");
                return hasFocus();
            }
            @Override public boolean save(RadioStation station) throws Exception {
                JSONObject value = new JSONObject();
                value.put("schema", 1); value.put("station", encode(station));
                boolean committed = preferences.edit().putString("station", value.toString()).commit();
                if (committed) record = station;
                return committed;
            }
            @Override public void tune(RadioStation station) throws Exception { tuning.tune(station); }
            @Override public void playCurrent() throws Exception { tuning.playCurrent(); }
            @Override public void stageSelected(RadioStation station) throws Exception { selected(station); }
            @Override public void clearSelected() { selected = null; }
            @Override public void cancelOwned() throws Exception { tuning.cancel(); }
            @Override public void releaseOwned() throws Exception { tuning.release(); }
            @Override public void log(String event) { Log.i("vt_rds", event); }
            @Override public void error(Exception failure) { AndroidRdsPlatform.this.error(failure); }
        };
    }

    void schedule(ModuleScope scope, long delay, Runnable action) {
        try {
            scope.schedule(delay, () -> {
                try { postMain(scope.listener(action)); }
                catch (Exception unavailable) { error(unavailable); }
            });
        } catch (Exception unavailable) { throw new IllegalStateException("Radio timer unavailable", unavailable); }
    }

    @Override public void postMain(Runnable action) {
        if (!main.post(action)) throw new IllegalStateException("Radio main looper rejected work");
    }

    @Override public void bridges(ModuleScope scope, HookRegistry hooks, RadioRestoreController controller, RdsModule.Originals originals) throws Exception {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !RESTORE.equals(intent.getAction())) return;
                scope.listener(() -> {
                    try { restoreAccSelection(controller); }
                    catch (Exception unavailable) { error(unavailable); }
                }).run();
            }
        };
        scope.own(() -> {
            try { context.unregisterReceiver(receiver); }
            catch (IllegalArgumentException absent) { /* A failed registration can leave no local receiver. */ }
        });
        IntentFilter filter = new IntentFilter(RESTORE);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, PERMISSION, main, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter, PERMISSION, main);
        AndroidRadioMediaBridge.install(this, scope, hooks, widget(scope, controller));
    }

    RadioWidgetController widget(ModuleScope scope, RadioRestoreController controller) {
        return new RadioWidgetController(new RadioWidgetController.IO() {
            @Override public RadioStation saved() { return record; }
            @Override public RadioStation current() throws Exception { return AndroidRdsPlatform.this.current(); }
            @Override public boolean ready() throws Exception { return AndroidRdsPlatform.this.ready() && !Boolean.TRUE.equals(OemAccess.call(audio, "isCall")); }
            @Override public boolean isPlaying() throws Exception { return playing(); }
            @Override public List<RadioStation> list() throws Exception {
                List<RadioStation> stations = new ArrayList<>();
                Object available = OemAccess.call(manager, "getPlayList");
                if (available instanceof List<?>) for (Object station : (List<?>) available) stations.add(snapshot(station));
                return stations;
            }
            @Override public void pause() throws Exception { OemAccess.call(manager, "pause"); }
            @Override public void tune(RadioStation target) throws Exception {
                controller.select(target);
                tuning.tune(target);
                Object submitted = tuning.owned();
                schedule(scope, 6000, () -> {
                    if (tuning.owned() != submitted) return;
                    try { tuning.cancel(); } catch (Exception unavailable) { error(unavailable); }
                });
                controller.observe(current());
            }
        });
    }

    @Override public void restoreAccSelection(RadioRestoreController controller) {
        Bundle snapshot = AndroidDriveSettings.hook(context.getContentResolver(), "snapshot", null);
        if (snapshot.getInt("acc", -1) == 2) {
            try { controller.restoreSilent(snapshot.getLong("cycle", -1)); }
            catch (Exception unavailable) { error(unavailable); }
        }
    }

    @Override public void cleanup() throws Exception {
        if (tuning != null) tuning.close();
        selected = null;
        startupActions.clear();
        if (presentationChanged && originals != null) AndroidMainThread.run(() -> {
            Object model = OemAccess.call(methods.type(RdsModule.MODEL), "getInstance");
            originals.call("model-update", OemAccess.read(model, "rdsInfoListener"), OemAccess.call(manager, "getCurStation"));
            presentationChanged = false;
        });
    }

    void onStartup(Runnable action) { startupActions.add(action); }
    @Override public void startup() { for (Runnable action : new ArrayList<>(startupActions)) action.run(); }

    @Override public void error(Exception failure) { Log.e("vt_rds", "Radio restoration unavailable", failure); }
}
