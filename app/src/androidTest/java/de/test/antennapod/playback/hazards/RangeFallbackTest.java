package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;
import de.test.antennapod.playback.hazards.support.PlaybackCase;

/**
 * A server ignores Range and sends HTTP 200, either unchanged or replaced at the same URL.
 * Synthetic HTTP robustness coverage; adversarial review added the equal-length replacement case.
 * Cache A -> enable full-body responses (optionally replace A) -> restart and replay named passages.
 * Unfixed stable playback passes; changed bodies fill the old cache layout and fail the passage checks.
 */
@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class RangeFallbackTest extends PlaybackCase {
    @Test
    public void ignoredRangeKeepsStableContentReachable() throws Exception {
        primeCachedEpisode(false);
        app.server.ignoreRanges();
        restartAndCheckPassages();
    }

    @Test
    public void ignoredRangeWithChangedContentKeepsPassagesReachable() throws Exception {
        primeCachedEpisode(false);
        app.server.replaceAWithB();
        app.server.ignoreRanges();
        restartAndCheckPassages();
    }

    @Test
    public void ignoredRangeWithEqualLengthMutationKeepsPassagesReachable() throws Exception {
        primeCachedEpisode(true);
        app.server.replaceAWithB();
        app.server.ignoreRanges();
        restartAndCheckPassages();
    }
}
