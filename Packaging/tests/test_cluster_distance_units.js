'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../od/inject/app_client.js'), 'utf8');

function loadClusterAgent(compatible) {
    const messages = [];
    const distanceUnit = {};
    const application = {getPackageName: () => 'com.qinggan.cluster'};
    const sandbox = {
        console: {log: message => messages.push(message)},
        rpc: {exports: {}},
        Java: {
            perform: callback => callback(),
            use: name => {
                if (name === 'android.app.ActivityThread') {
                    return {currentApplication: () => application};
                }
                if (name === 'com.qinggan.cluster.GuidanceFormat') {
                    if (!compatible) throw new Error('OEM formatter unavailable');
                    return {toDistanceUnit: {overload: (...types) => {
                        assert.deepEqual(types, ['android.content.Context', 'int']);
                        return distanceUnit;
                    }}};
                }
                return {};
            }
        }
    };
    vm.runInNewContext(source, sandbox);
    return {distanceUnit, messages, ready: sandbox.rpc.exports.init()};
}

(async () => {
    const supported = loadClusterAgent(true);
    await supported.ready;
    for (const [meters, expected] of [[0, 'm'], [500, 'm'], [999, 'm'],
        [1000, 'km'], [1500, 'km'], [100000, 'km']]) {
        assert.equal(supported.distanceUnit.implementation(null, meters), expected);
    }
    assert(supported.messages.includes('[cluster-units] hook ready v1'));
    assert(supported.messages.includes('[app-client] hook ready v1'));
    const unsupported = loadClusterAgent(false);
    await unsupported.ready;
    assert.equal(unsupported.distanceUnit.implementation, undefined);
    assert(!unsupported.messages.includes('[app-client] hook ready v1'));
    assert(unsupported.messages.some(message => message.includes('hook unavailable')));
    console.log('Cluster distance units: boundary values, bootstrap, unsupported OEM API passed');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
