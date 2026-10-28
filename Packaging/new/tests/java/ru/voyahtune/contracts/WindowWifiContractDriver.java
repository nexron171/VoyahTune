package ru.voyahtune.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ru.voyahtune.agent.policy.WifiScanLease;

final class WindowWifiContractDriver {
    static JsonObject execute(JsonObject input) throws Exception {
        Harness harness = new Harness(input.getAsJsonObject("initial"));
        for (JsonElement event : input.getAsJsonArray("events")) {
            if (event.isJsonArray()) harness.advance(event.getAsJsonArray().get(1).getAsLong());
            else switch (event.getAsString()) {
                case "flush": harness.advance(0); break;
                case "release": harness.lease = ""; break;
                case "fail-next-mutation": harness.failMutation = true; break;
                case "settings-failure": harness.failSettings = true; break;
                case "agent-failure": harness.controller.stop(); break;
                default: throw new IllegalArgumentException("Unknown Wi-Fi lease event");
            }
        }
        JsonObject result = new JsonObject();
        result.add("writes", harness.writes.deepCopy());
        result.addProperty("enabled", harness.enabled);
        harness.controller.close();
        return result;
    }

    static final class Harness implements WifiScanLease.IO {
        final JsonArray writes = new JsonArray();
        final WifiScanLease controller;
        long now = 1000;
        long next = 1000;
        String lease;
        boolean enabled;
        boolean failMutation;
        boolean failSettings;
        boolean ignorePause;
        int failures;
        int uncertain;

        Harness(JsonObject initial) {
            lease = DriveResetContractDriver.stringValue(initial, "lease", "7:1000");
            enabled = !initial.has("enabled") || initial.get("enabled").getAsBoolean();
            ignorePause = DriveResetContractDriver.flag(initial, "ignorePause");
            controller = new WifiScanLease(7, this);
        }
        void advance(long duration) {
            long until = now + duration;
            for (int count = 0; next <= until; count++) {
                if (count > 100) throw new AssertionError("Unbounded Wi-Fi polling");
                now = next;
                next = controller.poll() ? now + 1000 : Long.MAX_VALUE;
            }
            now = until;
        }
        @Override public long now() { return now; }
        @Override public String lease() {
            if (failSettings) throw new IllegalStateException("Settings unavailable");
            return lease;
        }
        @Override public boolean enabled() { return enabled; }
        @Override public void setEnabled(boolean desired) {
            if (desired || !ignorePause) enabled = desired;
            writes.add(desired);
            if (failMutation) {
                failMutation = false;
                throw new IllegalStateException("Vendor failure after mutation");
            }
        }
        @Override public void error(Exception failure) { failures++; }
        @Override public void uncertain(Exception failure) { uncertain++; }
    }
}
