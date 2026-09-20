package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClusterMediaSessionTest {
    @Test public void shortTapCancelsHostBeforeActivityCreation() {
        ClusterMediaSession session = new ClusterMediaSession();
        long token = session.begin("navigator");
        assertTrue(session.cancelPackage("navigator"));
        assertFalse(session.owns(token, "navigator"));
    }

    @Test public void delayedDestroyCannotCloseReplacementHost() {
        ClusterMediaSession session = new ClusterMediaSession();
        long first = session.begin("navigator");
        long second = session.begin("music");
        session.end(first);
        assertTrue(session.owns(second, "music"));
        assertFalse(session.owns(first, "navigator"));
    }

    @Test public void openingUnrelatedAppLeavesMediaCardRunning() {
        ClusterMediaSession session = new ClusterMediaSession();
        long token = session.begin("navigator");
        assertFalse(session.cancelPackage("music"));
        assertTrue(session.owns(token, "navigator"));
    }

    @Test public void rapidLongShortLongKeepsOnlyNewestRequest() {
        ClusterMediaSession session = new ClusterMediaSession();
        long first = session.begin("navigator");
        session.cancelPackage("navigator");
        long second = session.begin("navigator");
        session.end(first);
        assertFalse(session.owns(first, "navigator"));
        assertTrue(session.owns(second, "navigator"));
    }
}
