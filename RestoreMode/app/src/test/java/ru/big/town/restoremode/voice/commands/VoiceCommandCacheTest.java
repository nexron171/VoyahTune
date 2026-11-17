package ru.big.town.restoremode.voice.commands;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class VoiceCommandCacheTest {
    @Test
    public void invalidationDuringBuildDiscardsTheOldSnapshot() throws Exception {
        VoiceCommandCache<String> cache = new VoiceCommandCache<>(300_000, () -> 1L);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger builds = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> result =
                    worker.submit(
                            () ->
                                    cache.load(
                                            () -> {
                                                if (builds.incrementAndGet() == 1) {
                                                    started.countDown();
                                                    try {
                                                        if (!release.await(5, TimeUnit.SECONDS)) {
                                                            throw new AssertionError(
                                                                    "build not released");
                                                        }
                                                    } catch (InterruptedException e) {
                                                        throw new AssertionError(e);
                                                    }
                                                    return "old settings";
                                                }
                                                return "new settings";
                                            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            cache.invalidate();
            release.countDown();
            assertEquals("new settings", result.get(5, TimeUnit.SECONDS));
            assertEquals(
                    "new settings",
                    cache.load(
                            () -> {
                                throw new AssertionError("unexpected rebuild");
                            }));
            assertEquals(2, builds.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void ttlAndExplicitInvalidationRebuildWithoutReturningNull() {
        AtomicLong time = new AtomicLong();
        AtomicInteger builds = new AtomicInteger();
        VoiceCommandCache<Integer> cache = new VoiceCommandCache<>(100, time::get);
        assertEquals(Integer.valueOf(1), cache.load(builds::incrementAndGet));
        time.set(99);
        assertEquals(Integer.valueOf(1), cache.load(builds::incrementAndGet));
        time.set(100);
        assertEquals(Integer.valueOf(2), cache.load(builds::incrementAndGet));
        cache.invalidate();
        assertEquals(Integer.valueOf(3), cache.load(builds::incrementAndGet));
    }

    @Test
    public void expiredValueIsServedWhileOneBackgroundRebuildIsQueued() {
        AtomicLong time = new AtomicLong();
        AtomicInteger builds = new AtomicInteger();
        List<Runnable> queued = new ArrayList<>();
        VoiceCommandCache<Integer> cache = new VoiceCommandCache<>(100, time::get);
        assertEquals(Integer.valueOf(1), cache.load(builds::incrementAndGet));

        time.set(150);
        assertEquals(
                "an expired value must be served instead of blocking the recording start",
                Integer.valueOf(1),
                cache.serveStaleAndRefresh(builds::incrementAndGet, queued::add));
        cache.serveStaleAndRefresh(builds::incrementAndGet, queued::add);
        assertEquals("only one rebuild may run", 1, queued.size());
        assertEquals(1, builds.get());

        queued.get(0).run();
        assertEquals("the queued rebuild must publish a fresh snapshot", 2, builds.get());
        time.set(160);
        assertEquals(Integer.valueOf(2), cache.serveStaleAndRefresh(() -> 99, queued::add));
        assertEquals("a fresh value must not schedule a rebuild", 1, queued.size());
    }

    @Test
    public void nothingIsServedBeforeTheFirstBuildOrAfterInvalidation() {
        List<Runnable> queued = new ArrayList<>();
        VoiceCommandCache<Integer> cache = new VoiceCommandCache<>(100, () -> 0L);

        assertNull(cache.serveStaleAndRefresh(() -> 1, queued::add));
        assertTrue("a caller without a snapshot must rebuild itself", queued.isEmpty());

        assertEquals(Integer.valueOf(1), cache.load(() -> 1));
        assertEquals(Integer.valueOf(1), cache.serveStaleAndRefresh(() -> 2, queued::add));

        cache.invalidate();
        assertNull(cache.serveStaleAndRefresh(() -> 2, queued::add));
    }
}
