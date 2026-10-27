package de.test.antennapod.playback;

import android.content.Context;
import android.util.Log;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import de.danoeh.antennapod.playback.service.internal.ExoPlayerUtils;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.test.antennapod.util.service.download.HTTPBin;
import org.apache.commons.io.IOUtils;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import de.danoeh.antennapod.net.download.service.feed.remote.HttpDownloader;
import de.danoeh.antennapod.model.download.DownloadRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@LargeTest
@OptIn(markerClass = UnstableApi.class)
public class ResolvedUrlPlaybackTest {
    private static final String TAG = "ResolvedUrlPlaybackTest";
    private final HTTPBin server = new HTTPBin();
    private Context context;
    private ExoPlayer player;
    private PcmSink sink;
    private File initial;
    private File changed;
    private volatile long playerPosition;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UserPreferences.init(context);
        initial = copyAsset("ad-2s.mp3");
        changed = copyAsset("ad-6s.mp3");
        server.start();
    }

    private File copyAsset(String name) throws Exception {
        File file = File.createTempFile(name, ".mp3", context.getCacheDir());
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("dynamic-ads/" + name);
                FileOutputStream output = new FileOutputStream(file)) {
            IOUtils.copy(input, output);
        }
        return file;
    }

    @After
    public void tearDown() {
        server.releaseResolvedGate();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (player != null) {
                player.release();
            }
            ExoPlayerUtils.releaseCache();
        });
        server.stop();
        if (initial != null) {
            initial.delete();
        }
        if (changed != null) {
            changed.delete();
        }
    }

    @Test
    public void stableResolvedUrlAcrossPlayerRestartPreservesContent() throws Exception {
        assertRestartedContent(false, false);
    }

    @Test
    public void freshResolutionAfterPlayerRestartPreservesContent() throws Exception {
        assertRestartedContent(true, false);
    }

    @Test
    public void freshResolutionWithoutCachedBytesPreservesContent() throws Exception {
        assertRestartedContent(true, true);
    }

    private void assertRestartedContent(boolean switchResolution, boolean clearCache) throws Exception {
        server.serveResolvedAds(initial, changed, true);
        MediaItem media = MediaItem.fromUri(server.getBaseUrl() + "/resolved-ad/original");
        playResolved(media, 0);
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(server::hasResolvedPrefix);
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> sink.consumedUs >= 17000000);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> player.stop());
        server.releaseResolvedGate();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            player.release();
            player = null;
            ExoPlayerUtils.releaseCache();
        });
        if (clearCache) {
            SimpleCache.delete(new File(context.getCacheDir(), "streaming"), new StandaloneDatabaseProvider(context));
        }
        if (switchResolution) {
            server.selectResolvedB();
        }
        playResolved(media, 17000);
        awaitResolvedContent();
        List<String> requests = server.getResolvedRequests();
        Log.i(TAG, "restart requests=" + requests);
        assertEquals(2, requests.stream().filter(request -> request.equals("HEAD original null")).count());
        if (clearCache) {
            assertEquals(3, requests.stream().filter(request -> request.startsWith("GET ")).count());
            assertTrue(requests.contains("GET B null"));
            assertTrue(requests.stream().anyMatch(request -> request.matches(
                    "GET B bytes=[1-9][0-9]*-" + (changed.length() - 1))));
        } else {
            assertEquals(2, requests.stream().filter(request -> request.startsWith("GET ")).count());
            assertTrue(requests.stream().anyMatch(request -> request.matches(
                    "GET " + (switchResolution ? "B" : "A") + " bytes=[1-9][0-9]*-" + (initial.length() - 1))));
        }
        assertConsumedContent("resolved restart changed=" + switchResolution + " emptyCache=" + clearCache);
    }

    @Test
    public void completeDownloadKeepsContentAfterFreshResolutionChanges() throws Exception {
        server.serveResolvedAds(initial, changed, false);
        File downloaded = new File(context.getCacheDir(), "resolved-download-" + System.nanoTime() + ".mp3");
        try {
            String url = server.getBaseUrl() + "/resolved-ad/original";
            DownloadRequest request = new DownloadRequest(downloaded.getAbsolutePath(), url, "Synthetic episode",
                    0, FeedMedia.FEEDFILETYPE_FEEDMEDIA, null, null, null, false);
            HttpDownloader downloader = new HttpDownloader(request);
            downloader.call();
            assertTrue(downloader.getResult().isSuccessful());
            try (InputStream expected = new FileInputStream(initial);
                    InputStream actual = new FileInputStream(downloaded)) {
                assertTrue(IOUtils.contentEquals(expected, actual));
            }
            final int requestsBeforePlayback = server.getResolvedRequests().size();
            server.selectResolvedB();
            playResolved(MediaItem.fromUri(downloaded.toURI().toString()), 17000);
            awaitResolvedContent();
            assertEquals(requestsBeforePlayback, server.getResolvedRequests().size());
            assertConsumedContent("completed local download");
        } finally {
            downloaded.delete();
        }
    }

    private void playResolved(MediaItem media, long position) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            player = ExoPlayerUtils.buildPlayer(context, new DefaultRenderersFactory(context) {
                @Override
                protected AudioSink buildAudioSink(Context context, boolean floatOutput, boolean playbackParams) {
                    sink = new PcmSink(super.buildAudioSink(context, false, false));
                    return sink;
                }
            });
            player.setSkipSilenceEnabled(false);
            player.setPlaybackSpeed(1);
            player.setMediaItem(media, position);
            player.prepare();
            player.play();
        });
    }

    private void awaitResolvedContent() {
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertEquals("Player error", null, player.getPlayerError());
                playerPosition = player.getCurrentPosition();
            });
            return sink.consumedUs >= 27000000;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> player.pause());
    }

    private void assertConsumedContent(String scenario) {
        assertFalse("PCM was flushed before it could be consumed", sink.flushedAfterPcm);
        List<double[]> windows = sink.consumedWindows();
        int laterWindows = 0;
        double worstError = 0;
        for (double[] window : windows) {
            double mediaTime = window[0];
            double expected = mediaTime - 2;
            double observed = (window[1] - 20) / ((10000.0 - 20) / 120);
            double error = observed - expected;
            Log.i(TAG, "variant=" + scenario + " media=" + mediaTime
                    + " expectedContent=" + expected + " observedContent=" + observed
                    + " error=" + error + " consumed=" + sink.consumedUs / 1000000.0
                    + " playerPosition=" + playerPosition / 1000.0);
            worstError = Math.max(worstError, Math.abs(error));
            if (mediaTime > 22) {
                laterWindows++;
            }
        }
        assertTrue("Not enough consumed PCM after restart", laterWindows >= 8);
        assertTrue("Decoded content skipped/repeated: maximum error=" + worstError + " seconds",
                worstError < 0.25);
    }

    private static class PcmSink extends ForwardingAudioSink {
        private final List<double[]> windows = new ArrayList<>();
        private int sampleRate;
        private ByteBuffer pending;
        private int initialPosition;
        private double previous;
        private double firstCrossing;
        private double lastCrossing;
        private int crossings;
        private int samples;
        private long streamOffsetUs;
        private volatile long consumedUs;
        private volatile boolean flushedAfterPcm;

        PcmSink(AudioSink delegate) {
            super(delegate);
        }

        @Override
        public void configure(Format format, int bufferSize, int[] channels) throws ConfigurationException {
            assertEquals(C.ENCODING_PCM_16BIT, format.pcmEncoding);
            assertEquals(1, format.channelCount);
            sampleRate = format.sampleRate;
            super.configure(format, bufferSize, channels);
        }

        @Override
        public void setOutputStreamOffsetUs(long offsetUs) {
            streamOffsetUs = offsetUs;
            super.setOutputStreamOffsetUs(offsetUs);
        }

        @Override
        public synchronized boolean handleBuffer(ByteBuffer buffer, long timeUs, int units)
                throws InitializationException, WriteException {
            if (pending != buffer) {
                pending = buffer;
                initialPosition = buffer.position();
            }
            ByteBuffer pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            boolean handled = super.handleBuffer(buffer, timeUs, units);
            pcm.limit(buffer.position());
            while (pcm.remaining() >= 2) {
                double time = (timeUs - streamOffsetUs) / 1000000.0
                        + (pcm.position() - initialPosition) / (2.0 * sampleRate);
                double value = pcm.getShort();
                if (previous <= 0 && value > 0) {
                    double crossing = time - value / (value - previous) / sampleRate;
                    if (crossings++ == 0) {
                        firstCrossing = crossing;
                    }
                    lastCrossing = crossing;
                }
                previous = value;
                if (++samples == sampleRate / 4) {
                    if (crossings > 1) {
                        windows.add(new double[] {(firstCrossing + lastCrossing) / 2,
                                (crossings - 1) / (lastCrossing - firstCrossing)});
                    }
                    samples = 0;
                    crossings = 0;
                }
            }
            if (handled) {
                pending = null;
            }
            return handled;
        }

        @Override
        public long getCurrentPositionUs(boolean ended) {
            long position = super.getCurrentPositionUs(ended);
            consumedUs = position == CURRENT_POSITION_NOT_SET ? 0 : position - streamOffsetUs;
            return position;
        }

        @Override
        public synchronized void flush() {
            flushedAfterPcm |= !windows.isEmpty();
            super.flush();
        }

        synchronized List<double[]> consumedWindows() {
            List<double[]> consumed = new ArrayList<>();
            for (double[] window : windows) {
                if (window[0] + 0.25 < consumedUs / 1000000.0) {
                    consumed.add(window);
                }
            }
            return consumed;
        }
    }
}
