package de.test.antennapod.playback.hazards;

import android.os.SystemClock;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import de.danoeh.antennapod.model.feed.FeedMedia;

import org.junit.Test;


import static org.junit.Assert.assertTrue;

@LargeTest
@OptIn(markerClass = UnstableApi.class)
public class PlaybackLifecycleTest extends PlaybackCase {
    @Test
    public void pausedPreparationDoesNotCountQueuedAudio() throws Exception {
        start(false);
        awaitReady();
        SystemClock.sleep(2000);
        assertTrue("Paused preparation played audio", audio.heard().isEmpty());
        sample(() -> app.controller.play(), 0, 80);
    }

    @Test
    public void ordinarySeeksExposeKnownProgramPassages() throws Exception {
        start(false);
        sample(() -> app.controller.play(), 0, 80);
        for (int target : new int[] {110, 75, 80}) {
            seekAndSample(target);
        }
    }

    @Test
    public void completeEpisodeReplacementExposesBothEditions() throws Exception {
        start(false);
        sample(() -> app.controller.play(), 0, 80);
        FeedMedia second = addEpisode("B");
        select(second, 80);
        sample(() -> app.controller.play(), 1, 80);
    }
}
