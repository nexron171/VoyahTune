"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const root = path.join(__dirname, "..");

function source(name) { return fs.readFileSync(path.join(root, "agents", name + ".js"), "utf8"); }
function production(text, name) {
    const match = new RegExp("^([ ]*)function " + name + "\\(", "m").exec(text);
    assert.ok(match, name);
    const closing = "\n" + match[1] + "}";
    return text.slice(match.index, text.indexOf(closing, match.index) + closing.length);
}

const events = [];
const scheduled = new Map();
const settings = {short: "short-action", long: "long-action"};
const steering = vm.createContext({
    action: name => settings[name], doAction: value => events.push(value), LONG_MS: 600,
    setTimeout: callback => { scheduled.set(1, callback); return 1; },
    clearTimeout: id => scheduled.delete(id)
});
vm.runInContext(production(source("steering"), "pressHandler"), steering);
const press = steering.pressHandler("short", "long");
press.down(0); press.up();
assert.deepEqual(events, ["short-action"]);
assert.equal(scheduled.size, 0);
press.down(0); press.down(1); scheduled.get(1)(); press.up();
assert.deepEqual(events, ["short-action", "long-action"]);
settings.short = settings.long = "none";
assert.equal(press.passthrough(), true);

let stockCalls = 0;
let moveResult = true;
let metadataError = false;
let stockError = false;
const tops = ["org.maps", "com.qinggan.app.launcher"];
const moves = [];
const swap = {call() { stockCalls++; if (stockError) throw Error("OEM swap failure"); return "stock"; }};
const move = {call: (_, ...args) => { moves.push(args); return moveResult; }};
const multidisplay = vm.createContext({
    enabled: true, installedCore: [], NEVER: ["com.qinggan.app.launcher"], ctx: () => ({}),
    safeLog() {}, safeValue: String,
    VoyahAdapters: {optionalClass: () => ({getDpyTopAppInfo(_, display, kind) {
        if (metadataError) throw Error("OEM metadata unavailable");
        return kind === 1 ? tops[display] : "MainActivity";
    }})}
});
vm.runInContext(["packageValue", "isNever", "installSingleAppTransfer"].map(name => production(source("multidisplay"), name)).join("\n"), multidisplay);
multidisplay.installSingleAppTransfer({swap, move});
assert.equal(swap.implementation(), true);
assert.deepEqual(moves, [["org.maps", "MainActivity", false]]);
assert.equal(stockCalls, 0);
tops[1] = "org.music";
assert.equal(swap.implementation(), "stock");
tops[1] = "com.qinggan.app.launcher";
moveResult = false;
assert.equal(swap.implementation(), "stock");
metadataError = true;
assert.equal(swap.implementation(), "stock");
stockError = true;
const before = stockCalls;
assert.throws(() => swap.implementation(), /OEM swap failure/);
assert.equal(stockCalls, before + 1);
stockError = metadataError = false;
multidisplay.enabled = false;
assert.equal(swap.implementation(), "stock");

function apollo(missing) {
    const methods = new Map();
    const properties = new Map();
    const messages = [];
    const classes = new Map();
    const sandbox = {console: {log: message => messages.push(message)}, Java: {
        perform: callback => callback(), use(name) {
            if (name === "android.util.Log") return {i() {}, e() {}};
            if (name === "java.lang.System") return {getProperty: key => properties.get(key) || "", setProperty: (key, value) => properties.set(key, value)};
            if (name === missing) throw Error("missing OEM class");
            if (!classes.has(name)) classes.set(name, new Proxy({}, {get(_, key) {
                const id = name + "." + key;
                if (!methods.has(id)) methods.set(id, {implementation: null});
                return {overload: () => methods.get(id)};
            }}));
            return classes.get(name);
        }
    }};
    vm.runInNewContext(source("apollo"), sandbox);
    return {methods, messages};
}
const active = apollo();
assert.equal(active.methods.size, 9);
assert.ok([...active.methods.values()].every(method => typeof method.implementation === "function"));
assert.ok(active.messages.some(message => message.includes("hook ready")));
const failed = apollo("com.qinggan.app.vehiclesetting.fragments.driveassistance.DriveAssistantData");
assert.ok([...failed.methods.values()].every(method => method.implementation === null));
assert.ok(failed.messages.some(message => message.includes("hook failed")));
assert.ok(!failed.messages.some(message => message.includes("hook ready")));
console.log("PASS: steering short/long presses, single-app transfer with OEM fallback, Apollo ready/rollback");
