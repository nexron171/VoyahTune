// Host model for the actual agent. No Android, Frida, ADB or network.
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function fixture(options = {}) {
    let now = 0, seq = 0;
    const timers = new Map(), classes = new Map(), methods = new Map(), files = new Map();
    const events = [], registered = new Set();
    const stat = Array(20).fill('0'); stat[0] = 'S'; stat[19] = '1';
    files.set('/proc/self/stat', '104 (system_server) ' + stat.join(' '));
    files.set('/proc/sys/kernel/random/boot_id', 'test');
    function method(name, original = () => undefined) {
        if (methods.has(name)) return methods.get(name);
        let impl = null;
        const m = (...args) => original(...args);
        m.overload = () => {
            if (options.missingMethod === name) throw new Error('unsupported overload');
            return m;
        };
        m.call = (self, ...args) => original.apply(self, args);
        Object.defineProperty(m, 'implementation', {
            get: () => impl,
            set: value => {
                events.push(['replacement', name, value !== null]);
                if (value && options.failInstall === name) throw new Error('install failed');
                if (!value && options.failRollback === name) throw new Error('rollback failed');
                impl = value;
            }
        });
        methods.set(name, m); return m;
    }
    const context = {
        getPackageManager: () => now < (options.readyAt || 0) ? null : {
            getPackageUid: () => options.uid ?? 10064
        },
        getContentResolver: () => ({}),
        getSystemService: () => ({ isInteractive: () => options.screenOn !== false }),
        unregisterReceiver: receiver => {
            events.push(['unregister', receiver.$name]);
            registered.delete(receiver);
        }
    };
    context.registerReceiver = method('context.registerReceiver', function (receiver) {
        registered.add(receiver);
        events.push(['register', receiver.$name]);
        if (options.failReceiver === receiver.$name) throw new Error('receiver failed');
        return null;
    });
    function field(name) {
        return { setAccessible() {}, get: obj => obj[name], getInt: obj => obj[name].value,
            setInt(obj, value) { obj[name].value = value; } };
    }
    const specials = {
        'android.app.ActivityThread': { currentActivityThread: () => ({ getSystemContext: () => context }) },
        'android.os.SystemClock': { elapsedRealtime: () => now },
        'android.os.Process': { myPid: () => 104 },
        'java.lang.Thread': { getAllStackTraces: () => ({ keySet: () => ({ iterator: () => {
            let done = false;
            return { hasNext: () => !done, next: () => { done = true;
                return { getName: () => 'main', getContextClassLoader: () => ({}) };
            } };
        } }) }) },
        'android.util.Log': Object.fromEntries(['i','w','e'].map(level => [level,
            (_, message) => events.push(['log', level, message])])),
        'android.provider.Settings$Global': { getString: (_, key) => {
            events.push(['setting', key]);
            if (options.failSetting === key) throw new Error('settings unavailable');
            return (options.settings || {})[key] ?? null;
        } },
        'android.os.SystemProperties': { getInt: () => options.liftType ?? 2 },
        'java.io.FileReader': { $new: name => ({ name }) },
        'java.io.BufferedReader': { $new: reader => ({
            readLine: () => files.get(reader.name), close() {}
        }) },
        'java.io.FileWriter': { $new: name => ({
            write: { overload: () => ({ call: (_, line) => files.set(name, line.trim()) }) }, close() {}
        }) },
        'java.io.File': { $new: name => ({ name, renameTo: target => {
            if (options.failStatus) return false;
            files.set(target.name, files.get(name)); files.delete(name); return true;
        } }) },
        'com.android.server.LocalServices': { getService: () => ({}) },
        'android.content.IntentFilter': { $new: () => ({ addAction() {} }) },
        'android.content.res.Configuration': { $new: current => ({ densityDpi: { value: current.densityDpi.value } }) }
    };
    function use(name) {
        if (name === options.missingClass) throw new Error('java.lang.ClassNotFoundException');
        if (specials[name]) return specials[name];
        if (!classes.has(name)) classes.set(name, new Proxy({
            class: { getDeclaredField: field },
            $new: () => ({})
        }, { get(target, prop) {
            if (prop in target) return target[prop];
            const original = prop === 'requestTraversalFromDisplayManager'
                ? () => events.push(['traversal']) : () => true;
            return method(name + '.' + String(prop), original);
        } }));
        return classes.get(name);
    }
    const sandbox = {
        console, Java: { use, classFactory: { loader: null }, cast: obj => obj, retain: obj => obj,
            perform: fn => fn(), registerClass: definition => ({ $new: () => ({
                $name: definition.name, onReceive: definition.methods.onReceive.implementation
            }) }) },
        setTimeout: (fn, delay) => { const id = ++seq; timers.set(id, { at: now + delay, fn }); return id; },
        clearTimeout: id => timers.delete(id), Date: { now: () => now }
    };
    let source = fs.readFileSync(path.join(__dirname, '../inject/vd_bypass.js'), 'utf8');
    // Export local functions only in this host evaluation; shipped source exposes nothing.
    source = source.replace('    } // installAgent', `
        globalThis.api = { refreshFreeformCfg, ffDpiFor, ffApplyTaskDpi,
            get cfg() { return FF; }, scheduleFreeformHotAttach, scheduleFreeformConfigReplay };
    } // installAgent`);
    vm.runInNewContext(source, sandbox, { timeout: 2000 });
    function advance(ms) {
        const until = now + ms;
        for (let guard = 0; guard < 500; guard++) {
            const next = [...timers.entries()].sort((a,b) => a[1].at-b[1].at)[0];
            if (!next || next[1].at > until) { now = until; return; }
            now = next[1].at; timers.delete(next[0]); next[1].fn();
        }
        throw new Error('timer loop');
    }
    return { options, events, methods, files, registered, timers, advance,
        get api() { return sandbox.api; },
        status: () => files.get('/data/local/open_voyah/vd_hooks/status.v1'),
        receive(name, action, extras = {}) {
            const receiver = [...registered].find(r => r.$name.endsWith(name));
            if (!receiver) throw new Error('missing receiver ' + name);
            receiver.onReceive(context, { getAction: () => action, getIntExtra: (_, fallback) => extras.type ?? fallback });
        }
    };
}
module.exports = { fixture };
