package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import de.danoeh.antennapod.model.feed.FeedMedia;

import org.awaitility.Awaitility;
import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;
import de.test.antennapod.playback.hazards.support.PlaybackCase;
import de.test.antennapod.playback.hazards.support.ResolvedTaggedAudio;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/**
 * Fresh resolution selects a shorter B while the resolved A and B URLs remain independently stable.
 * Michael's captures found a 60.055 s interior displacement; ad placement and sweep bands here are synthetic.
 * Partially play/cache A -> stop service -> resolve B -> resume and replay fixed program passages.
 * Unfixed enclosure-keyed caching mixes A/B bytes and misses a passage; the three healthy controls pass.
 */
@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class ResolutionChangeTest extends PlaybackCase {
    @Test
    public void freshResolutionKeepsNamedPassagesReachableThroughServiceRestart() throws Exception {
        start(false, 70, true);
        sample(() -> app.controller.play(), 0, 70);
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
            List<ResolvedTaggedAudio.Heard> heard = audio.heard();
            return app.server.hasSentPrefix() && !heard.isEmpty()
                    && heard.get(heard.size() - 1).program >= 25;
        });
        app.stopService();
        app.server.switchFreshResolutionsToB();
        app.server.resumeResponses();
        app.connect();
        int first = audio.heard().size();
        app.onMain(() -> app.controller.play());
        sampledContent(first, false);
        for (int replay = 0; replay < 2; replay++) {
            for (int program : new int[] {75, 105, 90}) {
                boolean reached = reaches(program);
                if (replay == 1) {
                    assertTrue("Program passage " + program + " unavailable on the fixed replay", reached);
                }
            }
        }
    }

    @Test
    public void identicalBytesAtDifferentUrlsStayObservable() throws Exception {
        start(true);
        sample(() -> app.controller.play(), 0, 80);
        FeedMedia second = addEpisode("B");
        select(second, 80);
        sample(() -> app.controller.play(), 0, 80);
    }

    @Test
    public void originalEditionKeepsNamedPassagesReachable() throws Exception {
        start(false);
        sample(() -> app.controller.play(), 0, 80);
        for (int program : new int[] {75, 105, 90}) {
            assertTrue("Original edition passage " + program, reaches(program));
        }
    }

    @Test
    public void replacementEditionKeepsNamedPassagesReachable() throws Exception {
        start(false);
        select(addEpisode("B"), 80);
        sample(() -> app.controller.play(), 1, 80);
        for (int program : new int[] {75, 105, 90}) {
            assertTrue("Replacement edition passage " + program, reaches(program));
        }
    }
}
