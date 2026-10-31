package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;
import de.test.antennapod.playback.hazards.support.PlaybackCase;

/**
 * GET redirects from A to B while HEAD still describes A.
 * Synthetic resolver robustness coverage, not an observed detail of Michael's captures.
 * Cache part of A -> change only GET routing -> restart the service -> replay fixed program passages.
 * Unfixed playback keeps A's cached byte layout while GET fills its holes from B, missing a required passage.
 */
@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class HeadGetDisagreementTest extends PlaybackCase {
    @Test
    public void getSelectedContentKeepsPassagesReachable() throws Exception {
        primeCachedEpisode(false);
        app.server.redirectGetsFromAToB();
        restartAndCheckPassages();
    }
}
