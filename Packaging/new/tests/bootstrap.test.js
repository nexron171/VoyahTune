"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

(async function () {
    const bootstrap = fs.readFileSync(path.join(__dirname, "../runtime/bootstrap.js"), "utf8");
    for (const directory of ["/data/user/0/oem.launcher/cache", "android", null]) {
        const callbacks = [];
        const factory = {cacheDir: "/data/local/tmp"};
        const javaContext = vm.createContext({
            VOYAH_READY_MARKERS: [], console: {log() {}},
            Java: {
                classFactory: factory,
                perform: callback => callbacks.push(callback),
                use: () => ({currentApplication: () => directory === null ? null : {
                    getPackageName: () => directory === "android" ? "android" : "oem.launcher",
                    getCacheDir: () => ({getAbsolutePath: () => directory})
                }})
            }
        });
        vm.runInContext(bootstrap, javaContext);
        javaContext.Java.perform(() => assert.equal(factory.cacheDir,
            directory === "android" || directory === null ? "/data/local/tmp" : directory));
        callbacks.forEach(callback => callback());
    }
    let originalReady;
    const original = new Promise(resolve => { originalReady = resolve; });
    const context = vm.createContext({VOYAH_READY_MARKERS: ["[one] ready", "[two] ready"], console: {log() {}}, rpc: {exports: {init: () => original}}});
    vm.runInContext(bootstrap + "\nVoyahBootstrap.attach();", context);
    let finished = false;
    const pending = context.rpc.exports.init().then(() => { finished = true; });
    context.console.log("[one] ready");
    originalReady();
    await Promise.resolve();
    assert.equal(finished, false);
    context.console.log("[two] ready");
    await pending;
    assert.equal(finished, true);
    console.log("PASS: app DEX cache precedes hooks; eternalize waits for markers and original async bootstrap");
})().catch(error => { console.error(error); process.exitCode = 1; });
