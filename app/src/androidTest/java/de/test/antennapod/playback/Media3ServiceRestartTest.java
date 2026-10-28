package de.test.antennapod.playback;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.danoeh.antennapod.playback.base.RewindAfterPauseUtils;
import de.danoeh.antennapod.playback.service.Media3PlaybackService;
import de.danoeh.antennapod.playback.service.internal.StreamCacheGeneration;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.test.antennapod.EspressoTestUtils;
import de.danoeh.antennapod.event.PlayerErrorEvent;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import org.apache.commons.io.IOUtils;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@LargeTest
@OptIn(markerClass = UnstableApi.class)
public class Media3ServiceRestartTest {
    private final ResolvedEpisodeServer server = new ResolvedEpisodeServer();
    private Context context;
    private MediaController controller;
    private File initial;
    private File changed;
    private FeedMedia media;
    private volatile long position;
    private volatile long restoredPosition = -1;
    private volatile long restoredAt;
    private volatile boolean recoveryRejected;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        stopService();
        deleteCache();
        EspressoTestUtils.clearPreferences();
        EspressoTestUtils.clearDatabase();
        UserPreferences.setAllowMobileStreaming(true);
        initial = copyAsset("resolved-a.mp3");
        changed = copyAsset("resolved-b.mp3");
        server.start(initial, changed);
        server.throttleResponsesAfter(512000);
        Feed feed = new Feed(server.getBaseUrl() + "/feed", null, "Synthetic subscription", null, null);
        FeedItem item = new FeedItem(0, "Synthetic episode", "episode", null, new Date(), FeedItem.UNPLAYED, feed);
        media = new FeedMedia(item, server.getBaseUrl() + "/resolved-ad/original", initial.length(), "audio/mpeg");
        item.setMedia(media);
        feed.setItems(Collections.singletonList(item));
        PodDBAdapter adapter = PodDBAdapter.getInstance().open();
        adapter.setCompleteFeed(feed);
        adapter.close();
        assertTrue(media.getId() > 0);
    }

    @After
    public void tearDown() throws Exception {
        server.resumeResponses();
        try {
            stopService();
            deleteCache();
        } finally {
            try {
                server.stop();
            } finally {
                if (initial != null) {
                    initial.delete();
                }
                if (changed != null) {
                    changed.delete();
                }
            }
        }
    }

    @Test
    public void pauseResumeContinuesSameEpisode() throws Exception {
        startAndPause();
        final long pausedPosition = position;
        server.switchFreshResolutionsToB();
        server.resumeResponses();
        onMain(() -> controller.play());
        awaitPosition(pausedPosition + 3000);
        List<String> requests = server.getRequests();
        Log.i("Media3ServiceRestartTest", "pause/resume requests=" + requests);
        onMain(() -> assertEquals(String.valueOf(media.getId()), controller.getCurrentMediaItem().mediaId));
    }

    @Test
    public void serviceRestartRestoresSavedPosition() throws Exception {
        startAndPause();
        stopService();
        Log.i("Media3ServiceRestartTest", "pausedControllerPosition=" + position
                + " savedAfterShutdown=" + DBReader.getFeedMedia(media.getId()).getPosition());
        FeedMedia saved = DBReader.getFeedMedia(media.getId());
        final int savedPosition = saved.getPosition();
        assertTrue("Saved position must permit the full short-pause rewind",
                savedPosition > RewindAfterPauseUtils.MINIMUM_REWIND);
        final long pausedAt = saved.getLastPlayedTimeStatistics();
        assertTrue("Missing pause timestamp", pausedAt > 0);
        server.resumeResponses();
        server.switchFreshResolutionsToB();
        connect();
        onMain(() -> {
            assertEquals(0, controller.getMediaItemCount());
            controller.addListener((Player.Listener) Proxy.newProxyInstance(Player.Listener.class.getClassLoader(),
                    new Class<?>[] {Player.Listener.class}, (listener, method, arguments) -> {
                        switch (method.getName()) {
                            case "onMediaItemTransition":
                                restoredPosition = controller.getCurrentPosition();
                                restoredAt = System.currentTimeMillis();
                                return null;
                            case "hashCode":
                                return System.identityHashCode(listener);
                            case "equals":
                                return listener == arguments[0];
                            case "toString":
                                return "RestartPositionListener";
                            default:
                                return null;
                        }
                    }));
            controller.play();
        });
        awaitPosition(savedPosition + 3000);
        onMain(() -> {
            controller.pause();
            assertNotNull(controller.getCurrentMediaItem());
            assertEquals(String.valueOf(media.getId()), controller.getCurrentMediaItem().mediaId);
        });
        List<String> requests = server.getRequests();
        Log.i("Media3ServiceRestartTest", "savedPosition=" + savedPosition + " restoredPosition=" + restoredPosition
                + " resumedPosition=" + position
                + " requests=" + requests);
        assertTrue("Setup exceeded the short-pause interval", restoredAt > pausedAt
                && restoredAt - pausedAt < RewindAfterPauseUtils.ELAPSED_TIME_FOR_SHORT_REWIND);
        assertEquals("Saved position and normal short-pause rewind", savedPosition
                - RewindAfterPauseUtils.MINIMUM_REWIND, restoredPosition);
    }

    @Test
    public void expiredCachedUrlRecoversThroughFreshServiceSource() throws Exception {
        startAndPause();
        String before = generation();
        int requests = server.getRequests().size();
        server.expireA();
        server.switchFreshResolutionsToB();
        server.resumeResponses();
        onMain(() -> {
            controller.seekTo(110000);
            controller.play();
        });
        awaitRecoveredPosition(113000);
        assertNotEquals(before, generation());
        List<String> recovery = server.getRequests();
        recovery = recovery.subList(requests, recovery.size());
        assertTrue(recovery.toString(), hasRequest(recovery, "GET A "));
        assertTrue(recovery.toString(), hasRequest(recovery, "GET B "));
        assertRecoveredSeeks();
    }

    @Test
    public void changedLengthRecoversThroughFreshServiceSource() throws Exception {
        startAndPause();
        String before = generation();
        server.replaceAWithB();
        server.resumeResponses();
        onMain(() -> {
            controller.seekTo(110000);
            controller.play();
        });
        awaitRecoveredPosition(113000);
        assertNotEquals(before, generation());
        assertRecoveredSeeks();
    }

    @Test
    public void unchangedForbiddenUrlDoesNotRotateCacheGeneration() throws Exception {
        startAndPause();
        String before = generation();
        server.expireA();
        server.resumeResponses();
        EventBus.getDefault().register(this);
        try {
            onMain(() -> {
                controller.seekTo(110000);
                controller.play();
            });
            Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> recoveryRejected);
            assertEquals(before, generation());
            onMain(() -> assertNotNull(controller.getPlayerError()));
        } finally {
            EventBus.getDefault().unregister(this);
        }
    }

    @Subscribe
    public void onRecoveryRejected(PlayerErrorEvent event) {
        recoveryRejected = true;
    }

    private boolean hasRequest(List<String> requests, String prefix) {
        for (String request : requests) {
            if (request.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String generation() {
        return new StreamCacheGeneration(context, server.getBaseUrl() + "/resolved-ad/original").token;
    }

    private void awaitRecoveredPosition(long minimum) {
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
            final boolean[] ready = {false};
            onMain(() -> {
                position = controller.getCurrentPosition();
                ready[0] = controller.getPlayerError() == null && controller.getPlaybackState() == Player.STATE_READY
                        && position >= minimum;
            });
            return ready[0];
        });
        onMain(() -> assertEquals(String.valueOf(media.getId()), controller.getCurrentMediaItem().mediaId));
        Log.i("Media3ServiceRestartTest", "recoveredPosition=" + position + " requests=" + server.getRequests());
    }

    private void assertRecoveredSeeks() {
        String recovered = generation();
        for (long target : new long[] {80000, 110000, 75000}) {
            onMain(() -> controller.seekTo(target));
            awaitPosition(target + 1500);
            assertEquals(recovered, generation());
        }
    }

    private void startAndPause() throws Exception {
        connect();
        onMain(() -> {
            controller.setMediaItem(MediaItemAdapter.fromMediaIdStub(media.getId()));
            controller.prepare();
            controller.play();
        });
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(server::hasSentPrefix);
        awaitPosition(17000);
        onMain(() -> {
            controller.pause();
            position = controller.getCurrentPosition();
        });
    }

    private void connect() throws Exception {
        SessionToken token = new SessionToken(context, new ComponentName(context, Media3PlaybackService.class));
        controller = new MediaController.Builder(context, token).setApplicationLooper(Looper.getMainLooper())
                .buildAsync().get(30, TimeUnit.SECONDS);
    }

    private void awaitPosition(long minimum) {
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
            onMain(() -> {
                assertNull(controller.getPlayerError());
                position = controller.getCurrentPosition();
            });
            return position >= minimum;
        });
    }

    private void stopService() throws Exception {
        if (controller != null) {
            onMain(() -> controller.pause());
            Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() ->
                    PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PAUSED);
            onMain(() -> {
                controller.release();
                controller = null;
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        ComponentName service = new ComponentName(context, Media3PlaybackService.class);
        context.stopService(new Intent().setComponent(service));
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> {
            for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (service.equals(info.service)) {
                    return false;
                }
            }
            return true;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        FutureTask<Void> databaseIdle = new FutureTask<>(DBWriter::tearDownTests, null);
        new Thread(databaseIdle, "TestDatabaseBarrier").start();
        try {
            databaseIdle.get(5, TimeUnit.SECONDS);
        } finally {
            databaseIdle.cancel(true);
        }
    }

    private void deleteCache() {
        StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(context);
        try {
            SimpleCache.delete(new File(context.getCacheDir(), "streaming"), database);
        } finally {
            database.close();
        }
    }

    private File copyAsset(String name) throws Exception {
        File file = File.createTempFile(name, ".mp3", context.getCacheDir());
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("dynamic-ads/" + name); FileOutputStream output = new FileOutputStream(file)) {
            IOUtils.copy(input, output);
        }
        return file;
    }

    private void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
