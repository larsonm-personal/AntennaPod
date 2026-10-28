package de.test.antennapod.playback;

import android.content.Context;
import android.media.AudioFormat;
import android.os.FileObserver;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.PlayerMessage;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.audio.AudioOutput;
import androidx.media3.exoplayer.audio.AudioOutputProvider;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.ForwardingAudioOutput;
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import de.danoeh.antennapod.model.download.DownloadRequest;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.service.feed.remote.HttpDownloader;
import de.danoeh.antennapod.playback.service.internal.ExoPlayerUtils;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.apache.commons.io.IOUtils;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@LargeTest
@OptIn(markerClass = UnstableApi.class)
public class ResolvedUrlPlaybackTest {
    private static final String TAG = "ResolvedUrlPlaybackTest";
    private static final long INITIAL_SEEK_MS = 70000;
    private static final long RESUME_MS = 87000;
    private static final long MIN_OBSERVATION_END_MS = 97000;
    private static final int THROTTLE_AFTER_BYTES = 512000;
    private static final double INITIAL_AD_SECONDS = 2 + 2299 * 1152.0 / 44100;
    private static final int CHANGED_AD_SECONDS = 2;
    private static final double TOLERANCE_SECONDS = 0.25;
    private final ResolvedEpisodeServer server = new ResolvedEpisodeServer();
    private double observationBoundary = 90;
    private long observationEndMs = MIN_OBSERVATION_END_MS;
    private final List<FileObserver> fileObservers = new ArrayList<>();
    private final Set<Long> activeLoads = new HashSet<>();
    private Throwable loadError;
    private boolean mediaLoadObserved;
    private Context context;
    private ExoPlayer player;
    private volatile PcmOutput sink;
    private volatile boolean outputRecreated;
    private File initial;
    private File changed;
    private long resumeByte;
    private volatile long playerPosition;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UserPreferences.init(context);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(ExoPlayerUtils::releaseCache);
        deleteCache();
        initial = copyAsset("resolved-a.mp3");
        changed = copyAsset("resolved-b.mp3");
        resumeByte = frameOffsetAt(RESUME_MS);
        server.start(initial, changed);
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
    public void tearDown() throws Exception {
        for (FileObserver observer : fileObservers) {
            observer.stopWatching();
        }
        server.resumeResponses();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (player != null) {
                    player.release();
                }
                ExoPlayerUtils.releaseCache();
            });
        } finally {
            try {
                server.stop();
            } finally {
                try {
                    deleteCache();
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
    }

    @Test
    public void stableResolvedUrlAcrossPlayerRestartPreservesContent() throws Exception {
        assertRestartedContent(false, false);
    }

    @Test
    public void freshResolutionDoesNotMixRepresentations() throws Exception {
        assertRestartedContent(true, false);
    }

    @Test
    public void emptyCachePlaysCoherentBAtRestoredTimestamp() throws Exception {
        assertRestartedContent(true, true);
    }

    private void assertRestartedContent(boolean switchResolution, boolean clearCache) throws Exception {
        server.throttleResponsesAfter(THROTTLE_AFTER_BYTES);
        MediaItem media = MediaItem.fromUri(server.getBaseUrl() + "/resolved-ad/original");
        playResolved(media, INITIAL_SEEK_MS);
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(server::hasSentPrefix);
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(
                () -> sink != null && sink.consumedUs >= RESUME_MS * 1000);
        watchCache(new File(context.getCacheDir(), "streaming"));
        logCacheFiles("before stop", new File(context.getCacheDir(), "streaming"));
        awaitLoaderClosed();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> player.stop());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            player.release();
            player = null;
            ExoPlayerUtils.releaseCache();
        });
        logCacheFiles("after player/cache release", new File(context.getCacheDir(), "streaming"));
        server.resumeResponses();
        long cachedEndBytes = cachedEndAtResume();
        double cacheEnd = frameTimeAt(cachedEndBytes);
        if (cacheEnd > RESUME_MS / 1000.0 + 2 && cachedEndBytes < initial.length()) {
            observationBoundary = cacheEnd;
            observationEndMs = Math.max(MIN_OBSERVATION_END_MS, (long) ((cacheEnd + 3) * 1000));
        }
        Log.i(TAG, "cachedEndBytes=" + cachedEndBytes + " cacheEndSeconds=" + cacheEnd);
        if (clearCache) {
            deleteCache();
            assertEquals(0, cachedEndAtResume());
        }
        if (switchResolution) {
            server.switchFreshResolutionsToB();
        }
        logCacheFiles("before new player", new File(context.getCacheDir(), "streaming"));
        playResolved(media, RESUME_MS);
        logCacheFiles("after new player", new File(context.getCacheDir(), "streaming"));
        awaitResolvedContent();
        Log.i(TAG, "restart requests=" + server.getRequests());
        assertConsumedContent("restart changed=" + switchResolution + " emptyCache=" + clearCache,
                !switchResolution ? INITIAL_AD_SECONDS : clearCache ? CHANGED_AD_SECONDS : 0);
    }

    @Test
    public void completeDownloadKeepsContentAfterFreshResolutionChanges() throws Exception {
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
            final int requestsBeforePlayback = server.getRequests().size();
            server.switchFreshResolutionsToB();
            playResolved(MediaItem.fromUri(downloaded.toURI().toString()), RESUME_MS);
            awaitResolvedContent();
            assertEquals(requestsBeforePlayback, server.getRequests().size());
            assertConsumedContent("completed local download", INITIAL_AD_SECONDS);
        } finally {
            downloaded.delete();
        }
    }

    private void playResolved(MediaItem media, long position) throws Exception {
        List<PlayerMessage> messages = new ArrayList<>();
        sink = null;
        outputRecreated = false;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activeLoads.clear();
            loadError = null;
            mediaLoadObserved = false;
            player = ExoPlayerUtils.buildPlayer(context);
            player.addAnalyticsListener((AnalyticsListener) Proxy.newProxyInstance(
                    AnalyticsListener.class.getClassLoader(), new Class<?>[] {AnalyticsListener.class},
                    (listener, method, arguments) -> {
                        String name = method.getName();
                        if (name.equals("hashCode")) {
                            return System.identityHashCode(listener);
                        }
                        if (name.equals("equals")) {
                            return listener == arguments[0];
                        }
                        if (name.equals("toString")) {
                            return "LoadCompletionListener";
                        }
                        if (name.equals("onLoadError")) {
                            loadError = (Throwable) arguments[3];
                        }
                        if ((name.equals("onLoadStarted") && arguments.length == 4)
                                || name.equals("onLoadCanceled") || name.equals("onLoadCompleted")) {
                            LoadEventInfo info = (LoadEventInfo) arguments[1];
                            MediaLoadData data = (MediaLoadData) arguments[2];
                            if (data.dataType == C.DATA_TYPE_MEDIA) {
                                if (name.equals("onLoadStarted")) {
                                    mediaLoadObserved = true;
                                    activeLoads.add(info.loadTaskId);
                                } else {
                                    activeLoads.remove(info.loadTaskId);
                                }
                                Log.i(TAG, name + " task=" + info.loadTaskId + " active=" + activeLoads);
                            }
                        }
                        return null;
                    }));
            AudioOutputProvider provider = new ForwardingAudioOutputProvider(
                    new AudioTrackAudioOutputProvider.Builder(context).build()) {
                @Override
                public AudioOutput getAudioOutput(OutputConfig config) throws InitializationException {
                    assertEquals(C.ENCODING_PCM_16BIT, config.encoding);
                    assertEquals(AudioFormat.CHANNEL_OUT_MONO, config.channelMask);
                    assertFalse(config.isOffload);
                    outputRecreated |= sink != null && sink.acceptedSamples != 0;
                    sink = new PcmOutput(super.getAudioOutput(config), position);
                    return sink;
                }
            };
            for (int index = 0; index < player.getRendererCount(); index++) {
                Renderer renderer = player.getRenderer(index);
                if (renderer.getTrackType() == C.TRACK_TYPE_AUDIO) {
                    messages.add(player.createMessage(renderer).setType(Renderer.MSG_SET_AUDIO_OUTPUT_PROVIDER)
                            .setPayload(provider).send());
                }
            }
        });
        assertEquals(1, messages.size());
        messages.get(0).blockUntilDelivered(5000);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            player.setSkipSilenceEnabled(false);
            player.setPlaybackSpeed(1);
            player.setMediaItem(media, position);
            player.prepare();
            player.play();
        });
    }

    private void awaitLoaderClosed() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue("No media load observed", mediaLoadObserved);
            player.pause();
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        });
        AtomicBoolean closed = new AtomicBoolean();
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertNull("Load failed", loadError);
                assertNull("Player error", player.getPlayerError());
                closed.set(!player.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO) && activeLoads.isEmpty());
            });
            return closed.get();
        });
        Log.i(TAG, "loaderClosedBeforeRelease=true");
        logCacheFiles("after loader closed", new File(context.getCacheDir(), "streaming"));
    }

    private void awaitResolvedContent() {
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertNull("Player error", player.getPlayerError());
                playerPosition = player.getCurrentPosition();
            });
            return sink != null && sink.consumedUs >= observationEndMs * 1000;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> player.pause());
    }

    private void assertConsumedContent(String scenario, double expectedAdSeconds) {
        assertFalse("Audio output was recreated during observation", outputRecreated);
        assertFalse("PCM output was flushed or released during observation", sink.discardedAfterPcm);
        List<double[]> windows = sink.consumedWindows();
        int before = 0;
        int after = 0;
        int initialBefore = 0;
        int changedAfter = 0;
        double worstA = 0;
        double worstB = 0;
        for (double[] window : windows) {
            double mediaTime = window[0];
            double observed = (window[1] - 20) / ((10000.0 - 20) / 120);
            double errorA = observed - (mediaTime - INITIAL_AD_SECONDS);
            double errorB = observed - (mediaTime - CHANGED_AD_SECONDS);
            Log.i(TAG, "scenario=" + scenario + " media=" + mediaTime + " program=" + observed
                    + " errorA=" + errorA + " errorB=" + errorB);
            worstA = Math.max(worstA, Math.abs(errorA));
            worstB = Math.max(worstB, Math.abs(errorB));
            if (mediaTime < observationBoundary - 0.5) {
                before++;
                if (Math.abs(errorA) < TOLERANCE_SECONDS) {
                    initialBefore++;
                }
            }
            if (mediaTime > observationBoundary + 0.5) {
                after++;
                if (Math.abs(errorB) < TOLERANCE_SECONDS) {
                    changedAfter++;
                }
            }
        }
        assertTrue("Not enough consumed PCM before boundary", before >= 4);
        assertTrue("Not enough consumed PCM after boundary", after >= 8);
        Log.i(TAG, "boundary=" + observationBoundary + " A-before=" + initialBefore + " B-after=" + changedAfter
                + " maxErrorA=" + worstA + " maxErrorB=" + worstB);
        double error = expectedAdSeconds == INITIAL_AD_SECONDS ? worstA
                : expectedAdSeconds == CHANGED_AD_SECONDS ? worstB : Math.min(worstA, worstB);
        assertTrue("Inconsistent decoded representation: maxErrorA=" + worstA + " maxErrorB=" + worstB
                + " A-before=" + initialBefore + " B-after=" + changedAfter, error < TOLERANCE_SECONDS);
    }

    private long cachedEndAtResume() throws Exception {
        StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(context);
        SimpleCache cache = new SimpleCache(new File(context.getCacheDir(), "streaming"),
                new NoOpCacheEvictor(), database);
        try (RandomAccessFile reference = new RandomAccessFile(initial, "r")) {
            long end = 0;
            for (String key : cache.getKeys()) {
                for (CacheSpan span : cache.getCachedSpans(key)) {
                    byte[] expected = new byte[(int) span.length];
                    byte[] actual = new byte[(int) span.length];
                    reference.seek(span.position);
                    reference.readFully(expected);
                    try (RandomAccessFile saved = new RandomAccessFile(span.file, "r")) {
                        saved.readFully(actual);
                    }
                    assertArrayEquals("Cached span differs from A", expected, actual);
                    Log.i(TAG, "cachedSpanStart=" + span.position + " length=" + span.length
                            + " physical=" + span.file.length() + " file=" + span.file + " key=" + key);
                }
                long length = cache.getCachedLength(key, resumeByte, initial.length() - resumeByte);
                if (length > 0) {
                    end = Math.max(end, resumeByte + length);
                }
            }
            return end;
        } finally {
            logCacheFiles("before probe release", new File(context.getCacheDir(), "streaming"));
            cache.release();
            logCacheFiles("after probe release", new File(context.getCacheDir(), "streaming"));
            database.close();
        }
    }

    private void watchCache(File directory) {
        FileObserver observer = new FileObserver(directory.getAbsolutePath(), FileObserver.MODIFY
                | FileObserver.CLOSE_WRITE | FileObserver.CREATE | FileObserver.DELETE
                | FileObserver.MOVED_FROM | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                File file = path == null ? directory : new File(directory, path);
                Log.i(TAG, "cacheFileEvent=" + event + " file=" + file
                        + " exists=" + file.exists() + " physical=" + file.length());
            }
        };
        fileObservers.add(observer);
        observer.startWatching();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    watchCache(file);
                }
            }
        }
    }

    private void logCacheFiles(String stage, File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    logCacheFiles(stage, file);
                } else {
                    Log.i(TAG, "cacheSnapshot=" + stage + " file=" + file + " physical=" + file.length());
                }
            }
        }
    }

    private double frameTimeAt(long bytes) throws Exception {
        int frames = 0;
        try (RandomAccessFile file = new RandomAccessFile(initial, "r")) {
            while (file.getFilePointer() < bytes) {
                long start = file.getFilePointer();
                file.seek(start + frameSize(file.readInt()));
                frames++;
            }
        }
        return frames * 1152.0 / 44100;
    }

    private long frameOffsetAt(long timeMs) throws Exception {
        int frames = 0;
        try (RandomAccessFile file = new RandomAccessFile(initial, "r")) {
            while (frames * 1152.0 / 44100 < timeMs / 1000.0) {
                long start = file.getFilePointer();
                file.seek(start + frameSize(file.readInt()));
                frames++;
            }
            return file.getFilePointer();
        }
    }

    private int frameSize(int header) {
        assertEquals("Expected tagless MPEG-1 layer III at 192 kbps / 44.1 kHz",
                0xfffbb000, header & 0xfffffc00);
        return 144 * 192000 / 44100 + ((header >> 9) & 1);
    }

    private void deleteCache() {
        StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(context);
        try {
            SimpleCache.delete(new File(context.getCacheDir(), "streaming"), database);
        } finally {
            database.close();
        }
    }

    private static class PcmOutput extends ForwardingAudioOutput {
        private final List<double[]> windows = new ArrayList<>();
        private final int sampleRate;
        private final double startSeconds;
        private long acceptedSamples;
        private double previous;
        private double firstCrossing;
        private double lastCrossing;
        private int crossings;
        private int samples;
        private volatile long consumedUs;
        private volatile boolean discardedAfterPcm;

        PcmOutput(AudioOutput delegate, long startMs) {
            super(delegate);
            sampleRate = delegate.getSampleRate();
            startSeconds = startMs / 1000.0;
        }

        @Override
        public synchronized boolean write(ByteBuffer buffer, int units, long timeUs) throws WriteException {
            ByteBuffer pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            boolean handled = super.write(buffer, units, timeUs);
            pcm.limit(buffer.position());
            while (pcm.remaining() >= 2) {
                double time = startSeconds + acceptedSamples++ / (double) sampleRate;
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
            return handled;
        }

        @Override
        public long getPositionUs() {
            long position = super.getPositionUs();
            consumedUs = position < 0 ? 0 : position + (long) (startSeconds * 1000000);
            return position;
        }

        @Override
        public synchronized void flush() {
            discardedAfterPcm |= acceptedSamples != 0;
            super.flush();
        }

        @Override
        public synchronized void release() {
            discardedAfterPcm |= acceptedSamples != 0;
            super.release();
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
