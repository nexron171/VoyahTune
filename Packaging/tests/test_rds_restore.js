"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const source = fs.readFileSync(path.join(__dirname, "../od/inject/app_client.js"), "utf8");
const context = {Java: {perform() {}}, console};
vm.runInNewContext(source, context);
const fm = (freq, pi = 0) => ({fm: true, freq, pi, rds: pi > 0, ta: false});
const am = freq => ({fm: false, freq, pi: 0, rds: false, ta: false});
const original = fm(10170, 1234);
const reset = fm(8750);
const plain = value => JSON.parse(JSON.stringify(value));
let count = 0;
function test(name, fn) { fn(); count++; console.log("OK " + name); }

function fixture(saved = original) {
    const f = {time: 0, current: reset, ready: true, timers: [], writes: [], tunes: [],
        focus: true, focusRequests: 0, staged: [], playing: false, plays: 0, fallbacks: 0, logs: [], owned: null, diskFail: false};
    f.tick = duration => {
        const end = f.time + duration;
        while (f.timers.some(t => t.at <= end)) {
            f.timers.sort((a, b) => a.at - b.at);
            const t = f.timers.shift(); f.time = t.at; t.fn();
        }
        f.time = end;
    };
    f.controller = context.createRdsRestoreController({
        now: () => f.time, current: () => f.current, ready: () => f.ready,
        acquireFocus: () => { f.focusRequests++; return f.focus; }, hasFocus: () => f.focus,
        isPlaying: () => f.playing, stageSelected: s => f.staged.push(plain(s)), clearSelected: () => {},
        later: (fn, delay) => f.timers.push({fn, at: f.time + delay}),
        save: s => { if (f.diskFail) return false; f.writes.push(plain(s)); return true; },
        tune: s => { f.owned = plain(s); f.tunes.push(plain(s)); if (f.onTune) f.onTune(); },
        playCurrent: () => { f.plays++; },
        cancelOwned: () => { f.owned = null; }, releaseOwned: () => { f.owned = null; },
        log: text => f.logs.push(text)
    }, saved);
    f.observe = s => { f.current = s; f.controller.observe(s); };
    f.resume = () => f.controller.resume(() => { f.fallbacks++; });
    return f;
}

test("startup reset cannot replace durable selection", () => {
    const f = fixture(); f.observe(reset); f.tick(1000);
    assert.equal(f.writes.length, 0); assert.equal(f.tunes.length, 0);
    f.resume(); assert.deepEqual(f.tunes, [original]);
    assert.equal(f.logs.some(s => s.includes("confirmed")), false);
    f.observe(original); f.tick(1000);
    assert.equal(f.logs.some(s => s.includes("confirmed")), true);
});
test("first install learns a settled station without playing", () => {
    const f = fixture(null); f.observe(original); f.tick(749);
    assert.equal(f.writes.length, 0); f.tick(1);
    assert.deepEqual(f.writes, [original]); assert.equal(f.tunes.length, 0);
});
test("explicit choice only saves a matching confirmation", () => {
    const f = fixture(); const chosen = fm(10230);
    f.controller.select(chosen); f.observe(reset); f.tick(1000);
    assert.equal(f.writes.length, 0);
    f.observe(chosen); f.tick(750); assert.deepEqual(f.writes, [chosen]);
    f.current = reset; f.resume(); assert.deepEqual(f.tunes, [chosen]);
});
test("new choice cancels stale writes and delayed restoration", () => {
    const f = fixture(); f.ready = false; f.resume();
    f.controller.select(fm(9800)); f.ready = true; f.observe(fm(9800)); f.tick(500);
    f.controller.select(fm(9900)); f.observe(fm(9900)); f.tick(1000);
    assert.deepEqual(f.writes, [fm(9900)]); assert.equal(f.tunes.length, 0);
});
test("pause cancels queued tune even after it has been submitted", () => {
    const f = fixture(); f.resume(); assert.deepEqual(f.owned, original);
    f.controller.pause(); f.tick(12000);
    assert.equal(f.owned, null); assert.equal(f.tunes.length, 1);
});
test("readiness is awaited without extending the bounded deadline", () => {
    const f = fixture(); f.ready = false; f.resume(); f.tick(9000); f.resume(); f.tick(1000);
    assert.equal(f.tunes.length, 0); f.ready = true; f.tick(1000);
    assert.equal(f.tunes.length, 0);
    assert.equal(f.logs.some(s => s.includes("timed out")), true);
});
test("late readiness submits once; lack of confirmation never means success", () => {
    const f = fixture(); f.ready = false; f.resume(); f.tick(1000);
    f.ready = true; f.tick(9000);
    assert.equal(f.tunes.length, 1); assert.equal(f.owned, null);
    assert.equal(f.logs.some(s => s.includes("confirmed")), false);
    assert.equal(f.writes.length, 0);
});
test("matching station resumes audio without retuning", () => {
    const f = fixture(); f.current = original; f.resume();
    assert.equal(f.plays, 1); assert.equal(f.tunes.length, 0);
});
test("stale RDS PI after wake cannot overwrite the saved frequency", () => {
    const f = fixture(fm(10210, 30489));
    f.observe(fm(10210, 30489)); f.tick(80);
    f.observe(reset); f.observe(fm(8750, 30489)); f.tick(1000);
    assert.equal(f.writes.length, 0);
    f.resume(); assert.deepEqual(f.tunes, [fm(10210, 30489)]);
    assert.equal(f.logs.some(s => s.includes("restore confirmed")), false);
    f.observe(fm(10210)); f.tick(750);
    assert.equal(f.logs.some(s => s.includes("restore confirmed 10210")), true);
});
test("unrequested AF frequency cannot replace the user's durable frequency by PI alone", () => {
    const f = fixture(); f.observe(fm(10230, 1234)); f.tick(750);
    assert.equal(f.writes.length, 0);
});
test("a stale PI cannot confirm a manual choice at a different frequency", () => {
    const f = fixture(); f.controller.select(fm(10210, 30489));
    f.observe(fm(8750, 30489)); f.tick(1000);
    assert.equal(f.writes.length, 0);
});
test("traffic announcement cannot overwrite the station", () => {
    const f = fixture(); f.observe({...fm(10230, 1234), ta: true}); f.tick(1000);
    assert.equal(f.writes.length, 0);
});
test("different station callback during playback is not a new selection", () => {
    const f = fixture(); f.observe(fm(10230, 4321)); f.tick(1000);
    assert.equal(f.writes.length, 0); assert.equal(f.tunes.length, 0);
});
test("scan cancels restore, suppresses intermediate frequencies and learns the result", () => {
    const f = fixture(); f.resume(); f.controller.scan(); f.ready = false;
    f.observe(fm(9000)); f.tick(1000); assert.equal(f.writes.length, 0);
    f.current = fm(10420); f.ready = true; f.controller.scanFinished(); f.tick(750);
    assert.deepEqual(f.writes, [fm(10420)]); assert.equal(f.owned, null);
});
test("failed persistence retains old durable snapshot and retries on a new observation", () => {
    const f = fixture(); f.controller.select(fm(9900)); f.diskFail = true;
    f.observe(fm(9900)); f.tick(750); assert.equal(f.writes.length, 0);
    f.diskFail = false; f.observe(fm(9900)); f.tick(750);
    assert.deepEqual(f.writes, [fm(9900)]);
});
test("invalid saved record falls back to OEM playback", () => {
    for (const bad of [null, {}, fm(-1), fm(101.7), {...original, fm: "true"}, {...original, pi: -1}]) {
        const f = fixture(bad); f.resume(); assert.equal(f.fallbacks, 1);
        assert.equal(f.tunes.length, 0);
    }
});
test("AM range and station are kept distinct from FM", () => {
    const f = fixture(am(999)); f.resume(); assert.deepEqual(f.tunes, [am(999)]);
    f.observe(am(999)); f.tick(1000);
    assert.equal(f.logs.some(s => s.includes("confirmed")), true);
});
test("synchronous confirmation during dispatch does not leave a retry timer", () => {
    const f = fixture(); f.onTune = () => f.observe(original); f.resume(); f.tick(15000);
    assert.equal(f.tunes.length, 1); assert.equal(f.logs.some(s => s.includes("timed out")), false);
});
test("close invalidates every delayed task", () => {
    const f = fixture(null); f.observe(original); f.controller.close(); f.tick(20000);
    assert.equal(f.writes.length, 0); assert.equal(f.tunes.length, 0);
});

// Execute the real Frida adapter against a small RdsApp facade, including nested OEM calls.
function adapterFixture(options = {}) {
    const f = {current: options.current ?? reset, prefs: options.raw ?? JSON.stringify({schema: 1, station: original}),
        timers: [], time: 0, ready: true, calls: [], logs: [], writes: [], methods: [], fields: {}, nextId: 1,
        mainTasks: [], focusRequests: 0, playing: options.playing, cycle: options.cycle ?? 1, acc: options.acc ?? 2, displays: []};
    function method(name, fn) {
        const m = {implementation: null, overload() { return m; },
            call(self, ...args) { return fn.apply(self, args); },
            invoke(self, ...args) { return (m.implementation || fn).apply(self, args); }};
        if (options.failInstall === name) {
            Object.defineProperty(m, "implementation", {get: () => null,
                set: value => { if (value !== null) throw Error("replacement rejected"); }});
        }
        f.methods.push(m); f.fields[name] = m; return m;
    }
    function station(s) {
        if (!s) return null;
        const value = {...s}; const id = f.nextId++;
        return {getFreq: () => value.freq, getPICode: () => value.pi, isRDS: () => value.rds,
            isFMStation: () => value.fm, isTAAssert: () => value.ta,
            setIsFMStation: v => { value.fm = v; }, setIsRDS: v => { value.rds = v; },
            setPICode: v => { value.pi = v; }, $dispose() {}, equals: other => other && other.id === id,
            id, value};
    }
    const mgr = {
        getCurStation: () => station(f.current), isInit: () => f.ready,
        isRdsSource: () => f.radioSource !== false, isPlay: () => f.playing !== false,
        getScanState: () => ({name: () => "FINISH"}),
        delayPlayStation: {value: null}, delayPlay: {value: true}, tempPlayStation: {value: null}, actionCode: {value: 0},
        handler: {value: {removeMessages: code => f.calls.push(["remove", code])}}
    };
    const Rds = {
        getInstance: () => mgr,
        requestAudioFocus: method("focus", () => { f.focusRequests++; return true; }),
        playStation: method("playStation", function (s) {
            f.calls.push(["tune", {...s.value}]);
            // Reproduce the OEM AM typo; the hook must correct it only for restore calls.
            Rds.switchRadioSource.invoke(mgr, "FM");
            mgr.delayPlayStation.value = s;
            mgr.tempPlayStation.value = s; mgr.actionCode.value = 1002;
        }),
        play: method("play", () => { f.calls.push(["play"]); if (options.playThrows) throw Error("OEM failure"); }),
        resumePlay: method("resume", () => f.calls.push(["resume"])),
        pause: method("pause", () => f.calls.push(["pause"])),
        pauseNoAbandon: method("pauseNoAbandon", () => f.calls.push(["pauseNoAbandon"])),
        fullScan: method("scan", () => f.calls.push(["scan"])),
        switchRadioSource: method("switch", band => f.calls.push(["band", band])),
        initData: method("init", () => {})
    };
    const modelListener = {};
    const Model = {
        getInstance: () => ({rdsInfoListener: {value: modelListener}}),
        getCurRadioStation: method("modelCurrent", () => station(f.current)),
        getCurStation: method("modelPrivateCurrent", () => station(f.current)),
        play: method("modelPlay", function () { Rds.playStation.invoke(mgr, station(f.current)); })};
    const Info = {
        onCurrentStationUpdate: method("update", s => { f.current = {...s.value}; }),
        onFreqSeekStateChanged: method("scanState", () => {})
    };
    const classes = {
        "android.net.Uri": {parse: s => s},
        "android.content.ContentResolver": {call: method("provider", () => ({
            getInt: k => k === "protocol" ? 2 : f.acc, getLong: () => f.cycle}))},
        "android.content.BroadcastReceiver": {},
        "android.content.IntentFilter": {$new: action => ({action})},
        "android.os.Handler": {$new: () => ({})},
        "android.os.Looper": {getMainLooper: () => ({})},
        "com.pateo.rdsapp.main.model.RdsDataModel$1": {onCurrentStationUpdate:
            method("modelUpdate", s => f.displays.push({...s.value}))},
        "android.util.Log": {i: (tag, text) => f.logs.push(text)},
        "com.pateo.overSideRadio.base.dab.RdsManager": Rds,
        "com.pateo.rdsapp.main.model.RdsDataModel": Model,
        "com.pateo.overSideRadio.base.dab.RdsManager$3": Info,
        "com.pateo.overSideRadio.base.dab.RdsManager$4": {onAudioPlayStatus: method("audioStatus", () => {})},
        "com.adayo.proxy.dab.aidl.beans.RadioStation": {$new: freq => station(fm(freq))},
        "com.adayo.proxy.dab.DABConstants$BAND_DEF": {FM: {value: "FM"}, MW: {value: "MW"}},
        "android.os.SystemClock": {elapsedRealtime: () => f.time},
        "com.qinggan.media.helper.AudioPolicyHelper": {getInstance: () => ({isCall: () => false, isCurMediaFocus: () => true})},
        "com.qinggan.media.helper.MediaEnum": {RDS: {value: "RDS"}}
    };
    const app = {
        getContentResolver: () => ({}),
        registerReceiver: {overload: () => ({call: (app, receiver, filter, permission) => {
            f.receiver = receiver; f.receiverPermission = permission; f.receiverAction = filter.action;
        }})},
        unregisterReceiver: () => { f.receiver = null; },
        getSharedPreferences: () => ({
        getString: () => f.prefs,
        edit: () => ({putString(key, value) { this.value = value; return this; },
            commit() { f.prefs = this.value; f.writes.push(JSON.parse(this.value)); return true; }})
    })};
    let loading = true;
    const sandbox = {
        rpc: {exports: {}},
        console: {log: text => f.logs.push(text)},
        setTimeout: (fn, delay) => f.timers.push({fn, at: f.time + delay}),
        Java: {
            perform: fn => { if (!loading) fn(); },
            cast: x => x,
            registerClass: def => ({$new: () => ({receive: def.methods.onReceive[0].implementation})}),
            scheduleOnMainThread: fn => options.deferredBootstrap ? f.mainTasks.push(fn) : fn(), retain: x => x,
            use: name => { if (name === options.missing) throw Error("ClassNotFoundException");
                if (!classes[name]) throw Error("unexpected class " + name); return classes[name]; }
        }
    };
    vm.runInNewContext(source, sandbox); loading = false;
    if (options.deferredBootstrap) sandbox.scheduleRdsStationRestore(app);
    else sandbox.installRdsStationRestore(app);
    f.readyPromise = sandbox.rpc.exports.init();
    f.completeBootstrap = sandbox.appClientBootstrapResolve;
    f.flushMain = () => { while (f.mainTasks.length) f.mainTasks.shift()(); };
    f.tick = ms => {
        const end = f.time + ms;
        while (f.timers.some(t => t.at <= end)) {
            f.timers.sort((a,b) => a.at-b.at);
            const t = f.timers.shift(); f.time = t.at; t.fn();
        }
        f.time = end;
    };
    f.invoke = (name, ...args) => f.fields[name].invoke(mgr, ...args);
    f.update = s => f.invoke("update", station(s));
    f.choose = s => f.invoke("playStation", station(s));
    f.manager = mgr;
    f.restoreAcc = () => f.receiver.receive();
    return f;
}

test("adapter UI play restores saved station rather than saving OEM default", () => {
    const f = adapterFixture(); f.invoke("modelPlay");
    assert.deepEqual(f.calls.filter(c => c[0] === "tune"), [["tune", fm(original.freq)]]);
    f.update(original); f.tick(750);
    assert.equal(f.writes.length, 0);
});
test("adapter manual selection wins and persists in one schema record", () => {
    const f = adapterFixture(); f.choose(fm(10620)); f.update(fm(10620)); f.tick(750);
    assert.deepEqual(f.writes, [{schema: 1, station: fm(10620)}]);
});
test("adapter pause clears only the owned OEM pending fields", () => {
    const f = adapterFixture(); f.invoke("resume"); f.invoke("pause");
    assert.equal(f.manager.delayPlayStation.value, null);
    assert.equal(f.manager.tempPlayStation.value, null);
    assert.equal(f.manager.actionCode.value, -1);
    assert.ok(f.calls.some(c => c[0] === "remove" && c[1] === 1002));
});
test("adapter new user tune is not cleared by an expired old timer", () => {
    const f = adapterFixture(); f.invoke("resume"); f.choose(fm(10620));
    const next = f.manager.delayPlayStation.value;
    f.tick(15000); assert.equal(f.manager.delayPlayStation.value, next);
});
test("adapter corrects OEM AM source typo for restore only", () => {
    const f = adapterFixture({raw: JSON.stringify({schema: 1, station: am(999)})});
    f.invoke("resume"); assert.ok(f.calls.some(c => c[0] === "band" && c[1] === "MW"));
});
test("adapter corrupt/unknown schema keeps OEM behavior", () => {
    for (const raw of ["{bad", JSON.stringify({schema: 2, station: original})]) {
        const f = adapterFixture({raw}); f.invoke("play");
        assert.deepEqual(f.calls, [["play"]]);
    }
});
test("unsupported OEM class installs no replacements and announces no readiness", () => {
    const f = adapterFixture({missing: "com.pateo.overSideRadio.base.dab.RdsManager$3"});
    assert.ok(f.methods.every(m => m.implementation === null));
    assert.ok(!f.logs.some(s => s.includes("hook ready")));
    f.invoke("play"); assert.deepEqual(f.calls, [["play"]]);
});
test("another source taking focus while tuner starts cancels restore", () => {
    const f = fixture(); f.ready = false; f.resume(); f.tick(500);
    f.focus = false; f.tick(250); f.ready = true; f.tick(12000);
    assert.equal(f.tunes.length, 0);
});
test("denied audio focus never sends a tuning command", () => {
    const f = fixture(); f.focus = false; f.resume(); f.tick(12000);
    assert.equal(f.tunes.length, 0); assert.equal(f.plays, 0);
});
test("repeated identical metadata does not postpone the durable write forever", () => {
    const f = fixture(null);
    for (let i = 0; i < 10; i++) { f.observe(original); f.tick(100); }
    assert.deepEqual(f.writes, [original]);
});
test("owned delayed tune replay is still restoration, not a user selection", () => {
    const f = adapterFixture(); f.invoke("resume");
    f.invoke("playStation", f.manager.delayPlayStation.value);
    f.update(original); f.tick(750);
    assert.ok(f.logs.some(s => s.includes("restore confirmed")));
    assert.ok(!f.logs.some(s => s === "explicit station choice"));
});
test("OEM automatic radio playback restores after wake without a UI play press", () => {
    const f = adapterFixture(); assert.equal(f.calls.length, 0);
    f.invoke("audioStatus", false);
    assert.deepEqual(f.calls.filter(c => c[0] === "tune"), [["tune", fm(original.freq)]]);
});
test("paused OEM wake reset preserves the selected station until UI playback", () => {
    const chosen = fm(10210, 30489);
    const f = adapterFixture({current: chosen, raw: JSON.stringify({schema: 1, station: chosen})});
    f.playing = false;
    f.invoke("init"); f.tick(80);
    f.update(reset); f.update(fm(8750, 30489)); f.tick(1000);
    assert.equal(f.calls.length, 0);
    assert.equal(f.writes.length, 0);
    assert.deepEqual(JSON.parse(f.prefs).station, chosen);
    f.invoke("modelPlay");
    assert.deepEqual(f.calls.filter(c => c[0] === "tune"), [["tune", fm(10210)]]);
    assert.ok(!f.logs.some(s => s.includes("restore confirmed")));
    f.update(fm(10210, 30489)); f.tick(750);
    assert.ok(f.logs.some(s => s.includes("restore confirmed 10210")));
});
test("OEM source other than radio never triggers automatic restore", () => {
    const f = adapterFixture(); f.radioSource = false; f.invoke("audioStatus", false);
    assert.equal(f.calls.length, 0);
});
test("matching automatic playback does not send a redundant play command", () => {
    const f = adapterFixture({current: original}); f.invoke("audioStatus", false);
    assert.equal(f.calls.length, 0);
});
test("pausing immediately after confirmed manual tune still saves the station", () => {
    const f = fixture(); f.controller.select(fm(9900)); f.observe(fm(9900));
    f.controller.pause(); f.tick(750); assert.deepEqual(f.writes, [fm(9900)]);
});
test("scan with no callback cannot disable restoration forever", () => {
    const f = fixture(); f.controller.scan(); f.controller.pause(); f.tick(60000); f.resume();
    assert.deepEqual(f.tunes, [original]);
});
test("transient OEM pause during source switch does not discard the queued tune", () => {
    const f = adapterFixture(); f.invoke("resume");
    const owned = f.manager.delayPlayStation.value; f.invoke("audioStatus", true);
    assert.equal(f.manager.delayPlayStation.value, owned);
    f.update(original); f.tick(750);
    assert.ok(f.logs.some(s => s.includes("restore confirmed")));
});
test("unknown future schema is preserved without installing hooks or overwriting data", () => {
    const raw = JSON.stringify({schema: 2, station: original});
    const f = adapterFixture({raw}); f.tick(2000);
    assert.equal(f.prefs, raw); assert.equal(f.writes.length, 0);
    assert.ok(f.methods.every(m => m.implementation === null));
});
test("failed replacement rolls back earlier hooks and reports no readiness", () => {
    const f = adapterFixture({failInstall: "audioStatus"});
    assert.ok(f.methods.every(m => m.implementation === null));
    assert.ok(!f.logs.some(s => s.includes("hook ready")));
});
test("OEM play exception is propagated without a duplicate original call", () => {
    const f = adapterFixture({current: original, playThrows: true});
    assert.throws(() => f.invoke("play"), /OEM failure/);
    assert.deepEqual(f.calls, [["play"]]);
});
test("a new request after suspend does not wait for an overdue timer", () => {
    const f = fixture(); f.ready = false; f.resume();
    f.time = 20000; f.ready = true; f.resume();
    assert.deepEqual(f.tunes, [original]);
});
(async function () {
    for (const fail of [false, true]) {
        const f = adapterFixture({deferredBootstrap: true,
            missing: fail ? "com.pateo.overSideRadio.base.dab.RdsManager$3" : undefined});
        let finished = false;
        f.readyPromise.then(() => { finished = true; });
        f.completeBootstrap();
        await Promise.resolve(); await Promise.resolve();
        assert.equal(finished, false, "injector must wait for the actual main-thread install");
        f.flushMain();
        await f.readyPromise;
        assert.equal(finished, true);
        assert.equal(f.logs.some(s => s.includes("[rds-restore] hook ready")), !fail);
        console.log("OK RPC init waits for delayed RDS " + (fail ? "failure" : "readiness"));
        count++;
    }
    console.log("RDS restore: " + count + " tests passed");
})().catch(error => { console.error(error); process.exitCode = 1; });

test("silent ACC stages the choice without focus, playback, tuner writes or false feedback", () => {
    const f = fixture(); assert.equal(f.controller.restoreSilent(1), true);
    assert.deepEqual(f.staged, [original]); assert.deepEqual(f.current, reset);
    f.tick(15000);
    assert.equal(f.focusRequests, 0); assert.equal(f.plays, 0); assert.equal(f.tunes.length, 0);
    assert.equal(f.writes.length, 0);
    f.controller.restoreSilent(1); assert.equal(f.staged.length, 1);
    f.resume(); assert.deepEqual(f.tunes, [original]);
});
test("Native follow-up cannot overwrite a choice made after early ACC", () => {
    const f = fixture(); f.controller.restoreSilent(1);
    f.controller.select(fm(9870)); f.observe(fm(9870)); f.tick(750);
    f.controller.restoreSilent(1); assert.equal(f.staged.length, 1);
    f.controller.restoreSilent(2); assert.deepEqual(f.staged[1], fm(9870));
});
test("silent restore leaves active playback, scans and pending user selections alone", () => {
    for (const busy of [f => {f.playing = true;}, f => f.controller.scan(),
            f => f.controller.select(fm(9990)), f => f.resume()]) {
        const f = fixture(); busy(f); f.controller.restoreSilent(1);
        assert.equal(f.staged.length, 0);
    }
});
test("adapter late attach and Native broadcast silently restore the display once per ACC", () => {
    const f = adapterFixture({playing: false});
    assert.equal(f.receiverPermission, "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE");
    assert.equal(f.receiverAction, "ru.big.town.anative.RESTORE_RADIO_SELECTION");
    assert.equal(f.calls.length, 0); assert.equal(f.focusRequests, 0); assert.equal(f.current.freq, reset.freq);
    assert.equal(f.invoke("modelCurrent").value.freq, original.freq);
    assert.equal(f.displays.length, 1); f.restoreAcc(); assert.equal(f.displays.length, 1);
    f.update(reset); assert.equal(f.displays.length, 1); assert.equal(f.writes.length, 0);
    f.invoke("modelPlay"); assert.equal(f.calls.filter(c => c[0] === "tune").length, 1);
});
test("adapter sleep does not stage and later ACC preserves a fresh manual choice", () => {
    const f = adapterFixture({playing: false, acc: 0}); assert.equal(f.displays.length, 0);
    f.acc = 2; f.cycle++; f.restoreAcc(); assert.equal(f.displays.length, 1);
    f.choose(fm(9900)); f.update(fm(9900)); f.tick(750); f.restoreAcc();
    assert.equal(f.invoke("modelCurrent").value.freq, 9900);
    assert.equal(f.calls.filter(c => c[0] === "tune").length, 1);
});
console.log("PASS: " + count + " RDS restore scenarios");
