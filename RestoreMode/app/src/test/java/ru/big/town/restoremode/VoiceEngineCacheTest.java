package ru.big.town.restoremode;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceEngineCacheTest {
    private static final class Engine implements AutoCloseable {
        int closes;
        @Override public void close() { closes++; }
    }

    @Test public void completedSessionsKeepOneResidentEngineUntilDisabled() throws Exception {
        VoiceEngineCache<Engine> cache = new VoiceEngineCache<>();
        cache.retain(true);
        Engine first = cache.acquire(Engine::new);
        for (int i = 0; i < 5; i++) {
            cache.releaseIfUnretained();
            assertSame(first, cache.acquire(Engine::new));
        }
        assertEquals(0, first.closes);
        cache.retain(false);
        cache.releaseIfUnretained();
        cache.releaseIfUnretained();
        assertEquals(1, first.closes);
        assertNotSame(first, cache.acquire(Engine::new));
        cache.release();
    }

    @Test public void disabledAssistantDryRunReleasesItsModels() throws Exception {
        VoiceEngineCache<Engine> cache = new VoiceEngineCache<>();
        Engine engine = cache.acquire(Engine::new);
        cache.releaseIfUnretained();
        assertEquals(1, engine.closes);
    }

    @Test public void reenableBeforeQueuedCleanupPreservesEngine() throws Exception {
        VoiceEngineCache<Engine> cache = new VoiceEngineCache<>();
        Engine engine = cache.acquire(Engine::new);
        cache.retain(false);
        cache.retain(true);
        cache.releaseIfUnretained();
        assertSame(engine, cache.acquire(Engine::new));
        assertEquals(0, engine.closes);
        cache.release();
    }

    @Test public void failedPreparationCanBeRetriedWithoutCachingBrokenEngine() throws Exception {
        VoiceEngineCache<Engine> cache = new VoiceEngineCache<>();
        try {
            cache.acquire(() -> { throw new Exception("load failed"); });
            fail("Expected preparation failure");
        } catch (Exception expected) { assertEquals("load failed", expected.getMessage()); }
        assertNotNull(cache.acquire(Engine::new));
        cache.release();
    }

    @Test public void openingScreenDuringWarmupWaitsForSameEngineWithoutRestart() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        VoiceEngineCache<Engine> cache = new VoiceEngineCache<>();
        cache.retain(true);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loading = new CountDownLatch(1), allowReady = new CountDownLatch(1);
        try {
            Future<Engine> warmup = worker.submit(() -> cache.acquire(() -> {
                loads.incrementAndGet(); loading.countDown();
                assertTrue(allowReady.await(5, TimeUnit.SECONDS));
                return new Engine();
            }));
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            Future<Engine> screen = worker.submit(() -> cache.acquire(() -> {
                loads.incrementAndGet(); return new Engine();
            }));
            assertFalse("Screen must wait for ongoing preparation", screen.isDone());
            allowReady.countDown();
            assertSame(warmup.get(5, TimeUnit.SECONDS), screen.get(5, TimeUnit.SECONDS));
            assertEquals("Model load must not restart", 1, loads.get());
        } finally {
            allowReady.countDown(); worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            cache.release();
        }
    }
}
