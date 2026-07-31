package ru.big.town.anative;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class MediaControlPolicyTest {

    private static MediaControlPolicy.Candidate candidate(
            String token, int state, boolean pause, boolean bridge, boolean nativeQinggan) {
        return new MediaControlPolicy.Candidate(
                token, "test." + token, state, pause, bridge, nativeQinggan);
    }

    @Test
    public void picksFirstActiveSessionInsteadOfInactiveFrameworkHead() {
        int selected = MediaControlPolicy.chooseTarget(Arrays.asList(
                candidate("bt", MediaControlPolicy.STATE_INACTIVE, false, false, true),
                candidate("spotify", MediaControlPolicy.STATE_ACTIVE, true, false, false)
        ), "", false);

        assertEquals(1, selected);
    }

    @Test
    public void unpinnedInactiveStickyDoesNotBeatAnotherActiveSession() {
        int selected = MediaControlPolicy.chooseTarget(Arrays.asList(
                candidate("spotify", MediaControlPolicy.STATE_INACTIVE, true, false, false),
                candidate("bt", MediaControlPolicy.STATE_ACTIVE, false, false, true)
        ), "spotify", false);

        assertEquals(1, selected);
    }

    @Test
    public void latestActiveStickyWinsAmongTwoActiveSessions() {
        int selected = MediaControlPolicy.chooseTarget(Arrays.asList(
                candidate("bt", MediaControlPolicy.STATE_ACTIVE, false, false, true),
                candidate("spotify", MediaControlPolicy.STATE_ACTIVE, true, false, false)
        ), "spotify", true);

        assertEquals(1, selected);
    }

    @Test
    public void nullAndEmptySessionListsHaveNoTarget() {
        assertEquals(-1, MediaControlPolicy.chooseTarget(null, "", false));
        assertEquals(-1, MediaControlPolicy.chooseTarget(java.util.Collections.emptyList(), "", false));
    }

    @Test
    public void pinnedSessionWinsAfterOurPauseEvenIfAnotherSessionClaimsPlaying() {
        int selected = MediaControlPolicy.chooseTarget(Arrays.asList(
                candidate("bt", MediaControlPolicy.STATE_ACTIVE, false, false, true),
                candidate("spotify", MediaControlPolicy.STATE_INACTIVE, true, false, false)
        ), "spotify", true);

        assertEquals(1, selected);
    }

    @Test
    public void activeBridgeUsesWorkingKeymanagerPlayPausePath() {
        MediaControlPolicy.Plan plan = MediaControlPolicy.plan(
                candidate("autokit", MediaControlPolicy.STATE_ACTIVE, false, true, false),
                MediaControlPolicy.Command.PAUSE_ONLY);

        assertEquals(MediaControlPolicy.Operation.KEYMANAGER_KEY, plan.operation);
        assertEquals(MediaControlPolicy.KEY_PLAY_PAUSE, plan.keyCode);
    }

    @Test
    public void unknownBridgeUsesIdempotentPauseAndNeverBlindToggle() {
        MediaControlPolicy.Plan plan = MediaControlPolicy.plan(
                candidate("autokit", MediaControlPolicy.STATE_UNKNOWN, false, true, false),
                MediaControlPolicy.Command.PAUSE_ONLY);

        assertEquals(MediaControlPolicy.Operation.KEYMANAGER_KEY, plan.operation);
        assertEquals(MediaControlPolicy.KEY_PAUSE, plan.keyCode);
    }

    @Test
    public void activeMusicStreamMakesUnknownBridgeToggleSafe() {
        assertEquals(MediaControlPolicy.KEY_PLAY_PAUSE,
                MediaControlPolicy.pauseKeyWithAudioEvidence(MediaControlPolicy.KEY_PAUSE, true));
        assertEquals(0, MediaControlPolicy.pauseKeyWithAudioEvidence(0, true));
        assertEquals(MediaControlPolicy.KEY_PAUSE,
                MediaControlPolicy.pauseKeyWithAudioEvidence(MediaControlPolicy.KEY_PAUSE, false));
        assertEquals(0, MediaControlPolicy.pauseKeyWithAudioEvidence(0, false));
    }

    @Test
    public void ordinaryActivePlayerUsesTransportPauseWhenAdvertised() {
        MediaControlPolicy.Plan plan = MediaControlPolicy.plan(
                candidate("yandex", MediaControlPolicy.STATE_ACTIVE, true, false, false),
                MediaControlPolicy.Command.PAUSE_ONLY);

        assertEquals(MediaControlPolicy.Operation.TRANSPORT_PAUSE, plan.operation);
        assertEquals(0, plan.keyCode);
    }

    @Test
    public void alreadyInactiveSessionMakesDoorPauseNoOp() {
        MediaControlPolicy.Plan plan = MediaControlPolicy.plan(
                candidate("spotify", MediaControlPolicy.STATE_INACTIVE, true, false, false),
                MediaControlPolicy.Command.PAUSE_ONLY);

        assertEquals(MediaControlPolicy.Operation.NONE, plan.operation);
    }

    @Test
    public void nativeSessionPreservesOriginalQingganNextPath() {
        MediaControlPolicy.Plan plan = MediaControlPolicy.plan(
                candidate("bt", MediaControlPolicy.STATE_ACTIVE, false, false, true),
                MediaControlPolicy.Command.NEXT);

        assertEquals(MediaControlPolicy.Operation.NATIVE_QG, plan.operation);
        assertEquals(MediaControlPolicy.KEY_NEXT, plan.keyCode);
    }

    @Test
    public void recognizesObservedAutoKitBridgePackage() {
        org.junit.Assert.assertTrue(MediaControlPolicy.isBridgePackage("com.thunder.carplay"));
    }
}
