package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public class MediaControlPolicyTest {
    @Test
    public void olderRadioFirmwareKeepsTargetedKeysForActivePlayback() {
        MediaControlPolicy.Candidate radio =
                new MediaControlPolicy.Candidate(
                        "radio",
                        "com.pateo.rdsapp",
                        MediaControlPolicy.STATE_ACTIVE,
                        false,
                        false,
                        false);
        for (ru.big.town.common.VehiclePlatform platform :
                ru.big.town.common.VehiclePlatform.values()) {
            MediaControlPolicy.Plan next =
                    MediaControlPolicy.plan(platform, radio, MediaControlPolicy.Command.NEXT);
            if (platform == ru.big.town.common.VehiclePlatform.H97X
                    || platform == ru.big.town.common.VehiclePlatform.H97Y) {
                assertEquals(MediaControlPolicy.Operation.TRANSPORT_NEXT, next.operation);
            } else {
                assertEquals(MediaControlPolicy.Operation.TARGET_KEY, next.operation);
                assertEquals(87, next.keyCode);
            }
        }
    }

    private static MediaControlPolicy.Candidate candidate(
            String token, int state, boolean pause, boolean bridge, boolean nativeQinggan) {
        return new MediaControlPolicy.Candidate(
                token, "test." + token, state, pause, bridge, nativeQinggan);
    }

    @Test
    public void picksFirstActiveSessionInsteadOfInactiveFrameworkHead() {
        int selected =
                MediaControlPolicy.chooseTarget(
                        Arrays.asList(
                                candidate(
                                        "bt",
                                        MediaControlPolicy.STATE_INACTIVE,
                                        false,
                                        false,
                                        true),
                                candidate(
                                        "spotify",
                                        MediaControlPolicy.STATE_ACTIVE,
                                        true,
                                        false,
                                        false)),
                        "",
                        false);

        assertEquals(1, selected);
    }

    @Test
    public void unpinnedInactiveStickyDoesNotBeatAnotherActiveSession() {
        int selected =
                MediaControlPolicy.chooseTarget(
                        Arrays.asList(
                                candidate(
                                        "spotify",
                                        MediaControlPolicy.STATE_INACTIVE,
                                        true,
                                        false,
                                        false),
                                candidate(
                                        "bt", MediaControlPolicy.STATE_ACTIVE, false, false, true)),
                        "spotify",
                        false);

        assertEquals(1, selected);
    }

    @Test
    public void latestActiveStickyWinsAmongTwoActiveSessions() {
        int selected =
                MediaControlPolicy.chooseTarget(
                        Arrays.asList(
                                candidate(
                                        "bt", MediaControlPolicy.STATE_ACTIVE, false, false, true),
                                candidate(
                                        "spotify",
                                        MediaControlPolicy.STATE_ACTIVE,
                                        true,
                                        false,
                                        false)),
                        "spotify",
                        true);

        assertEquals(1, selected);
    }

    @Test
    public void nullAndEmptySessionListsHaveNoTarget() {
        assertEquals(-1, MediaControlPolicy.chooseTarget(null, "", false));
        assertEquals(
                -1, MediaControlPolicy.chooseTarget(java.util.Collections.emptyList(), "", false));
    }

    @Test
    public void pinnedSessionWinsAfterOurPauseEvenIfAnotherSessionClaimsPlaying() {
        int selected =
                MediaControlPolicy.chooseTarget(
                        Arrays.asList(
                                candidate(
                                        "bt", MediaControlPolicy.STATE_ACTIVE, false, false, true),
                                candidate(
                                        "spotify",
                                        MediaControlPolicy.STATE_INACTIVE,
                                        true,
                                        false,
                                        false)),
                        "spotify",
                        true);

        assertEquals(1, selected);
    }

    @Test
    public void activeBridgeUsesWorkingKeymanagerPlayPausePath() {
        MediaControlPolicy.Plan plan =
                MediaControlPolicy.plan(
                        candidate("autokit", MediaControlPolicy.STATE_ACTIVE, false, true, false),
                        MediaControlPolicy.Command.PAUSE_ONLY);

        assertEquals(MediaControlPolicy.Operation.KEYMANAGER_KEY, plan.operation);
        assertEquals(MediaControlPolicy.KEY_PLAY_PAUSE, plan.keyCode);
    }

    @Test
    public void unknownBridgeUsesIdempotentPauseAndNeverBlindToggle() {
        MediaControlPolicy.Plan plan =
                MediaControlPolicy.plan(
                        candidate("autokit", MediaControlPolicy.STATE_UNKNOWN, false, true, false),
                        MediaControlPolicy.Command.PAUSE_ONLY);

        assertEquals(MediaControlPolicy.Operation.KEYMANAGER_KEY, plan.operation);
        assertEquals(MediaControlPolicy.KEY_PAUSE, plan.keyCode);
    }

    @Test
    public void activeMusicStreamMakesUnknownBridgeToggleSafe() {
        assertEquals(
                MediaControlPolicy.KEY_PLAY_PAUSE,
                MediaControlPolicy.pauseKeyWithAudioEvidence(MediaControlPolicy.KEY_PAUSE, true));
        assertEquals(0, MediaControlPolicy.pauseKeyWithAudioEvidence(0, true));
        assertEquals(
                MediaControlPolicy.KEY_PAUSE,
                MediaControlPolicy.pauseKeyWithAudioEvidence(MediaControlPolicy.KEY_PAUSE, false));
        assertEquals(0, MediaControlPolicy.pauseKeyWithAudioEvidence(0, false));
    }

    @Test
    public void ordinaryActivePlayerUsesTransportPauseWhenAdvertised() {
        MediaControlPolicy.Plan plan =
                MediaControlPolicy.plan(
                        candidate("yandex", MediaControlPolicy.STATE_ACTIVE, true, false, false),
                        MediaControlPolicy.Command.PAUSE_ONLY);

        assertEquals(MediaControlPolicy.Operation.TRANSPORT_PAUSE, plan.operation);
        assertEquals(0, plan.keyCode);
    }

    @Test
    public void alreadyInactiveSessionMakesDoorPauseNoOp() {
        MediaControlPolicy.Plan plan =
                MediaControlPolicy.plan(
                        candidate("spotify", MediaControlPolicy.STATE_INACTIVE, true, false, false),
                        MediaControlPolicy.Command.PAUSE_ONLY);

        assertEquals(MediaControlPolicy.Operation.NONE, plan.operation);
    }

    @Test
    public void nativeSessionPreservesOriginalQingganNextPath() {
        MediaControlPolicy.Plan plan =
                MediaControlPolicy.plan(
                        candidate("bt", MediaControlPolicy.STATE_ACTIVE, false, false, true),
                        MediaControlPolicy.Command.NEXT);

        assertEquals(MediaControlPolicy.Operation.NATIVE_QG, plan.operation);
        assertEquals(MediaControlPolicy.KEY_NEXT, plan.keyCode);
    }

    @Test
    public void recognizesObservedAutoKitBridgePackage() {
        org.junit.Assert.assertTrue(MediaControlPolicy.isBridgePackage("com.thunder.carplay"));
    }

    @Test
    public void activeRadioUsesTransportCommandsDespiteMissingAdvertisedActions() {
        MediaControlPolicy.Candidate radio =
                new MediaControlPolicy.Candidate(
                        "radio",
                        "com.pateo.rdsapp",
                        MediaControlPolicy.STATE_ACTIVE,
                        false,
                        false,
                        false);
        assertEquals(
                MediaControlPolicy.Operation.TRANSPORT_NEXT,
                MediaControlPolicy.radioPlan(radio, MediaControlPolicy.Command.NEXT).operation);
        assertEquals(
                MediaControlPolicy.Operation.TRANSPORT_PREVIOUS,
                MediaControlPolicy.radioPlan(radio, MediaControlPolicy.Command.PREVIOUS).operation);
        assertEquals(
                MediaControlPolicy.Operation.TRANSPORT_PAUSE,
                MediaControlPolicy.radioPlan(radio, MediaControlPolicy.Command.PLAY_PAUSE)
                        .operation);
        assertEquals(
                MediaControlPolicy.Operation.TARGET_KEY,
                MediaControlPolicy.plan(radio, MediaControlPolicy.Command.NEXT).operation);
    }

    @Test
    public void pausedRadioResumesThroughOemPlayAndDoesNotStartOnSkip() {
        MediaControlPolicy.Candidate radio =
                new MediaControlPolicy.Candidate(
                        "radio",
                        "com.pateo.rdsapp",
                        MediaControlPolicy.STATE_INACTIVE,
                        false,
                        false,
                        false);
        assertEquals(
                MediaControlPolicy.Operation.TRANSPORT_PLAY,
                MediaControlPolicy.radioPlan(radio, MediaControlPolicy.Command.PLAY_PAUSE)
                        .operation);
        assertEquals(
                MediaControlPolicy.Operation.NONE,
                MediaControlPolicy.radioPlan(radio, MediaControlPolicy.Command.PAUSE_ONLY)
                        .operation);
        assertEquals(
                MediaControlPolicy.Operation.TARGET_KEY,
                MediaControlPolicy.radioPlan(radio, MediaControlPolicy.Command.NEXT).operation);
    }

    @Test
    public void playOnlyNeverTogglesOrPausesAnyPlayer() {
        for (boolean bridge : new boolean[] {false, true}) {
            for (boolean nativeQinggan : new boolean[] {false, true}) {
                for (int state :
                        new int[] {
                            MediaControlPolicy.STATE_UNKNOWN,
                            MediaControlPolicy.STATE_INACTIVE,
                            MediaControlPolicy.STATE_ACTIVE
                        }) {
                    MediaControlPolicy.Plan plan =
                            MediaControlPolicy.plan(
                                    candidate("player", state, false, bridge, nativeQinggan),
                                    MediaControlPolicy.Command.PLAY_ONLY);
                    if (state == MediaControlPolicy.STATE_ACTIVE) {
                        assertEquals(MediaControlPolicy.Operation.NONE, plan.operation);
                    } else {
                        assertEquals(MediaControlPolicy.KEY_PLAY, plan.keyCode);
                        assertEquals(
                                bridge || nativeQinggan
                                        ? MediaControlPolicy.Operation.KEYMANAGER_KEY
                                        : MediaControlPolicy.Operation.TARGET_KEY,
                                plan.operation);
                    }
                }
            }
        }
        MediaControlPolicy.Candidate radio =
                new MediaControlPolicy.Candidate(
                        "radio",
                        "com.pateo.rdsapp",
                        MediaControlPolicy.STATE_INACTIVE,
                        false,
                        false,
                        false);
        assertEquals(
                MediaControlPolicy.Operation.TRANSPORT_PLAY,
                MediaControlPolicy.radioPlan(radio, MediaControlPolicy.Command.PLAY_ONLY)
                        .operation);
    }

    @Test
    public void radioExtensionPreservesOtherPlayersAndBridgeRouting() {
        for (boolean bridge : new boolean[] {false, true}) {
            MediaControlPolicy.Candidate player =
                    candidate("player", MediaControlPolicy.STATE_ACTIVE, true, bridge, false);
            for (MediaControlPolicy.Command command : MediaControlPolicy.Command.values()) {
                assertEquals(
                        MediaControlPolicy.plan(player, command).operation,
                        MediaControlPolicy.radioPlan(player, command).operation);
            }
        }
    }
}
