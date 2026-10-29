package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;

@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class SameUrlChangeTest extends PlaybackCase {
    @Test
    public void differentLengthMutationKeepsPassagesReachable() throws Exception {
        primeCachedEpisode(false);
        app.server.replaceAWithB();
        restartAndCheckPassages();
    }

    @Test
    public void equalLengthMutationKeepsPassagesReachable() throws Exception {
        primeCachedEpisode(true);
        app.server.replaceAWithB();
        restartAndCheckPassages();
    }

    @Test
    public void equalLengthEditionPlaysKnownPassages() throws Exception {
        start("tagged-equal-length.mp3", 80, false);
        select(addEpisode("B"), 80);
        sample(() -> app.controller.play(), 1, 80);
        checkPassages();
    }
}
