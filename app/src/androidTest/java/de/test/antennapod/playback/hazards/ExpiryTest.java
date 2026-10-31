package de.test.antennapod.playback.hazards;

import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;
import de.test.antennapod.playback.hazards.support.PlaybackCase;

import static org.junit.Assert.assertTrue;

/**
 * Retires A with HTTP 403 while fresh resolution selects B; safe avoidance of A is also accepted.
 * Synthetic expired-link coverage, not behaviour measured in Michael's captures.
 * Prime A -> expire A and resolve B -> interrupt and seek, or restart -> replay fixed passages.
 * Unfixed active playback does not re-resolve expired A; a fresh restart instead mixes cached A with B.
 */
@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class ExpiryTest extends PlaybackCase {
    @Test
    public void expiredActiveUrlKeepsPassagesReachable() throws Exception {
        primePlayback(false);
        int first = audio.heard().size();
        app.server.expireA();
        app.server.switchFreshResolutionsToB();
        app.server.disconnectActiveResponses();
        app.server.resumeResponses();
        try {
            app.onMain(() -> app.controller.seekTo(110000));
            sampledContent(first, false);
            assertTrue("Expiry setup did not interrupt an active response", app.server.getInterruptedResponses() > 0);
            checkPassages();
        } finally {
            Log.i("ServiceAudioObserver", "expiry interruptions=" + app.server.getInterruptedResponses()
                    + " expiry responses=" + app.server.getExpiredResponses()
                    + " (zero means avoidance, not demonstrated 403 recovery)");
        }
    }

    @Test
    public void freshResolutionMayAvoidExpiredUrl() throws Exception {
        primeCachedEpisode(false);
        app.server.expireA();
        app.server.switchFreshResolutionsToB();
        try {
            restartAndCheckPassages();
        } finally {
            Log.i("ServiceAudioObserver", "restart expiry responses=" + app.server.getExpiredResponses());
        }
    }
}
