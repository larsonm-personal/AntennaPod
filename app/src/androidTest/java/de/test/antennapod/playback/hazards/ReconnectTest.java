package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;

import static org.junit.Assert.assertTrue;

@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class ReconnectTest extends PlaybackCase {
    @Test
    public void interruptedResponseKeepsPassagesReachable() throws Exception {
        primePlayback(false);
        int first = audio.heard().size();
        app.server.disconnectActiveResponses();
        app.server.resumeResponses();
        sampledContent(first, false);
        assertTrue("No response was interrupted", app.server.getInterruptedResponses() > 0);
        checkPassages();
    }
}
