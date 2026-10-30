package de.test.antennapod.playback.hazards;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.platform.app.InstrumentationRegistry;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.service.Media3PlaybackService;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.test.antennapod.EspressoTestUtils;

import org.apache.commons.io.IOUtils;
import org.awaitility.Awaitility;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;

@OptIn(markerClass = UnstableApi.class)
final class ServicePlaybackFixture {
    final ResolvedEpisodeServer server = new ResolvedEpisodeServer();
    Context context;
    MediaController controller;
    private File initial;
    private File changed;
    FeedMedia media;

    void setUp(String first, String second) throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        stopService();
        deleteCache();
        EspressoTestUtils.clearPreferences();
        EspressoTestUtils.clearDatabase();
        UserPreferences.setAllowMobileStreaming(true);
        initial = copyAsset(first);
        changed = copyAsset(second);
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

    void connect() throws Exception {
        SessionToken token = new SessionToken(context, new ComponentName(context, Media3PlaybackService.class));
        controller = new MediaController.Builder(context, token).setApplicationLooper(Looper.getMainLooper())
                .buildAsync().get(30, TimeUnit.SECONDS);
    }

    void stopService() throws Exception {
        try {
            if (controller != null) {
                AtomicBoolean hasMedia = new AtomicBoolean();
                onMain(() -> {
                    hasMedia.set(controller.getCurrentMediaItem() != null);
                    controller.pause();
                });
                if (hasMedia.get()) {
                    Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() ->
                            PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PAUSED);
                }
            }
        } finally {
            try {
                if (controller != null) {
                    onMain(() -> {
                        controller.release();
                        controller = null;
                    });
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                }
            } finally {
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

    void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
