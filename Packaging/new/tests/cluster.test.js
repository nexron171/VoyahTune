"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const root = path.join(__dirname, "..");
const source = ["runtime/adapters.js", "runtime/bootstrap.js", "agents/cluster-navigation.js"]
    .map(name => fs.readFileSync(path.join(root, name), "utf8")).join("\n");

function fixture(compatible) {
    const distanceUnit = {};
    const messages = [];
    const sandbox = vm.createContext({
        VOYAH_READY_MARKERS: ["[cluster] hook ready v1"],
        console: {log: message => messages.push(message)}, rpc: {exports: {}},
        Java: {perform: callback => callback(), use(name) {
            if (name === "android.app.ActivityThread") return {currentApplication: () => ({getPackageName: () => "com.qinggan.cluster"})};
            if (name === "com.qinggan.cluster.GuidanceFormat" && compatible) return {toDistanceUnit: {
                overload(...signature) { assert.deepEqual(signature, ["android.content.Context", "int"]); return distanceUnit; }
            }};
            throw new Error("unsupported " + name);
        }}
    });
    vm.runInContext(source + "\nVoyahBootstrap.attach();", sandbox);
    return {distanceUnit, messages, ready: sandbox.rpc.exports.init()};
}

(async function () {
    const supported = fixture(true);
    await supported.ready;
    for (const [meters, unit] of [[0, "m"], [999, "m"], [1000, "km"], [100000, "km"]]) {
        assert.equal(supported.distanceUnit.implementation(null, meters), unit);
    }
    assert.ok(supported.messages.includes("[cluster] hook ready v1 card=unsupported"));
    const unsupported = fixture(false);
    let ready = false;
    unsupported.ready.then(() => { ready = true; });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(ready, false);
    assert.ok(!unsupported.messages.some(message => message.includes("hook ready")));
    console.log("PASS: cluster units remain available without NAVI_FULL; unsupported ABI never reports ready");
})().catch(error => { console.error(error); process.exitCode = 1; });
