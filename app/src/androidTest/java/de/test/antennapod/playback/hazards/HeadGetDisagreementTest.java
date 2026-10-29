package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;

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
