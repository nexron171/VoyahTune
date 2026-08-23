package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LatestRequestGateTest {
    @Test
    public void burstKeepsOneRunningAndLatestFollowUp() {
        LatestRequestGate<Object> gate = new LatestRequestGate<>();
        Object first = new Object();
        assertSame(first, gate.offer(first));
        Object latest = null;
        for (int i = 0; i < 100_000; i++) {
            latest = new Object();
            assertNull(gate.offer(latest));
        }

        LatestRequestGate.Completion<Object> completion = gate.finish(first);

        assertFalse(completion.publish);
        assertSame(latest, completion.next);
        assertTrue(gate.finish(latest).publish);
    }

    @Test
    public void unchangedRunningRequestCanPublish() {
        LatestRequestGate<Object> gate = new LatestRequestGate<>();
        Object request = new Object();

        assertSame(request, gate.offer(request));
        assertNull(gate.offer(request));
        LatestRequestGate.Completion<Object> completion = gate.finish(request);

        assertTrue(completion.publish);
        assertNull(completion.next);
    }

    @Test
    public void rejectedSubmissionRetainsLatestForRetry() {
        LatestRequestGate<Object> gate = new LatestRequestGate<>();
        Object request = new Object();
        assertSame(request, gate.offer(request));

        gate.reject(request);

        assertSame(request, gate.retry());
        assertTrue(gate.finish(request).publish);
    }

    @Test
    public void rejectedRunningRequestHandsRetryToNewerValue() {
        LatestRequestGate<Object> gate = new LatestRequestGate<>();
        Object first = new Object();
        Object newer = new Object();
        assertSame(first, gate.offer(first));
        assertNull(gate.offer(newer));

        gate.reject(first);

        assertSame(newer, gate.retry());
        assertTrue(gate.finish(newer).publish);
    }

    @Test
    public void staleCompletionCannotPublishOrDisruptCurrentRun() {
        LatestRequestGate<Object> gate = new LatestRequestGate<>();
        Object first = new Object();
        Object stale = new Object();
        assertSame(first, gate.offer(first));

        LatestRequestGate.Completion<Object> completion = gate.finish(stale);

        assertFalse(completion.publish);
        assertNull(completion.next);
        assertTrue(gate.finish(first).publish);
    }

    @Test
    public void closeDropsRunningQueuedAndFutureRequests() {
        LatestRequestGate<Object> gate = new LatestRequestGate<>();
        Object first = new Object();
        assertSame(first, gate.offer(first));
        gate.offer(new Object());

        gate.close();

        assertFalse(gate.finish(first).publish);
        assertNull(gate.retry());
        assertNull(gate.offer(new Object()));
    }
}
