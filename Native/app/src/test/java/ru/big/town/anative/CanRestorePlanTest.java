package ru.big.town.anative;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CanRestorePlanTest {
    @Test
    public void retrySendsOnlyPendingCommands() {
        CanRestorePlan.Builder builder = new CanRestorePlan.Builder();
        builder.add("energy", frame(1));
        builder.add("drive", frame(2));
        builder.add("vsp", frame(3));
        CanRestorePlan plan = builder.build();
        List<String> calls = new ArrayList<>();
        AtomicInteger driveCalls = new AtomicInteger();

        assertEquals(CanRestorePlan.AttemptResult.TRANSIENT_FAILURE,
                plan.sendPending((frames, label) -> {
                    calls.add(label);
                    return !"drive".equals(label) || driveCalls.incrementAndGet() > 1;
                }));
        assertEquals(Arrays.asList("energy", "drive", "vsp"), calls);
        assertEquals(1, plan.pendingCount());

        assertEquals(CanRestorePlan.AttemptResult.SUCCESS,
                plan.sendPending((frames, label) -> {
                    calls.add(label);
                    return true;
                }));
        assertEquals(Arrays.asList("energy", "drive", "vsp", "drive"), calls);
    }

    @Test
    public void dependentCommandWaitsForItsPrerequisite() {
        CanRestorePlan.Builder builder = new CanRestorePlan.Builder();
        int energy = builder.add("energy", frame(1));
        builder.add("vsp", frame(2));
        builder.addAfter("forced EV", frame(3), energy);
        CanRestorePlan plan = builder.build();
        List<String> calls = new ArrayList<>();

        assertEquals(CanRestorePlan.AttemptResult.TRANSIENT_FAILURE,
                plan.sendPending((frames, label) -> {
                    calls.add(label);
                    return !"energy".equals(label);
                }));
        assertEquals(Arrays.asList("energy", "vsp"), calls);

        assertEquals(CanRestorePlan.AttemptResult.SUCCESS,
                plan.sendPending((frames, label) -> {
                    calls.add(label);
                    return true;
                }));
        assertEquals(Arrays.asList("energy", "vsp", "energy", "forced EV"), calls);
    }

    @Test
    public void intentionalNextPassResendsEveryCommand() {
        CanRestorePlan.Builder builder = new CanRestorePlan.Builder();
        builder.add("drive", frame(1));
        builder.add("vsp", frame(2));
        CanRestorePlan plan = builder.build();
        AtomicInteger calls = new AtomicInteger();

        assertEquals(CanRestorePlan.AttemptResult.SUCCESS,
                plan.sendPending((frames, label) -> {
                    calls.incrementAndGet();
                    return true;
                }));
        plan.resetForNextPass();
        assertEquals(CanRestorePlan.AttemptResult.SUCCESS,
                plan.sendPending((frames, label) -> {
                    calls.incrementAndGet();
                    return true;
                }));
        assertEquals(4, calls.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyRequiredCommandIsPermanentPlanError() {
        new CanRestorePlan.Builder().add("drive", new byte[0][]);
    }

    private static byte[][] frame(int marker) {
        byte[] frame = new byte[10];
        frame[0] = (byte) marker;
        return new byte[][]{frame};
    }
}
