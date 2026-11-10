package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class NavigationAudioDuckingTest {
    @Test
    public void requestsOnceForOverlappingSoundsAndReleasesAfterLastSound() throws Exception {
        RecordingFocus focus = new RecordingFocus();
        NavigationAudioDucking ducking = new NavigationAudioDucking(focus);

        ducking.update(true, 1000);
        ducking.update(true, 1100);
        ducking.update(false, 1200);
        ducking.update(true, 1300);
        ducking.update(false, 1500);
        ducking.update(false, 1749);
        assertEquals(1, focus.requests);
        assertEquals(0, focus.releases);

        ducking.update(false, 1750);
        assertEquals(1, focus.releases);
        ducking.update(false, 2000);
        assertEquals(1, focus.releases);
    }

    @Test
    public void closingRestoresMusicImmediatelyAndIgnoresLatePlayback() throws Exception {
        RecordingFocus focus = new RecordingFocus();
        NavigationAudioDucking ducking = new NavigationAudioDucking(focus);

        ducking.update(true, 0);
        ducking.close();
        ducking.close();
        ducking.update(true, 1000);

        assertEquals(1, focus.requests);
        assertEquals(1, focus.releases);
    }

    @Test
    public void failedRequestStillCleansUpPossibleOemFocusOwner() throws Exception {
        RecordingFocus focus = new RecordingFocus();
        focus.failRequest = true;
        NavigationAudioDucking ducking = new NavigationAudioDucking(focus);
        try {
            ducking.update(true, 0);
        } catch (IllegalStateException expected) {
            assertEquals("request failed", expected.getMessage());
        }
        ducking.update(true, 100);
        ducking.close();

        assertEquals(1, focus.requests);
        assertEquals(1, focus.releases);
    }

    @Test
    public void failedReleaseIsRetriedWithoutCreatingAnotherFocusOwner() throws Exception {
        RecordingFocus focus = new RecordingFocus();
        NavigationAudioDucking ducking = new NavigationAudioDucking(focus);
        ducking.update(true, 0);
        ducking.update(false, 100);
        focus.failRelease = true;
        try {
            ducking.update(false, 350);
        } catch (IllegalStateException expected) {
            assertEquals("release failed", expected.getMessage());
        }
        focus.failRelease = false;
        ducking.update(false, 450);
        ducking.update(false, 600);
        assertEquals(1, focus.requests);
        assertEquals(2, focus.releases);
    }

    @Test
    public void deniedFocusRetriesWhilePlayingWithoutRequestingOnEveryPoll() throws Exception {
        RecordingFocus focus = new RecordingFocus();
        focus.grantRequest = false;
        NavigationAudioDucking ducking = new NavigationAudioDucking(focus);
        ducking.update(true, 0);
        ducking.update(true, 499);
        assertEquals(1, focus.requests);
        focus.grantRequest = true;
        ducking.update(true, 500);
        ducking.update(true, 1000);
        assertEquals(2, focus.requests);
        ducking.close();
        assertEquals(1, focus.releases);
    }

    @Test
    public void exceptionAndServiceDeathCanRecoverWithinTheSamePlayback() throws Exception {
        RecordingFocus focus = new RecordingFocus();
        focus.failRequest = true;
        NavigationAudioDucking ducking = new NavigationAudioDucking(focus);
        try {
            ducking.update(true, 0);
        } catch (IllegalStateException expected) {
            assertEquals("request failed", expected.getMessage());
        }
        focus.failRequest = false;
        ducking.update(true, 500);
        assertEquals(2, focus.requests);
        focus.alive = false;
        ducking.update(true, 1000);
        focus.alive = true;
        ducking.update(true, 1100);
        assertEquals(3, focus.requests);
        ducking.close();
        assertEquals(1, focus.releases);
    }

    private static final class RecordingFocus implements NavigationAudioDucking.Focus {
        int requests;
        int releases;
        boolean failRequest;
        boolean grantRequest = true;
        boolean alive = true;
        boolean failRelease;

        @Override
        public boolean request() {
            requests++;
            if (failRequest) throw new IllegalStateException("request failed");
            return grantRequest;
        }

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public void release() {
            releases++;
            if (failRelease) throw new IllegalStateException("release failed");
        }
    }
}
