package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;
import de.test.antennapod.playback.hazards.support.PlaybackCase;

@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class ShortRewindTest extends PlaybackCase {
    @Test
    public void changedResolutionRetainsPassageAfterRewind() throws Exception {
        primePartialOriginal();
        app.server.switchFreshResolutionsToB();
        restartForReplay();
        rewindAndHearPassage(75);
    }

    @Test
    public void originalEditionRetainsPassageAfterRewind() throws Exception {
        primePartialOriginal();
        restartForReplay();
        rewindAndHearPassage(75);
    }

    @Test
    public void replacementEditionRetainsPassageAfterRewind() throws Exception {
        playReplacement();
        rewindAndHearPassage(75);
    }
}
