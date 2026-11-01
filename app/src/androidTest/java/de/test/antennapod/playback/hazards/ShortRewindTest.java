package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;
import de.test.antennapod.playback.hazards.support.PlaybackCase;

/**
 * Short rewinds must make a named passage audible when replaying across a cached edition join.
 * The Oct 31 live rewind trace motivated this synthetic case; it did not independently identify omitted audio.
 * Prime A -> restart with fresh resolution B -> play past the join -> rewind three times -> listen forward.
 * Mixed cached bytes can omit the passage again; coherent A reuse or B replacement may both recover it.
 */
@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class ShortRewindTest extends PlaybackCase {
    @Test
    public void shortRewindRecoversPassageAcrossChangedResolution() throws Exception {
        primeCachedEpisode(false);
        app.server.switchFreshResolutionsToB();
        restartForReplay();
        rewindAndHearPassage(75);
    }

    @Test
    public void shortRewindReplaysOriginalEdition() throws Exception {
        primeCachedEpisode(false);
        restartForReplay();
        rewindAndHearPassage(75);
    }

    @Test
    public void shortRewindReplaysReplacementEdition() throws Exception {
        start(false, 70, false);
        // Program second 25 is near media 87 in A and 27 in B.
        select(addEpisode("B"), 27);
        app.onMain(() -> app.controller.play());
        rewindAndHearPassage(75);
    }

    private void restartForReplay() throws Exception {
        app.server.resumeResponses();
        app.connect();
        app.onMain(() -> app.controller.play());
    }
}
