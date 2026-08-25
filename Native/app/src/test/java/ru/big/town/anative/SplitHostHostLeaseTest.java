package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class SplitHostHostLeaseTest {
    @Test
    public void newerHostReceivesPreviousOwnerAndIncreasingGeneration() {
        SplitHostHostLease<Object> lease = new SplitHostHostLease<>();
        Object first = new Object();
        Object second = new Object();

        SplitHostHostLease.Registration<Object> one = lease.acquire(first);
        SplitHostHostLease.Registration<Object> two = lease.acquire(second);

        assertEquals(1L, one.generation);
        assertNull(one.previousOwner);
        assertEquals(2L, two.generation);
        assertSame(first, two.previousOwner);
    }

    @Test
    public void staleReleaseCannotClearSuccessor() {
        SplitHostHostLease<Object> lease = new SplitHostHostLease<>();
        Object first = new Object();
        Object second = new Object();
        long stale = lease.acquire(first).generation;
        lease.acquire(second);

        lease.release(stale);

        assertSame(second, lease.acquire(new Object()).previousOwner);
    }

    @Test
    public void currentReleaseClearsOwner() {
        SplitHostHostLease<Object> lease = new SplitHostHostLease<>();
        long current = lease.acquire(new Object()).generation;

        lease.release(current);

        assertNull(lease.acquire(new Object()).previousOwner);
    }
}
