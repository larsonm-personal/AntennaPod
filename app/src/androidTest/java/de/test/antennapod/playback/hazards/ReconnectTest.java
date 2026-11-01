package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;
import de.test.antennapod.playback.hazards.support.PlaybackCase;

import static org.junit.Assert.assertTrue;

/**
 * Interrupted HTTP/1.1 responses must resume without losing access to program content.
 * Synthetic interruption inspired by Pocket Casts Android #5869, not its reported HTTP/2 END_STREAM failure.
 * Partially play A -> disconnect its active responses -> resume serving A -> replay named passages.
 * This already passes unfixed: the resumed bytes belong to the same edition.
 */
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
