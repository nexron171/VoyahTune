"use strict";

// Reuse behavioral fixtures while redirecting every production read to Packaging/new/.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const Module = require("node:module");
const solutionRoot = path.resolve(__dirname, "..");
const packagingRoot = path.dirname(solutionRoot);
const fixtureDirectory = path.join(packagingRoot, "tests");
const adapters = fs.readFileSync(path.join(solutionRoot, "runtime/adapters.js"), "utf8");
const fixtures = [
    "test_power_hold_bridge.js", "test_dock_navigation_actions.js", "test_carplay_dock.js",
    "test_od_carplay_chrome.js", "test_rds_restore.js", "test_od_carplay_statusbar.js",
    "test_cluster_navigation.js", "test_navigation_sources.js", "test_vd_stability.js",
    "test_carplay_memory_protection.js"
];

for (const fixture of fixtures) {
    const reads = new Set();
    const mappings = {
        "od/inject/voyahtune_drive_reset.js": "agents/drive-reset.js",
        "od/inject/vd_bypass.js": "agents/windows.js",
        "od/inject/launcherdock.js": "adapters/launcher/split-navigation.js",
        "pi/inject/launcherdock.js": "adapters/launcher/shared-navigation.js",
        "od/inject/app_client.js": fixture === "test_cluster_navigation.js"
            ? "agents/cluster-navigation.js" : "agents/app-client.js",
        "pi/inject/clusternavi.js": fixture === "test_navigation_sources.js"
            ? "agents/app-client.js" : "agents/cluster-navigation.js"
    };
    const redirectedFS = Object.create(fs);
    redirectedFS.readFileSync = function (filename, ...args) {
        const relative = path.relative(packagingRoot, filename);
        if (!mappings[relative]) throw new Error("Unmapped production read: " + filename);
        reads.add(relative);
        const source = fs.readFileSync(path.join(solutionRoot, mappings[relative]), ...args);
        return relative.endsWith("vd_bypass.js") ? adapters + "\n" + source : source;
    };
    function evaluate(filename) {
        let source = fs.readFileSync(filename, "utf8");
        source = source.replaceAll("/data/local/open_voyah/vd_hooks/", "/data/local/open_voyah/new/vd_hooks/");
        if (fixture === "test_navigation_sources.js") {
            source = source.replace("script.indexOf('function installClusterNavigation() {')", "script.length");
        }
        const testModule = new Module(filename, module);
        testModule.filename = filename;
        testModule.paths = Module._nodeModulePaths(path.dirname(filename));
        const originalRequire = testModule.require.bind(testModule);
        testModule.require = name => {
            if (name === "node:fs" || name === "fs") return redirectedFS;
            if (name === "./vd_fixture") return evaluate(path.join(fixtureDirectory, "vd_fixture.js"));
            return originalRequire(name);
        };
        testModule._compile(source, filename);
        return testModule.exports;
    }
    evaluate(path.join(fixtureDirectory, fixture));
    assert.ok(reads.size > 0, "fixture did not evaluate a new production source: " + fixture);
    console.log("PASS unified fixture: " + fixture);
}
