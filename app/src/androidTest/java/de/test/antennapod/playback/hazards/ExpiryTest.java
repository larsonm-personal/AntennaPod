package de.test.antennapod.playback.hazards;

import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;

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
        app.server.resumeResponses();
        try {
            app.onMain(() -> app.controller.seekTo(110000));
            sampledContent(first, false);
            checkPassages();
        } finally {
            Log.i("ServiceAudioObserver", "expiry responses=" + app.server.getExpiredResponses()
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
