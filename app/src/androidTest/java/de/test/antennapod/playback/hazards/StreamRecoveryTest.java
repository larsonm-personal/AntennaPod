package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.test.antennapod.IgnoreOnCi;
import de.test.antennapod.playback.hazards.support.PlaybackCase;

import org.awaitility.Awaitility;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Checks that replacing an expired stream preserves the surrounding controller playlist.
 * Synthetic regression from review of the proposed recovery code; not part of the independent hazard suite.
 * Play A between two items -> expire and interrupt A -> recover B -> verify playlist and played B audio.
 * Unfixed playback cannot recover A here; a replacement that clears the playlist also fails this check.
 */
@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class StreamRecoveryTest extends PlaybackCase {
    @Test
    public void recoveryPreservesTheControllerPlaylist() throws Exception {
        primePlayback(false);
        FeedMedia before = addEpisode("pressure/before");
        FeedMedia after = addEpisode("B");
        app.onMain(() -> {
            app.controller.pause();
            app.controller.addMediaItem(0, MediaItemAdapter.fromMediaIdStub(before.getId()));
            app.controller.addMediaItem(MediaItemAdapter.fromMediaIdStub(after.getId()));
        });
        Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> app.onMain(() -> {
            assertEquals(3, app.controller.getMediaItemCount());
            assertEquals(1, app.controller.getCurrentMediaItemIndex());
        }));
        app.server.expireA();
        app.server.switchFreshResolutionsToB();
        app.server.disconnectActiveResponses();
        app.server.resumeResponses();
        app.onMain(() -> app.controller.seekTo(110000));
        Awaitility.await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> app.onMain(() -> {
            assertTrue(app.server.getExpiredResponses() > 0);
            assertEquals(Player.STATE_READY, app.controller.getPlaybackState());
            assertTrue(app.controller.getDuration() > 0 && app.controller.getDuration() < 170000);
        }));
        app.onMain(() -> {
            assertEquals(3, app.controller.getMediaItemCount());
            assertEquals(1, app.controller.getCurrentMediaItemIndex());
            long[] ids = {before.getId(), app.media.getId(), after.getId()};
            for (int index = 0; index < ids.length; index++) {
                assertEquals(String.valueOf(ids[index]), app.controller.getMediaItemAt(index).mediaId);
            }
        });
        sample(() -> app.controller.play(), 1, 110);
    }
}
