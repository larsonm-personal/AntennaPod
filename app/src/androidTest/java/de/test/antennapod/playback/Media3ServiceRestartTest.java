package de.test.antennapod.playback;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.danoeh.antennapod.playback.service.Media3PlaybackService;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.test.antennapod.EspressoTestUtils;
import de.test.antennapod.util.service.download.HTTPBin;
import org.apache.commons.io.IOUtils;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@LargeTest
@OptIn(markerClass = UnstableApi.class)
public class Media3ServiceRestartTest {
    private final HTTPBin server = new HTTPBin();
    private Context context;
    private MediaController controller;
    private File initial;
    private File changed;
    private FeedMedia media;
    private volatile long position;
    private volatile long restoredPosition = -1;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        stopService();
        EspressoTestUtils.clearPreferences();
        EspressoTestUtils.clearDatabase();
        UserPreferences.setAllowMobileStreaming(true);
        initial = copyAsset("ad-2s.mp3");
        changed = copyAsset("ad-6s.mp3");
        server.start();
        server.serveResolvedAds(initial, changed, true);
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
        stopService();
        server.releaseResolvedGate();
        server.stop();
        if (initial != null) {
            initial.delete();
        }
        if (changed != null) {
            changed.delete();
        }
    }

    @Test
    public void pauseResumeKeepsResolvedUrl() throws Exception {
        startAndPause();
        final long pausedPosition = position;
        server.selectResolvedB();
        server.releaseResolvedGate();
        onMain(() -> controller.play());
        awaitPosition(pausedPosition + 3000);
        List<String> requests = server.getResolvedRequests();
        Log.i("Media3ServiceRestartTest", "pause/resume requests=" + requests);
        assertEquals(1, requests.stream().filter(request -> request.equals("HEAD original null")).count());
        assertTrue(requests.stream().anyMatch(request -> request.startsWith("GET A bytes=")));
        assertTrue(requests.stream().noneMatch(request -> request.startsWith("GET B")));
    }

    @Test
    public void serviceRestartRestoresPositionAndResolvesAgain() throws Exception {
        startAndPause();
        stopService();
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(
                () -> DBReader.getFeedMedia(media.getId()).getPosition() >= 17000);
        final int savedPosition = DBReader.getFeedMedia(media.getId()).getPosition();
        server.releaseResolvedGate();
        server.selectResolvedB();
        connect();
        onMain(() -> {
            assertEquals(0, controller.getMediaItemCount());
            controller.addListener(new Player.Listener() {
                @Override
                public void onMediaItemTransition(MediaItem item, int reason) {
                    restoredPosition = controller.getCurrentPosition();
                }
            });
            controller.play();
        });
        awaitPosition(savedPosition + 3000);
        onMain(() -> {
            controller.pause();
            assertNotNull(controller.getCurrentMediaItem());
            assertEquals(String.valueOf(media.getId()), controller.getCurrentMediaItem().mediaId);
        });
        List<String> requests = server.getResolvedRequests();
        Log.i("Media3ServiceRestartTest", "savedPosition=" + savedPosition + " restoredPosition=" + restoredPosition
                + " resumedPosition=" + position
                + " requests=" + requests);
        assertTrue("Saved position was not restored", restoredPosition >= savedPosition - 3000
                && restoredPosition <= savedPosition);
        assertEquals(2, requests.stream().filter(request -> request.equals("HEAD original null")).count());
        assertTrue(requests.contains("HEAD B null"));
        assertTrue(requests.stream().anyMatch(request -> request.matches("GET B bytes=[1-9][0-9]*-.*")));
    }

    private void startAndPause() throws Exception {
        connect();
        onMain(() -> {
            controller.setMediaItem(MediaItemAdapter.fromMediaIdStub(media.getId()));
            controller.prepare();
            controller.play();
        });
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(server::hasResolvedPrefix);
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
                assertEquals(null, controller.getPlayerError());
                position = controller.getCurrentPosition();
            });
            return position >= minimum;
        });
    }

    private void stopService() {
        onMain(() -> {
            if (controller != null) {
                controller.pause();
                controller.release();
                controller = null;
            }
        });
        ComponentName service = new ComponentName(context, Media3PlaybackService.class);
        context.stopService(new Intent().setComponent(service));
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(
                () -> manager.getRunningServices(Integer.MAX_VALUE).stream()
                        .noneMatch(info -> service.equals(info.service)));
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
