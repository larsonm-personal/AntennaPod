package de.test.antennapod.playback.hazards;

import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.awaitility.Awaitility;

import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;

import java.util.concurrent.TimeUnit;

import de.test.antennapod.IgnoreOnCi;
import de.test.antennapod.playback.hazards.support.PlaybackCase;
import de.test.antennapod.playback.hazards.support.ResolvedTaggedAudio;

import static org.junit.Assert.assertTrue;

/**
 * Checks partial-cache restart and bounded response-body traffic across fully buffered restarts.
 * AntennaPod #8487/#8552 motivate reuse; the three-restart byte budget is synthetic engineering coverage.
 * Replay partial A; separately buffer A -> restart three times -> compare warm bytes with one cold transfer.
 * Unfixed content playback passes, but uncached chapter scans repeat transfers and exceed the warm budget.
 */
@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class CacheReuseTest extends PlaybackCase {
    @Test
    public void stablePartialCacheRestartKeepsPassagesReachable() throws Exception {
        primeCachedEpisode(false);
        restartAndCheckPassages();
    }

    @Test
    public void warmRestartsDoNotRepeatColdTransfer() throws Exception {
        start(false);
        awaitFullyBuffered();
        sample(() -> app.controller.play(), 0, 80);
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() ->
                PlaybackPreferences.getCurrentlyPlayingFeedMediaId() == app.media.getId());
        long cold = app.server.getBodyBytes();
        assertTrue("No cold transfer measured", cold > 0);
        for (int restart = 0; restart < 3; restart++) {
            app.stopService();
            app.connect();
            int first = audio.heard().size();
            app.onMain(() -> app.controller.play());
            sampledContent(first, false);
            ResolvedTaggedAudio.segment(audio.heard(), first + 8, 0.25);
            awaitFullyBuffered();
        }
        long warm = app.server.getBodyBytes() - cold;
        Log.i("ServiceAudioObserver", "transfer coldBytes=" + cold + " threeWarmRestartsBytes=" + warm);
        assertTrue("Warm restarts repeated episode transfer: cold=" + cold + " warm=" + warm, warm <= cold);
        checkPassages();
    }
}
