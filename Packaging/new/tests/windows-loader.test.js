"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../adapters/windows/system-services.js"), "utf8");

function fixture(defaultReady, loaders) {
    const factory = { loader: "default" };
    let enumerations = 0;
    const context = vm.createContext({
        Java: {
            classFactory: factory,
            use(name) {
                assert.equal(name, "com.android.server.wm.ActivityTaskManagerService");
                if (!defaultReady) throw new Error("ClassNotFoundException");
                return {};
            },
            enumerateClassLoadersSync() {
                enumerations++;
                return loaders;
            },
            ClassFactory: {
                get(loader) {
                    return { use() {
                        if (!loader.services) throw new Error("ClassNotFoundException");
                        return {};
                    } };
                }
            }
        }
    });
    vm.runInContext(source, context);
    return { adapter: context.VoyahWindowAdapters, factory, enumerations: () => enumerations };
}

test("keeps a working default class loader", () => {
    const state = fixture(true, []);
    state.adapter.selectClassLoader();
    assert.equal(state.factory.loader, "default");
    assert.equal(state.enumerations(), 0);
});

test("selects the loader that resolves system services", () => {
    const services = { services: true };
    const state = fixture(false, [{ services: false }, services]);
    state.adapter.selectClassLoader();
    assert.equal(state.factory.loader, services);
});

test("leaves the loader unchanged while services are unavailable and permits retry", () => {
    const loaders = [{ services: false }];
    const state = fixture(false, loaders);
    assert.throws(() => state.adapter.selectClassLoader(), /not ready/);
    assert.equal(state.factory.loader, "default");
    const services = { services: true };
    loaders.push(services);
    state.adapter.selectClassLoader();
    assert.equal(state.factory.loader, services);
});
