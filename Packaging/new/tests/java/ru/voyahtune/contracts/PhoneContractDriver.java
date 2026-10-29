package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.PhoneModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

final class PhoneContractDriver {
    static boolean nativeHooks;

    private PhoneContractDriver() {}

    static JsonObject execute(String contract, JsonObject input) throws Throwable {
        String failure = input.has("failure") ? input.get("failure").getAsString() : "";
        try (Harness harness = new Harness(failure)) {
            if (contract.equals("agent.installation")) {
                ModuleRuntime.Snapshot status = harness.runtime.snapshot();
                JsonObject result = new JsonObject();
                result.addProperty("state", status.phase.name().toLowerCase().replace('_', '-'));
                result.addProperty("ready", status.phase == ModuleRuntime.Phase.READY);
                result.addProperty("remainingHooks", harness.backend.installed.size());
                return result;
            }
            if (harness.runtime.snapshot().phase != ModuleRuntime.Phase.READY) {
                throw new IllegalStateException("Phone module did not install", harness.runtime.snapshot().failure);
            }
            String number = input.get("number").isJsonNull() ? null : input.get("number").getAsString();
            if (contract.equals("phone.number")) {
                Method amend = Util.class.getDeclaredMethod("getAmendNumber", String.class);
                String normalized = (String) harness.backend.invoke(amend, null, number);
                JsonObject result = new JsonObject();
                result.addProperty("number", normalized);
                result.addProperty("idempotent", normalized.equals(harness.backend.invoke(amend, null, normalized)));
                return result;
            }
            return dial(input, number, harness);
        }
    }

    private static JsonObject dial(JsonObject input, String number, Harness harness) throws Throwable {
        boolean withDevice = input.get("overload").getAsString().equals("device");
        String device = input.has("device") ? input.get("device").getAsString() : null;
        Headset receiver = new Headset();
        if (input.has("originalError")) {
            receiver.failure = new IllegalStateException(input.get("originalError").getAsString());
        }
        Method method = withDevice
                ? Headset.class.getDeclaredMethod("makeCall", String.class, String.class)
                : Headset.class.getDeclaredMethod("makeCall", String.class);
        PrintStream previousOut = System.out;
        PrintStream previousError = System.err;
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        Object value = null;
        Throwable failure = null;
        try (PrintStream capture = new PrintStream(diagnostics, true, StandardCharsets.UTF_8.name())) {
            System.setOut(capture);
            System.setErr(capture);
            try {
                value = withDevice ? harness.backend.invoke(method, receiver, number, device)
                        : harness.backend.invoke(method, receiver, number);
            } catch (Throwable caught) {
                failure = caught;
            } finally {
                System.setOut(previousOut);
                System.setErr(previousError);
            }
        }
        JsonArray calls = new JsonArray();
        for (Call call : receiver.calls) {
            JsonObject record = new JsonObject();
            record.addProperty("number", call.number);
            record.addProperty("device", call.device);
            calls.add(record);
        }
        JsonObject result = new JsonObject();
        result.add("calls", calls);
        if (failure == null) {
            result.addProperty("result", (String) value);
            result.addProperty("receiverPreserved", receiver.calls.stream().allMatch(call -> call.receiver == receiver));
        } else {
            result.addProperty("error", failure.getMessage());
            result.addProperty("sameException", failure == receiver.failure);
        }
        String logged = diagnostics.toString(StandardCharsets.UTF_8.name());
        result.addProperty("loggedSensitive", containsSensitive(logged, number) || containsSensitive(logged, device)
                || receiver.calls.stream().anyMatch(call -> containsSensitive(logged, call.number)));
        return result;
    }

    private static boolean containsSensitive(String logged, String value) {
        return value != null && !value.isEmpty() && logged.contains(value);
    }

    private static final class Harness implements AutoCloseable {
        final Backend backend;
        final HookRegistry hooks;
        final ModuleRuntime runtime;

        Harness(String failure) {
            backend = new Backend(failure);
            hooks = new HookRegistry(backend);
            MethodAccess methods = new MethodAccess(name -> {
                if (name.equals("com.qinggan.bluetoothphone.util.Util")) {
                    return failure.equals("missing-amend") ? MissingAmend.class : Util.class;
                }
                if (name.equals("com.qinggan.bluetoothphone.logic.manager.HeadSetProfileManager")) {
                    if (failure.equals("missing-dial-class")) {
                        throw new ClassNotFoundException(name);
                    }
                    return failure.equals("no-dial-overload") ? MissingDial.class : Headset.class;
                }
                return Class.forName(name);
            });
            runtime = new ModuleRuntime("test-runtime", (delay, task) -> {
                throw new AssertionError("Phone module scheduled unexpected work");
            }, id -> new PhoneModule(methods, hooks));
            runtime.apply("test-runtime", 1, List.of("phone"), Map.of());
        }

        @Override
        public void close() throws Exception {
            runtime.close();
            hooks.close();
        }
    }

    private static final class Backend implements HookRegistry.Backend {
        final Map<Method, HookRegistry.Dispatch> installed = new HashMap<>();
        final String failure;

        Backend(String failure) {
            this.failure = failure;
        }

        @Override
        public Method hook(Executable executable, HookRegistry.Dispatch callback, Method callbackMethod) {
            Method target = (Method) executable;
            if (target.getName().equals("makeCall")
                    && ((failure.equals("install-single") && target.getParameterCount() == 1)
                    || (failure.equals("install-device") && target.getParameterCount() == 2))) {
                return null;
            }
            Method backup = nativeHooks ? HookBridge.hook(target, callback, callbackMethod) : target;
            if (backup != null) {
                installed.put(target, callback);
            }
            return backup;
        }

        @Override
        public boolean unhook(Executable executable) {
            Method target = (Method) executable;
            boolean success = !nativeHooks || HookBridge.unhook(target);
            installed.remove(target);
            return success;
        }

        Object invoke(Method target, Object receiver, Object... arguments) throws Throwable {
            HookRegistry.Dispatch callback = installed.get(target);
            if (callback != null && !nativeHooks) {
                boolean isStatic = Modifier.isStatic(target.getModifiers());
                int offset = isStatic ? 0 : 1;
                Object[] packed = new Object[arguments.length + offset];
                if (!isStatic) {
                    packed[0] = receiver;
                }
                System.arraycopy(arguments, 0, packed, offset, arguments.length);
                return callback.onCall(packed);
            }
            try {
                target.setAccessible(true);
                return target.invoke(receiver, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    private static final class Util {
        private static String getAmendNumber(String number) {
            return "oem-number";
        }
    }

    private static final class MissingAmend {}

    private static final class MissingDial {}

    private static final class Headset {
        final List<Call> calls = new ArrayList<>();
        RuntimeException failure;

        private String makeCall(String number) {
            return record(number, null);
        }

        private String makeCall(String number, String device) {
            return record(number, device);
        }

        private String record(String number, String device) {
            calls.add(new Call(number, device, this));
            if (failure != null) {
                throw failure;
            }
            return "oem-result";
        }
    }

    private static final class Call {
        final String number;
        final String device;
        final Object receiver;

        Call(String number, String device, Object receiver) {
            this.number = number;
            this.device = device;
            this.receiver = receiver;
        }
    }
}
