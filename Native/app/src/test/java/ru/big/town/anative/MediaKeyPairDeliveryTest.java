package ru.big.town.anative;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class MediaKeyPairDeliveryTest {

    @Test
    public void rejectedDownAllowsOneFallbackAndDoesNotSendUp() {
        AtomicInteger calls = new AtomicInteger();

        MediaKeyPairDelivery.Outcome outcome = MediaKeyPairDelivery.dispatch(down -> {
            calls.incrementAndGet();
            return false;
        });

        assertEquals(MediaKeyPairDelivery.Outcome.NOT_SENT, outcome);
        assertEquals(1, calls.get());
    }

    @Test
    public void upExceptionAfterAcceptedDownNeverAllowsFallback() {
        AtomicInteger calls = new AtomicInteger();

        MediaKeyPairDelivery.Outcome outcome = MediaKeyPairDelivery.dispatch(down -> {
            calls.incrementAndGet();
            if (!down) throw new IllegalStateException("up failed");
            return true;
        });

        assertEquals(MediaKeyPairDelivery.Outcome.DOWN_ONLY, outcome);
        assertEquals(2, calls.get());
    }

    @Test
    public void rejectedUpAfterAcceptedDownNeverAllowsFallback() {
        AtomicInteger calls = new AtomicInteger();

        MediaKeyPairDelivery.Outcome outcome = MediaKeyPairDelivery.dispatch(down -> {
            calls.incrementAndGet();
            return down;
        });

        assertEquals(MediaKeyPairDelivery.Outcome.DOWN_ONLY, outcome);
        assertEquals(2, calls.get());
    }

    @Test
    public void acceptedPairUsesOneBackendExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();

        MediaKeyPairDelivery.Outcome outcome = MediaKeyPairDelivery.dispatch(down -> {
            calls.incrementAndGet();
            return true;
        });

        assertEquals(MediaKeyPairDelivery.Outcome.COMPLETE, outcome);
        assertEquals(2, calls.get());
    }
}
