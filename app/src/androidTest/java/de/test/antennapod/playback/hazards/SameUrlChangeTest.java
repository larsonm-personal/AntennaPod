package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;
import de.test.antennapod.playback.hazards.support.PlaybackCase;

/**
 * The same resolved URL serves a different edition, with either changed or unchanged encoded length.
 * Synthetic cases informed by Pocket Casts Android #6213 and Shuttle AudioPlaybackKit ADR 0016, not captures.
 * Cache A -> replace bytes at A -> restart/replay; the equal-length B-only case is a healthy control.
 * Unfixed playback combines cached A with new B: both mutations miss a required passage; B alone passes.
 */
@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class SameUrlChangeTest extends PlaybackCase {
    @Test
    public void differentLengthMutationKeepsPassagesReachable() throws Exception {
        primeCachedEpisode(false);
        app.server.replaceAWithB();
        restartAndCheckPassages();
    }

    @Test
    public void equalLengthMutationKeepsPassagesReachable() throws Exception {
        primeCachedEpisode(true);
        app.server.replaceAWithB();
        restartAndCheckPassages();
    }

    @Test
    public void equalLengthEditionPlaysKnownPassages() throws Exception {
        start("tagged-equal-length.mp3", 80, false);
        select(addEpisode("B"), 80);
        sample(() -> app.controller.play(), 1, 80);
        checkPassages();
    }
}
