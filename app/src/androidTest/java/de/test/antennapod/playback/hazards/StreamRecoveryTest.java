package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.Player;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.awaitility.Awaitility;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.test.antennapod.IgnoreOnCi;

@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class StreamRecoveryTest extends PlaybackCase {
    @Test
    public void replacementPreservesAnInRangeRequestedPosition() throws Exception {
        primePlayback(false);
        app.onMain(() -> app.controller.pause());
        app.server.expireA();
        app.server.switchFreshResolutionsToB();
        app.server.disconnectActiveResponses();
        app.onMain(() -> app.controller.seekTo(110000));
        app.server.resumeResponses();
        Awaitility.await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> app.onMain(() -> {
            assertTrue("Expiry was not encountered", app.server.getExpiredResponses() > 0);
            assertEquals(Player.STATE_READY, app.controller.getPlaybackState());
            assertTrue(app.controller.getDuration() > 0 && app.controller.getDuration() < 170000);
            assertEquals(110000, app.controller.getCurrentPosition());
            assertFalse(app.controller.getPlayWhenReady());
        }));
        sample(() -> app.controller.play(), 1, 110);
        checkPassages();
    }

    @Test
    public void replacementShorterThanRequestedPositionKeepsEpisodeAvailable() throws Exception {
        primePlayback(false);
        int first = audio.heard().size();
        app.server.expireA();
        app.server.switchFreshResolutionsToB();
        app.server.resumeResponses();
        app.onMain(() -> app.controller.seekTo(170000));
        sampledContent(first, false);
        checkPassages();
    }
}
