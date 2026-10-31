package de.test.antennapod.playback.hazards;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.download.DownloadRequest;
import de.danoeh.antennapod.net.download.service.feed.remote.HttpDownloader;
import de.danoeh.antennapod.playback.base.RewindAfterPauseUtils;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;
import de.test.antennapod.playback.hazards.support.PlaybackCase;
import org.awaitility.Awaitility;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Healthy controls for paused audio, seeking, replacement, saved position and completed downloads.
 * Pause coverage follows AntennaPod #8487; download coverage follows Podverse #1350's streaming-only report.
 * All pass unfixed; service state and known audio calibrate the harness independently of changed responses.
 */
@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class PlaybackLifecycleTest extends PlaybackCase {
    // Connect without preparing media -> stop -> reconnect -> stop; both stops clear the controller.
    @Test
    public void unpreparedServiceCanBeStoppedAndConnectedAgain() throws Exception {
        try {
            app.setUp("tagged-a.mp3", "tagged-b.mp3");
            app.connect();
            app.stopService();
            assertNull(app.controller);
            app.connect();
            app.stopService();
            assertNull(app.controller);
        } finally {
            app.tearDown();
        }
    }

    // Prepare A paused -> observe no played PCM -> play and recognize A at media time 80 s.
    @Test
    public void pausedPreparationDoesNotCountQueuedAudio() throws Exception {
        start(false);
        awaitReady();
        SystemClock.sleep(2000);
        assertTrue("Paused preparation played audio", audio.heard().isEmpty());
        sample(() -> app.controller.play(), 0, 80);
    }

    // Play A at 80 s -> seek to 110, 75 and 80 s -> recognize the corresponding program audio.
    @Test
    public void ordinarySeeksExposeKnownProgramPassages() throws Exception {
        start(false);
        sample(() -> app.controller.play(), 0, 80);
        for (int target : new int[] {110, 75, 80}) {
            seekAndSample(target);
        }
    }

    // Play A at 80 s -> select independent B -> recognize B at 80 s.
    @Test
    public void completeEpisodeReplacementExposesBothEditions() throws Exception {
        start(false);
        sample(() -> app.controller.play(), 0, 80);
        FeedMedia second = addEpisode("B");
        select(second, 80);
        sample(() -> app.controller.play(), 1, 80);
    }

    // Play A -> pause six seconds -> switch fresh resolution to B -> resume and replay fixed passages.
    @Test
    public void pauseResumeKeepsPassagesReachable() throws Exception {
        start(false);
        sample(() -> app.controller.play(), 0, 80);
        app.onMain(() -> app.controller.pause());
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() ->
                PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PAUSED);
        SystemClock.sleep(6000);
        app.server.switchFreshResolutionsToB();
        int first = audio.heard().size();
        app.onMain(() -> app.controller.play());
        sampledContent(first, false);
        checkPassages();
    }

    // Play A -> stop and read saved position -> reconnect/play -> verify normal rewind and replay passages.
    @Test
    public void restartUsesFinalPersistedPositionAndNormalRewind() throws Exception {
        start(false);
        sample(() -> app.controller.play(), 0, 80);
        app.stopService();
        FeedMedia saved = DBReader.getFeedMedia(app.media.getId());
        assertTrue(saved.getPosition() > RewindAfterPauseUtils.MINIMUM_REWIND);
        AtomicLong restored = new AtomicLong(-1);
        AtomicLong restoredAt = new AtomicLong();
        app.connect();
        app.onMain(() -> app.controller.addListener((Player.Listener) Proxy.newProxyInstance(
                Player.Listener.class.getClassLoader(), new Class<?>[] {Player.Listener.class},
                (listener, method, arguments) -> {
                    switch (method.getName()) {
                        case "onMediaItemTransition":
                            if (arguments[0] != null) {
                                restored.set(app.controller.getCurrentPosition());
                                restoredAt.set(System.currentTimeMillis());
                            }
                            return null;
                        case "hashCode": return System.identityHashCode(listener);
                        case "equals": return listener == arguments[0];
                        case "toString": return "SavedPositionObserver";
                        default: return null;
                    }
                })));
        int first = audio.heard().size();
        app.onMain(() -> app.controller.play());
        sampledContent(first, false);
        long elapsed = restoredAt.get() - saved.getLastPlayedTimeStatistics();
        assertTrue("Setup exceeded short-pause interval: " + elapsed,
                elapsed > 0 && elapsed < RewindAfterPauseUtils.ELAPSED_TIME_FOR_SHORT_REWIND);
        assertEquals(saved.getPosition() - RewindAfterPauseUtils.MINIMUM_REWIND, restored.get());
        checkPassages();
    }

    // Download A -> mark it local -> resolve B on the server -> play A with no network body bytes.
    @Test
    public void completedDownloadKeepsPassagesWithoutStreamingAgain() throws Exception {
        start(false);
        awaitFullyBuffered();
        app.stopService();
        File downloaded = File.createTempFile("episode-download", ".mp3", app.context.getCacheDir());
        try {
            DownloadRequest request = new DownloadRequest(downloaded.getAbsolutePath(),
                    app.media.getDownloadUrl(), "Synthetic episode", 0, FeedMedia.FEEDFILETYPE_FEEDMEDIA,
                    null, null, null, false);
            HttpDownloader downloader = new HttpDownloader(request);
            downloader.call();
            assertTrue(downloader.getResult().isSuccessful());
            try (InputStream expected = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open("dynamic-ads/tagged-a.mp3");
                    InputStream actual = new FileInputStream(downloaded)) {
                assertTrue("Download differs from A", IOUtils.contentEquals(expected, actual));
            }
            app.media.setLocalFileUrl(downloaded.getAbsolutePath());
            app.media.setDownloaded(true, System.currentTimeMillis());
            DBWriter.setFeedMedia(app.media).get(5, TimeUnit.SECONDS);
            app.server.switchFreshResolutionsToB();
            final long before = app.server.getBodyBytes();
            app.connect();
            select(app.media, 80);
            sample(() -> app.controller.play(), 0, 80);
            checkPassages();
            long bytes = app.server.getBodyBytes() - before;
            Log.i("ServiceAudioObserver", "download playback responseBytes=" + bytes);
            assertEquals("Local playback fetched episode bytes", 0, bytes);
        } finally {
            app.stopService();
            downloaded.delete();
        }
    }
}
