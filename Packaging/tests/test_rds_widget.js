"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const context = {Java: {perform() {}}, console};
vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../od/inject/app_client.js"), "utf8"), context);
const station = (freq, fm = true, name = "") => ({freq, fm, name, rds: false, pi: 0, ta: false});
function fixture() {
    const state = {
        saved: station(10210, true, "Монте-Карло"), current: station(8750, true, "Старое имя"),
        ready: true, playing: false, list: [station(9900), station(10210), station(10620)], tunes: [], pauses: 0
    };
    state.command = context.createRdsWidgetController({
        saved: () => state.saved, current: () => state.current, ready: () => state.ready,
        playing: () => state.playing, list: () => state.list,
        tune: selected => state.tunes.push(selected), pause: () => {state.pauses++;}
    });
    return state;
}
let count = 0;
function test(name, run) { run(); count++; console.log("OK " + name); }

test("paused widget shows the saved station instead of the wake default", () => {
    const state = fixture();
    const result = state.command("snapshot");
    assert.equal(result.freq, 10210);
    assert.equal(result.name, "Монте-Карло");
    assert.equal(result.playing, false);
    assert.equal(state.tunes.length, 0);
});
test("play tunes the displayed frequency even when current OEM selection differs", () => {
    const state = fixture();
    const response = state.command("play", station(10620));
    assert.equal(response.accepted, true);
    assert.equal(response.requestedFreq, 10620);
    assert.equal(state.tunes[0].freq, 10620);
    assert.equal(state.tunes[0].rds, false);
    assert.equal(state.saved.freq, 10210);
    assert.equal(response.playing, false, "sending is not playback confirmation");
});
test("next and previous follow the OEM list with wraparound", () => {
    const state = fixture();
    assert.equal(state.command("next", station(10210)).requestedFreq, 10620);
    assert.equal(state.command("next", station(10620)).requestedFreq, 9900);
    assert.equal(state.command("previous", station(9900)).requestedFreq, 10620);
});
test("missing station chooses the nearest frequency in the requested direction", () => {
    const state = fixture();
    assert.equal(state.command("next", station(10000)).requestedFreq, 10210);
    assert.equal(state.command("previous", station(10000)).requestedFreq, 9900);
    assert.equal(state.command("next", station(10700)).requestedFreq, 9900);
});
test("a different band and temporary TA stations are not next-station candidates", () => {
    const state = fixture();
    state.list = [station(10210), station(10000, false), {...station(10620), ta: true}];
    assert.equal(state.command("snapshot").canSkip, false);
    assert.equal(state.command("next", station(10210)).accepted, false);
    assert.equal(state.tunes.length, 0);
});
test("pause is idempotent and does not tune or clear the saved station", () => {
    const state = fixture(); state.ready = false;
    state.command("pause");
    assert.equal(state.pauses, 1);
    assert.equal(state.tunes.length, 0);
    assert.equal(state.saved.freq, 10210);
});
test("unready radio and invalid frequency never cause tuning", () => {
    const state = fixture(); state.ready = false;
    assert.equal(state.command("play", station(10210)).accepted, false);
    state.ready = true;
    for (const value of [null, station(-1), station(10801), station(999999, false), station(10210.5)]) {
        assert.equal(state.command("play", value).accepted, false);
    }
    assert.equal(state.tunes.length, 0);
});
test("AM uses the raw AM frequency and does not turn into FM", () => {
    const state = fixture();
    const result = state.command("play", station(999, false));
    assert.equal(result.requestedFm, false);
    assert.equal(state.tunes[0].freq, 999);
});
test("late RDS names apply only to the same saved frequency", () => {
    const state = fixture();
    state.current = station(10210, true, "MONTE CARLO");
    assert.equal(state.command("snapshot").name, "MONTE CARLO");
    state.current = station(8750, true, "Неверное имя");
    assert.equal(state.command("snapshot").name, "Монте-Карло");
});
test("no saved station does not invent a display frequency from transient OEM data", () => {
    const state = fixture(); state.saved = null;
    assert.equal(state.command("snapshot").freq, -1);
    assert.equal(state.command("snapshot").name, "");
});
console.log("RDS widget: " + count + " tests passed");
