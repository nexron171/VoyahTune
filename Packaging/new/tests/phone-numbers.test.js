'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const profiles = ['agents/phone-numbers.js'];
const readyMarker = '[phone-numbers] hook ready v1';
const cases = [
    [null, ''],
    ['', ''],
    [' \t\n', ''],
    ['+7 (999) 123-45-67', '+79991234567'],
    ['7 (999) 123-45-67', '+79991234567'],
    ['+375 (29) 123-45-67', '+375291234567'],
    ['375 (29) 123-45-67', '+375291234567'],
    ['+375 (17) 123-45-67', '+375171234567'],
    ['375 (17) 123-45-67', '+375171234567'],
    ['8 (999) 123-45-67', '89991234567'],
    ['8 (029) 123-45-67', '80291234567'],
    ['+36 (52) 123-456', '+3652123456'],
    ['+86 138-1234-5678', '+8613812345678'],
    ['8613812345678', '8613812345678'],
    ['+44 20 1234 5678', '+442012345678'],
    ['112', '112'],
    ['103', '103'],
    ['375', '375'],
    ['7999123456', '7999123456'],
    ['799912345678', '799912345678'],
    ['37529123456', '37529123456'],
    ['3752912345678', '3752912345678'],
    ['*100#', '*100#'],
    ['*#06#', '*#06#'],
    ['*21*79991234567#', '*21*79991234567#'],
    ['375291234567#', '375291234567#'],
    ['79991234567,123', '+79991234567,123'],
    ['375291234567;123#', '+375291234567;123#'],
    ['79991234567p12w34', '+79991234567p12w34'],
    ['375291234567P12W34', '+375291234567P12W34'],
    ['+375291234567,12;34', '+375291234567,12;34'],
    ['89991234567,123', '89991234567,123'],
    ['79991234567ext123', '79991234567ext123'],
    ['00375291234567', '00375291234567']
];

function loadAgent(source, options = {}) {
    const messages = [];
    const calls = [];
    const methods = [];
    const originalResult = {};
    let performCallback;
    let rejectedInstall = false;

    function createMethod(name) {
        const previous = function () {};
        let implementation = previous;
        const method = {
            previous,
            call(receiver, ...argumentsList) {
                calls.push({name, receiver, argumentsList});
                if (options.originalError) throw options.originalError;
                return originalResult;
            },
            get implementation() {
                return implementation;
            },
            set implementation(value) {
                if (options.rejectInstall === name && !rejectedInstall) {
                    rejectedInstall = true;
                    throw new Error('Replacement rejected');
                }
                implementation = value;
            }
        };
        methods.push(method);
        return method;
    }

    const amend = createMethod('amend');
    const single = createMethod('single');
    const device = createMethod('device');
    const classes = {
        'com.qinggan.bluetoothphone.util.Util': {
            getAmendNumber: {
                overload(...types) {
                    assert.deepEqual(types, ['java.lang.String']);
                    if (options.missingAmend) throw new Error('Unsupported helper');
                    return amend;
                }
            }
        },
        'com.qinggan.bluetoothphone.logic.manager.HeadSetProfileManager': {
            makeCall: {
                overload(...types) {
                    assert.ok(types.every(type => type === 'java.lang.String'));
                    if (types.length === 1 && !options.missingSingle) return single;
                    if (types.length === 2 && !options.missingDevice) return device;
                    throw new Error('Unsupported makeCall overload');
                }
            }
        }
    };
    const rpc = {};
    vm.runInNewContext(source, {
        rpc,
        console: {log: message => messages.push(message)},
        Java: {
            perform(callback) {
                performCallback = callback;
                if (!options.deferred) callback();
            },
            use(name) {
                // Any PBAP sync or dependency outside the dial ABI fails this test.
                assert.ok(classes[name], `Unexpected Java dependency: ${name}`);
                if (options.missingClass === name) throw new Error('Unsupported OEM class');
                return classes[name];
            }
        }
    });
    return {
        amend, single, device, methods, messages, calls, originalResult, rpc,
        finishBootstrap: () => performCallback()
    };
}

async function main() {
    for (const profile of profiles) {
        const source = fs.readFileSync(path.join(__dirname, '..', profile), 'utf8');
        const agent = loadAgent(source);
        await agent.rpc.exports.init();
        assert.deepEqual(agent.messages, [readyMarker]);
        const receiver = {};
        const deviceAddress = 'test-bluetooth-device';

        for (const [input, expected] of cases) {
            assert.equal(agent.amend.implementation(input), expected, `${profile}: ${input}`);
            assert.equal(agent.amend.implementation(expected), expected, 'Normalization is idempotent');
            assert.equal(agent.single.implementation.call(receiver, input), agent.originalResult);
            assert.equal(agent.device.implementation.call(receiver, input, deviceAddress), agent.originalResult);
            const [singleCall, deviceCall] = agent.calls.slice(-2);
            assert.equal(singleCall.receiver, receiver);
            assert.equal(deviceCall.receiver, receiver);
            assert.deepEqual(singleCall.argumentsList, [expected]);
            assert.deepEqual(deviceCall.argumentsList, [expected, deviceAddress]);
        }
        assert.equal(agent.calls.length, cases.length * 2);
        assert.deepEqual(agent.messages, [readyMarker], 'Numbers and device addresses stay out of logs');

        for (const options of [
            {missingAmend: true},
            {missingClass: 'com.qinggan.bluetoothphone.logic.manager.HeadSetProfileManager'},
            {missingSingle: true, missingDevice: true},
            {rejectInstall: 'single'},
            {rejectInstall: 'device'}
        ]) {
            const failed = loadAgent(source, options);
            await failed.rpc.exports.init();
            assert.ok(!failed.messages.includes(readyMarker));
            assert.ok(failed.messages.some(message => message.startsWith('[phone-numbers] hook failed v1:')));
            for (const method of failed.methods) assert.equal(method.implementation, method.previous);
        }

        for (const missing of ['missingSingle', 'missingDevice']) {
            const partialAbi = loadAgent(source, {[missing]: true});
            assert.deepEqual(partialAbi.messages, [readyMarker]);
            const supported = missing === 'missingSingle' ? partialAbi.device : partialAbi.single;
            supported.implementation('375291234567', deviceAddress);
            assert.equal(partialAbi.calls[0].argumentsList[0], '+375291234567');
        }

        const originalError = new Error('OEM call failed');
        const throwing = loadAgent(source, {originalError});
        assert.throws(() => throwing.single.implementation('79991234567'), error => error === originalError);
        assert.equal(throwing.calls.length, 1);

        const delayed = loadAgent(source, {deferred: true});
        let bootstrapFinished = false;
        const ready = delayed.rpc.exports.init().then(() => { bootstrapFinished = true; });
        await Promise.resolve();
        assert.equal(bootstrapFinished, false);
        assert.deepEqual(delayed.messages, []);
        delayed.finishBootstrap();
        await ready;
        assert.equal(bootstrapFinished, true);
        assert.deepEqual(delayed.messages, [readyMarker]);
        console.log(`PASS: ${profile}, ${cases.length} numbers, dial delegation, ABI rollback and bootstrap`);
    }
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
