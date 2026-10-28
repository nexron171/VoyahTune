package ru.big.town.restoremode.diagnostics.runtime;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class RuntimeStatusContractTest {
    private String payload(String state, long updated) throws Exception {
        JSONObject agent =
                new JSONObject()
                        .put("state", state)
                        .put("updatedUptime", updated)
                        .put("identity", new JSONObject().put("pid", 55).put("startTicks", "100"))
                        .put("target", "com.qinggan.bluetoothphone")
                        .put("architecture", "armeabi-v7a")
                        .put("nativeId", "native-test")
                        .put("runtimeId", "java-test")
                        .put("reason", "cleanup uncertain")
                        .put("modules",
                                new org.json.JSONArray().put("phone").put("client.example.app"));
        return new JSONObject()
                .put("schema", 2)
                .put("boot", "boot")
                .put("state", "running")
                .put("updatedUptime", 100)
                .put("loader", new JSONObject().put("pid", 12).put("startTicks", "1"))
                .put("nativeId", "native-test")
                .put("runtimeId", "java-test")
                .put("configurationError", "")
                .put("agents", new JSONObject().put("phone", agent))
                .toString();
    }

    private RuntimeStatusContract.Row row(RuntimeStatusContract.Snapshot snapshot, String id) {
        for (RuntimeStatusContract.Row row : snapshot.rows) {
            if (row.id.equals(id))
                return row;
        }
        throw new AssertionError("Missing " + id);
    }

    @Test
    public void activeRequiresCurrentIdentityAndCompleteAcknowledgement() throws Exception {
        RuntimeStatusContract.Snapshot snapshot =
                RuntimeStatusContract.describe(payload("active", 99), 102, true);
        assertEquals(RuntimeStatusContract.Level.GREEN, row(snapshot, "loader").level);
        assertEquals(RuntimeStatusContract.Level.GREEN, row(snapshot, "phone").level);
        assertTrue(row(snapshot, "phone").detail.contains("ARM32"));
        assertEquals(RuntimeStatusContract.Level.GREEN, row(snapshot, "client.example.app").level);
        assertEquals(RuntimeStatusContract.Level.YELLOW, row(snapshot, "acc").level);
        JSONObject invalid = new JSONObject(payload("active", 99));
        invalid.getJSONObject("agents").getJSONObject("phone").put("runtimeId", "another");
        assertEquals(RuntimeStatusContract.Level.RED,
                row(RuntimeStatusContract.describe(invalid.toString(), 102, true), "phone").level);
        invalid.getJSONObject("agents")
                .getJSONObject("phone")
                .put("runtimeId", "java-test")
                .put("modules", new org.json.JSONArray());
        assertEquals(RuntimeStatusContract.Level.RED,
                row(RuntimeStatusContract.describe(invalid.toString(), 102, true), "phone").level);
    }

    @Test
    public void waitingDisabledAndErrorsRemainDistinct() throws Exception {
        for (String state : new String[] {"waiting", "disabled", "injecting", "stopping"}) {
            assertEquals(state, RuntimeStatusContract.Level.YELLOW,
                    row(RuntimeStatusContract.describe(payload(state, 99), 102, true), "phone")
                            .level);
        }
        for (String state : new String[] {"failed", "unsupported", "restart-required"}) {
            RuntimeStatusContract.Row row =
                    row(RuntimeStatusContract.describe(payload(state, 99), 102, true), "phone");
            assertEquals(state, RuntimeStatusContract.Level.RED, row.level);
            assertTrue(row.detail.contains("cleanup uncertain"));
        }
    }

    @Test
    public void staleRebootedAndFutureRecordsNeverLookHealthy() throws Exception {
        for (RuntimeStatusContract.Snapshot snapshot : new RuntimeStatusContract.Snapshot[] {
                     RuntimeStatusContract.describe(payload("active", 99), 125, true),
                     RuntimeStatusContract.describe(payload("active", 99), 102, false),
                     RuntimeStatusContract.describe(payload("active", 99), 5, true)}) {
            for (RuntimeStatusContract.Row row : snapshot.rows) {
                assertNotEquals(row.id, RuntimeStatusContract.Level.GREEN, row.level);
            }
        }
        assertEquals(RuntimeStatusContract.Level.RED,
                row(RuntimeStatusContract.describe(payload("active", 60), 102, true), "phone")
                        .level);
    }

    @Test
    public void malformedAndMissingRecordsAreVisibleAndRejected() throws Exception {
        for (String payload : new String[] {null, "{}", "bad",
                     payload("active", 99).replace("\"schema\":2", "\"schema\":99")}) {
            assertFalse(RuntimeStatusContract.isValidPayload(payload));
            assertTrue(RuntimeStatusContract.describe(payload, 100, true).rows.size() >= 20);
        }
        assertTrue(RuntimeStatusContract.isValidPayload(payload("active", 99)));
    }
}
