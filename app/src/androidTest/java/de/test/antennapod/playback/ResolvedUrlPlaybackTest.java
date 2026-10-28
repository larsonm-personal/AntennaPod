package de.test.antennapod.playback;

import android.content.Context;
import android.media.AudioFormat;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
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
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput;
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
import org.apache.commons.io.FileUtils;
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
    private static final double TAGGED_TOLERANCE_SECONDS = 1;
    private ResolvedEpisodeServer server = new ResolvedEpisodeServer();
    private double observationBoundary = 90;
    private long observationEndMs = MIN_OBSERVATION_END_MS;
    private final Set<Long> activeLoads = new HashSet<>();
    private Throwable loadError;
    private boolean mediaLoadObserved;
    private Context context;
    private ExoPlayer player;
    private volatile PcmOutput sink;
    private ResolvedTaggedAudio taggedAudio;
    private final List<Integer> sampledEditions = new ArrayList<>();
    private boolean taggedRejected;
    private volatile boolean outputRecreated;
    private volatile PcmOutput outputBeforeSeek;
    private long seekStartMs;
    private File initial;
    private File changed;
    private volatile long playerPosition;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UserPreferences.init(context);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(ExoPlayerUtils::releaseCache);
        deleteCache();
        initial = copyAsset("resolved-a.mp3");
        changed = copyAsset("resolved-b.mp3");
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
        cancelLoadingAndWait();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> player.stop());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            player.release();
            player = null;
            ExoPlayerUtils.releaseCache();
        });
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
        playResolved(media, RESUME_MS);
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

    @Test
    public void equalLengthFixturePlaysCoherentProgram() throws Exception {
        File equalLength = copyAsset("resolved-equal-length.mp3");
        try {
            assertEquals(initial.length(), equalLength.length());
            server.replaceAWith(equalLength);
            playResolved(MediaItem.fromUri(server.getBaseUrl() + "/resolved-ad/original"), RESUME_MS);
            awaitResolvedContent();
            assertConsumedContent("stable equal-length fixture", CHANGED_AD_SECONDS);
        } finally {
            equalLength.delete();
        }
    }

    @Test
    public void sameResolvedUrlLengthChangeDoesNotMixContent() throws Exception {
        MediaItem media = primeCachedEpisode();
        server.replaceAWithB();
        playResolved(media, RESUME_MS);
        awaitResolvedContent();
        Log.i(TAG, "synthetic same-URL mutation requests=" + server.getRequests());
        assertConsumedContent("synthetic same-URL length change", 0);
    }

    @Test
    public void interruptedStreamReconnectsWithinSamePlayer() throws Exception {
        server.throttleResponsesAfter(THROTTLE_AFTER_BYTES);
        MediaItem media = MediaItem.fromUri(server.getBaseUrl() + "/resolved-ad/original");
        playResolved(media, INITIAL_SEEK_MS);
        awaitPrimedAudio();
        final ExoPlayer originalPlayer = player;
        server.disconnectActiveResponses();
        server.resumeResponses();
        awaitResolvedContent();
        assertTrue("No response was interrupted", server.getInterruptedResponses() > 0);
        assertTrue("Player was replaced", player == originalPlayer);
        Log.i(TAG, "synthetic disconnect requests=" + server.getRequests());
        assertConsumedContent("synthetic disconnect, stable A", INITIAL_AD_SECONDS);
    }

    public void changedVariantRemainsAccessibleAcrossBackwardAndForwardSeeks() throws Exception {
        MediaItem media = primeCachedEpisode();
        server.switchFreshResolutionsToB();
        playResolved(media, RESUME_MS);
        awaitResolvedContent();
        double selectedAdSeconds = observedAdSeconds();
        double worst = 0;
        for (long position : new long[] {75000, 105000, 85000}) {
            worst = Math.max(worst, seekAndMeasureContent(position, selectedAdSeconds));
        }
        Log.i(TAG, "variant-switch seeks requests=" + server.getRequests());
        assertTrue("Selected content inaccessible after seeking: maxError=" + worst, worst < TOLERANCE_SECONDS);
    }

    @Test
    public void seekObservationMatchesBothStableEditions() throws Exception {
        for (String edition : new String[] {"A", "B"}) {
            observationEndMs = MIN_OBSERVATION_END_MS;
            playResolved(MediaItem.fromUri(server.getBaseUrl() + "/resolved-ad/" + edition), RESUME_MS);
            awaitResolvedContent();
            double adSeconds = edition.equals("A") ? INITIAL_AD_SECONDS : CHANGED_AD_SECONDS;
            assertEquals(adSeconds, observedAdSeconds(), 0);
            double worst = 0;
            for (long position : new long[] {75000, 105000, 85000}) {
                worst = Math.max(worst, seekAndMeasureContent(position, adSeconds));
            }
            assertTrue("Stable " + edition + " seek error=" + worst, worst < TOLERANCE_SECONDS);
            cancelLoadingAndWait();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                player.release();
                player = null;
                ExoPlayerUtils.releaseCache();
            });
        }
    }

    @Test
    public void identicalAudioAcrossDifferentUrlsKeepsPlaying() throws Exception {
        server.stop();
        server = new ResolvedEpisodeServer();
        server.start(initial, initial);
        MediaItem media = primeCachedEpisode();
        server.switchFreshResolutionsToB();
        playResolved(media, RESUME_MS);
        awaitResolvedContent();
        assertConsumedContent("identical bytes, different URLs", INITIAL_AD_SECONDS);
    }

    @Test
    public void sameLengthMutationKeepsSampledContentCoherent() throws Exception {
        useTaggedFixtures(true);
        MediaItem media = primeCachedEpisode();
        server.replaceAWithB();
        taggedAudio = new ResolvedTaggedAudio();
        playResolved(media, RESUME_MS);
        checkRestartSamples(collectTaggedAudio(-1, taggedObservationWindows(), true));
    }

    @Test
    public void sameLengthMutationKeepsSampledSeekTargetsRecoverable() throws Exception {
        useTaggedFixtures(true);
        assertEquals("Encoded lengths differ", initial.length(), changed.length());
        assertFalse("Fixtures are identical", FileUtils.contentEquals(initial, changed));
        MediaItem media = primeCachedEpisode();
        server.replaceAWithB();
        try {
            taggedAudio = new ResolvedTaggedAudio();
            playResolved(media, RESUME_MS);
            checkRestartSamples(collectTaggedAudio(-1, taggedObservationWindows(), false));
            checkFixedSeekTargets();
        } finally {
            Log.i(TAG, "same-length seeking requests=" + server.getRequests());
        }
    }

    @Test
    public void headGetDisagreementKeepsSampledContentCoherent() throws Exception {
        useTaggedFixtures(false);
        MediaItem media = primeCachedEpisode();
        server.redirectGetsFromAToB();
        taggedAudio = new ResolvedTaggedAudio();
        playResolved(media, RESUME_MS);
        checkRestartSamples(collectTaggedAudio(-1, taggedObservationWindows(), true));
    }

    @Test
    public void changedEditionKeepsSampledSeekTargetsRecoverable() throws Exception {
        useTaggedFixtures(false);
        MediaItem media = primeCachedEpisode();
        server.switchFreshResolutionsToB();
        taggedAudio = new ResolvedTaggedAudio();
        playResolved(media, RESUME_MS);
        checkRestartSamples(collectTaggedAudio(-1, taggedObservationWindows(), false));
        checkFixedSeekTargets();
    }

    private void checkFixedSeekTargets() {
        double[] targets = {80, 110, 75};
        for (double target : targets) {
            int before = taggedAudio.epoch();
            seekToMedia(target);
            checkSample(collectTaggedAudio(before, 12, false), 4, target);
        }
        int finalEdition = sampledEditions.get(sampledEditions.size() - 1);
        for (double target : targets) {
            int before = taggedAudio.epoch();
            seekToMedia(target);
            assertEquals(finalEdition, checkSample(collectTaggedAudio(before, 12, false), 4, target));
        }
    }

    @Test
    public void evictedStableEditionRemainsSeekable() throws Exception {
        useTaggedFixtures(false);
        MediaItem media = primeCachedEpisode();
        evictPrimedAudioThroughPlayback();
        taggedAudio = new ResolvedTaggedAudio();
        playResolved(media, RESUME_MS);
        checkRestartSamples(collectTaggedAudio(-1, taggedObservationWindows(), false));
        checkFixedSeekTargets();
    }

    @Test
    public void evictedEpisodeResolvesToSeekableEdition() throws Exception {
        useTaggedFixtures(false);
        MediaItem media = primeCachedEpisode();
        evictPrimedAudioThroughPlayback();
        server.switchFreshResolutionsToB();
        taggedAudio = new ResolvedTaggedAudio();
        playResolved(media, RESUME_MS);
        checkRestartSamples(collectTaggedAudio(-1, taggedObservationWindows(), false));
        checkFixedSeekTargets();
    }

    @Test
    public void expiredResolvedUrlRestartsWithSeekableContent() throws Exception {
        useTaggedFixtures(false);
        MediaItem media = primeCachedEpisode();
        int beforeExpiry = server.getRequests().size();
        server.expireA();
        server.switchFreshResolutionsToB();
        try {
            taggedAudio = new ResolvedTaggedAudio();
            playResolved(media, RESUME_MS);
            checkRestartSamples(collectTaggedAudio(-1, taggedObservationWindows(), false));
            checkFixedSeekTargets();
        } finally {
            List<String> requests = server.getRequests();
            Log.i(TAG, "afterExpiry requests=" + requests.subList(beforeExpiry, requests.size()));
        }
    }

    private void evictPrimedAudioThroughPlayback() throws Exception {
        File directory = new File(context.getCacheDir(), "streaming");
        List<File> primed = cacheFiles(directory);
        assertFalse("No primed cache files", primed.isEmpty());
        for (int index = 0; index < 26; index++) {
            final int episode = index;
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                player = ExoPlayerUtils.buildPlayer(context);
                observeMediaLoads();
                player.setMediaItem(MediaItem.fromUri(server.getBaseUrl() + "/resolved-ad/pressure/" + episode));
                player.prepare();
            });
            AtomicBoolean buffered = new AtomicBoolean();
            Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    assertNull("Pressure playback error", player.getPlayerError());
                    buffered.set(player.getPlaybackState() == Player.STATE_READY
                            && !player.isLoading() && player.getBufferedPercentage() == 100);
                });
                return buffered.get();
            });
            cancelLoadingAndWait();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                player.release();
                player = null;
                ExoPlayerUtils.releaseCache();
            });
            Log.i(TAG, "cache pressure completed episode=" + index);
        }
        long bytes = 0;
        for (File file : cacheFiles(directory)) {
            bytes += file.length();
        }
        assertTrue("Pressure did not fill the real cache: " + bytes, bytes > 95L * 1024 * 1024);
        assertTrue("Cache exceeded 100 MB: " + bytes, bytes <= 100L * 1024 * 1024);
        for (File file : primed) {
            assertFalse("Primed span survived pressure: " + file.getName(), file.exists());
        }
        Log.i(TAG, "real cache eviction bytes=" + bytes + " removedPrimedFiles=" + primed.size());
    }

    private List<File> cacheFiles(File directory) {
        List<File> result = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    result.addAll(cacheFiles(file));
                } else if (file.getName().endsWith(".v3.exo")) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    @Test
    public void taggedFixtureIdentifiesBothEditionsThroughOrdinarySeeks() throws Exception {
        useTaggedFixtures(false);
        for (String edition : new String[] {"A", "B"}) {
            taggedAudio = new ResolvedTaggedAudio();
            playResolved(MediaItem.fromUri(server.getBaseUrl() + "/resolved-ad/" + edition), RESUME_MS);
            collectTaggedAudio(-1, 12, false);
            int source = edition.equals("A") ? 0 : 1;
            for (double target : new double[] {80, 110, 75}) {
                int before = taggedAudio.epoch();
                seekToMedia(target);
                List<ResolvedTaggedAudio.Heard> heard = collectTaggedAudio(before, 12, false);
                assertEquals(source, checkSample(heard, 4, target));
            }
            cancelLoadingAndWait();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                player.release();
                player = null;
                ExoPlayerUtils.releaseCache();
            });
        }
    }

    private int taggedObservationWindows() {
        return (int) ((observationEndMs - RESUME_MS) / 250) + 4;
    }

    private void useTaggedFixtures(boolean equalLength) throws Exception {
        server.stop();
        initial.delete();
        changed.delete();
        initial = copyAsset("tagged-a.mp3");
        changed = copyAsset(equalLength ? "tagged-equal-length.mp3" : "tagged-b.mp3");
        server = new ResolvedEpisodeServer();
        server.start(initial, changed);
    }

    private List<ResolvedTaggedAudio.Heard> collectTaggedAudio(int before, int count, boolean allowRejection) {
        AtomicBoolean rejected = new AtomicBoolean();
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                rejected.set(player.getPlayerError() != null);
                if (rejected.get()) {
                    Log.i(TAG, "tagged playback error=" + player.getPlayerError());
                }
            });
            return rejected.get() || taggedAudio.after(before).size() >= count;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> player.pause());
        List<ResolvedTaggedAudio.Heard> heard = taggedAudio.after(before);
        for (ResolvedTaggedAudio.Heard window : heard) {
            Log.i(TAG, "tagged epoch=" + window.epoch + " source=" + window.edition
                    + " program=" + window.program + " output=" + window.outputSeconds);
        }
        taggedRejected = rejected.get();
        Log.i(TAG, "tagged outcome=" + (rejected.get() ? "explicit rejection; recovery not established"
                : "played audio; predetermined segments checked separately") + " windows=" + heard.size());
        assertTrue("Stable playback rejected", allowRejection || !rejected.get());
        return heard;
    }

    private void seekToMedia(double target) {
        long position = Math.round(target * 1000);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            player.seekTo(position);
            player.play();
        });
    }

    private void checkRestartSamples(List<ResolvedTaggedAudio.Heard> heard) {
        int afterBoundary = 4 * ((int) Math.ceil(observationBoundary - RESUME_MS / 1000.0) + 1);
        for (int first : new int[] {4, afterBoundary}) {
            if (heard.size() >= first + 4) {
                checkSample(heard, first, Double.NaN);
            } else {
                assertTrue("Missing predetermined restart segment", taggedRejected);
                Log.i(TAG, "Rejected before complete sampled segment " + first + "; recovery not established");
            }
        }
    }

    private int checkSample(List<ResolvedTaggedAudio.Heard> heard, int first, double mediaStart) {
        List<ResolvedTaggedAudio.Heard> segment = ResolvedTaggedAudio.segment(heard, first, TAGGED_TOLERANCE_SECONDS);
        int source = segment.get(0).edition;
        for (ResolvedTaggedAudio.Heard window : segment) {
            if (!Double.isNaN(mediaStart)) {
                double expected = mediaStart + window.outputSeconds
                        - (source == 0 ? INITIAL_AD_SECONDS : CHANGED_AD_SECONDS);
                assertTrue("Incorrect sampled seek content", Math.abs(window.program - expected) < TAGGED_TOLERANCE_SECONDS);
            }
        }
        sampledEditions.add(source);
        ResolvedTaggedAudio.assertOneChange(sampledEditions);
        Log.i(TAG, "sampled segment first=" + first + " source=" + source + " mediaStart=" + mediaStart);
        return source;
    }

    private void awaitPrimedAudio() {
        long started = System.nanoTime();
        long[] firstConsumedWindow = {0};
        long ready = 0;
        long[] nextReportMs = {0};
        try {
            Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> {
                PcmOutput output = sink;
                boolean consumed = output != null && !output.consumedWindows().isEmpty();
                if (consumed && firstConsumedWindow[0] == 0) {
                    firstConsumedWindow[0] = System.nanoTime();
                }
                return server.hasSentPrefix() && consumed;
            });
            ready = System.nanoTime();
            Log.i(TAG, "priming firstConsumedWindowMs=" + (firstConsumedWindow[0] - started) / 1000000
                    + " readinessMs=" + (ready - started) / 1000000);
            Awaitility.await().atMost(30, TimeUnit.SECONDS).conditionEvaluationListener(condition -> {
                if (condition.getElapsedTimeInMS() >= nextReportMs[0]) {
                    nextReportMs[0] += 5000;
                    logPrimingState();
                }
            }).until(() -> sink != null && sink.consumedUs >= RESUME_MS * 1000);
        } finally {
            Log.i(TAG, "priming observationMs=" + (ready == 0 ? -1 : (System.nanoTime() - ready) / 1000000)
                    + " totalMs=" + (System.nanoTime() - started) / 1000000);
            logPrimingState();
        }
    }

    private void logPrimingState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            PcmOutput output = sink;
            long accepted = 0;
            if (output != null) {
                synchronized (output) {
                    accepted = output.acceptedSamples;
                }
            }
            Log.i(TAG, "priming prefix=" + server.hasSentPrefix() + " state=" + player.getPlaybackState()
                    + " position=" + player.getCurrentPosition() + " buffered=" + player.getBufferedPosition()
                    + " playing=" + player.isPlaying() + " loading=" + player.isLoading()
                    + " suppression=" + player.getPlaybackSuppressionReason() + " active=" + activeLoads
                    + " acceptedSamples=" + accepted + " consumedUs="
                    + (output == null ? -1 : output.consumedUs) + " clockReadAgeMs="
                    + (output == null || output.positionReadNanos == 0 ? -1
                    : (System.nanoTime() - output.positionReadNanos) / 1000000)
                    + " error=" + player.getPlayerError());
        });
    }

    private MediaItem primeCachedEpisode() throws Exception {
        server.throttleResponsesAfter(THROTTLE_AFTER_BYTES);
        MediaItem media = MediaItem.fromUri(server.getBaseUrl() + "/resolved-ad/original");
        playResolved(media, INITIAL_SEEK_MS);
        awaitPrimedAudio();
        cancelLoadingAndWait();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            player.stop();
            player.release();
            player = null;
            ExoPlayerUtils.releaseCache();
        });
        server.resumeResponses();
        long end = cachedEndAtResume();
        double boundary = frameTimeAt(end);
        if (boundary > RESUME_MS / 1000.0 + 2 && end < initial.length()) {
            observationBoundary = boundary;
            observationEndMs = Math.max(MIN_OBSERVATION_END_MS, (long) ((boundary + 3) * 1000));
        }
        Log.i(TAG, "primed cachedEndBytes=" + end + " cacheEndSeconds=" + boundary);
        return media;
    }

    private double observedAdSeconds() {
        List<double[]> windows = sink.consumedWindows();
        assertTrue("Insufficient PCM to identify selected edition", windows.size() >= 8);
        double errorA = 0;
        double errorB = 0;
        for (double[] window : windows.subList(windows.size() - 8, windows.size())) {
            double observed = (window[1] - 20) / ((10000.0 - 20) / 120);
            errorA = Math.max(errorA, Math.abs(observed - (window[0] - INITIAL_AD_SECONDS)));
            errorB = Math.max(errorB, Math.abs(observed - (window[0] - CHANGED_AD_SECONDS)));
        }
        assertTrue("Latest consumed audio identifies neither edition", Math.min(errorA, errorB) < TOLERANCE_SECONDS);
        return errorA < errorB ? INITIAL_AD_SECONDS : CHANGED_AD_SECONDS;
    }

    private double seekAndMeasureContent(long position, double selectedAdSeconds) throws Exception {
        assertFalse("Unexpected output recreation before seek", outputRecreated);
        assertFalse("PCM discarded before seek", sink.discardedAfterPcm);
        final PcmOutput previousOutput = sink;
        seekStartMs = position;
        outputBeforeSeek = previousOutput;
        observationEndMs = position + 3500;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            player.seekTo(position);
            player.play();
        });
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> sink != previousOutput);
        awaitResolvedContent();
        assertFalse("Audio output recreated after seek", outputRecreated);
        assertFalse("PCM discarded after seek", sink.discardedAfterPcm);
        List<double[]> windows = sink.consumedWindows();
        assertTrue("Insufficient consumed PCM after seek", windows.size() >= 8);
        double worst = 0;
        for (double[] window : windows) {
            double observed = (window[1] - 20) / ((10000.0 - 20) / 120);
            double error = observed - (window[0] - selectedAdSeconds);
            Log.i(TAG, "seekTarget=" + position + " media=" + window[0] + " program=" + observed
                    + " selectedAdSeconds=" + selectedAdSeconds + " error=" + error);
            worst = Math.max(worst, Math.abs(error));
        }
        return worst;
    }

    private void playResolved(MediaItem media, long position) throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            player = ExoPlayerUtils.buildPlayer(context);
            observeMediaLoads();
        });
        observeAudioAt(position);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            player.setSkipSilenceEnabled(false);
            player.setPlaybackSpeed(1);
            player.setMediaItem(media, position);
            player.prepare();
            player.play();
        });
    }

    private void observeAudioAt(long position) throws Exception {
        List<PlayerMessage> messages = new ArrayList<>();
        sink = null;
        outputRecreated = false;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AudioOutputProvider provider = new ForwardingAudioOutputProvider(
                    new AudioTrackAudioOutputProvider.Builder(context).build()) {
                @Override
                public AudioOutput getAudioOutput(OutputConfig config) throws InitializationException {
                    assertEquals(C.ENCODING_PCM_16BIT, config.encoding);
                    assertEquals(AudioFormat.CHANNEL_OUT_MONO, config.channelMask);
                    assertFalse(config.isOffload);
                    if (taggedAudio != null) {
                        AudioTrackAudioOutput output = (AudioTrackAudioOutput) super.getAudioOutput(config);
                        return taggedAudio.wrap(output,
                                () -> output.getAudioTrack().getPlaybackHeadPosition() & 0xffffffffL);
                    }
                    long startMs = position;
                    if (outputBeforeSeek != null && outputBeforeSeek == sink) {
                        assertTrue("Old output not released on seek", sink.discardedAfterPcm);
                        startMs = seekStartMs;
                        outputBeforeSeek = null;
                    } else {
                        outputRecreated |= sink != null && sink.acceptedSamples != 0;
                    }
                    sink = new PcmOutput(super.getAudioOutput(config), startMs);
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
    }

    private void observeMediaLoads() {
        activeLoads.clear();
        loadError = null;
        mediaLoadObserved = false;
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
    }

    private void cancelLoadingAndWait() {
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
        Log.i(TAG, "mediaLoadsFinishedBeforeRelease=true");
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
            long resumeByte = frameOffsetAt(RESUME_MS);
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
                    Log.i(TAG, "cachedSpanStart=" + span.position + " length=" + span.length);
                }
                long length = cache.getCachedLength(key, resumeByte, initial.length() - resumeByte);
                if (length > 0) {
                    end = Math.max(end, resumeByte + length);
                }
            }
            return end;
        } finally {
            cache.release();
            database.close();
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

    static class PcmOutput extends ForwardingAudioOutput {
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
        private volatile long positionReadNanos;
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
            positionReadNanos = System.nanoTime();
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
            return consumedWindows(consumedUs);
        }

        synchronized List<double[]> consumedWindows(long playedUs) {
            List<double[]> consumed = new ArrayList<>();
            for (double[] window : windows) {
                if (window[0] + 0.25 < playedUs / 1000000.0) {
                    consumed.add(window);
                }
            }
            return consumed;
        }
    }
}
