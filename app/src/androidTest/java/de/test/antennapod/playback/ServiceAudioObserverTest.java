package de.test.antennapod.playback;

import android.media.AudioFormat;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.PlayerMessage;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioOutput;
import androidx.media3.exoplayer.audio.AudioOutputProvider;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider;
import androidx.test.filters.LargeTest;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.danoeh.antennapod.playback.service.Media3PlaybackService;
import de.danoeh.antennapod.storage.database.PodDBAdapter;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@LargeTest
@OptIn(markerClass = UnstableApi.class)
public class ServiceAudioObserverTest {
    private final ServicePlaybackFixture app = new ServicePlaybackFixture();
    private final ResolvedTaggedAudio audio = new ResolvedTaggedAudio();
    private final List<PlayerMessage> messages = new ArrayList<>();
    private boolean initialized;
    private ExoPlayer observedPlayer;
    private volatile long requestedSeek = -1;
    private volatile int seekStart = -1;

    @After
    public void tearDown() throws Exception {
        try {
            if (initialized) {
                Log.i("ServiceAudioObserver", "requests=" + app.server.getRequests());
                app.tearDown();
            }
        } finally {
            Media3PlaybackService.setPlayerObserverForTests(null);
        }
    }

    @Test
    public void pausedPreparationDoesNotCountQueuedAudio() throws Exception {
        start(false);
        awaitReady();
        SystemClock.sleep(2000);
        assertTrue("Paused preparation played audio", audio.heard().isEmpty());
        sample(() -> app.controller.play(), 0, 80);
    }

    @Test
    public void ordinarySeeksExposeKnownProgramPassages() throws Exception {
        start(false);
        sample(() -> app.controller.play(), 0, 80);
        for (int target : new int[] {110, 75, 80}) {
            seekAndSample(target);
        }
    }

    @Test
    public void completeEpisodeReplacementExposesBothEditions() throws Exception {
        start(false);
        sample(() -> app.controller.play(), 0, 80);
        FeedMedia second = addEpisode("B");
        select(second, 80);
        sample(() -> app.controller.play(), 1, 80);
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

    @Test
    public void passageRecognitionAllowsReplacementButRejectsMissingContent() {
        List<ResolvedTaggedAudio.Heard> windows = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            double band = index % 2 == 0 ? 500 : 3500;
            windows.add(new ResolvedTaggedAudio.Heard(new double[] {0.125,
                    band + (75 + index * 0.25) * 12.5}, index));
        }
        assertTrue(matchesPassage(windows, 75));
        windows.set(2, new ResolvedTaggedAudio.Heard(new double[] {0.125, 500 + 15 * 12.5}, 2));
        assertFalse(matchesPassage(windows, 75));
        assertFalse(matchesPassage(Collections.emptyList(), 75));
    }

    private void start(boolean identical) throws Exception {
        start(identical, 80, false);
    }

    private void start(boolean identical, int position, boolean partial) throws Exception {
        initialized = true;
        app.setUp("tagged-a.mp3", identical ? "tagged-a.mp3" : "tagged-b.mp3");
        if (!partial) {
            app.server.resumeResponses();
        }
        Media3PlaybackService.setPlayerObserverForTests(this::observe);
        app.connect();
        assertEquals(1, messages.size());
        messages.get(0).blockUntilDelivered(5000);
        select(app.media, position);
    }

    private void select(FeedMedia media, int position) {
        app.onMain(() -> {
            app.controller.pause();
            app.controller.setMediaItem(MediaItemAdapter.fromMediaIdStub(media.getId()));
            app.controller.prepare();
        });
        awaitReady();
        app.onMain(() -> app.controller.seekTo(position * 1000L));
    }

    private FeedMedia addEpisode(String route) {
        Feed feed = new Feed(app.server.getBaseUrl() + "/second-feed", null, "Synthetic second feed", null, null);
        FeedItem item = new FeedItem(0, "Synthetic second episode", "second", null, new Date(),
                FeedItem.UNPLAYED, feed);
        FeedMedia media = new FeedMedia(item, app.server.getBaseUrl() + "/resolved-ad/" + route, 0, "audio/mpeg");
        item.setMedia(media);
        feed.setItems(Collections.singletonList(item));
        PodDBAdapter adapter = PodDBAdapter.getInstance().open();
        try {
            adapter.setCompleteFeed(feed);
        } finally {
            adapter.close();
        }
        assertTrue(media.getId() > 0);
        return media;
    }

    private void awaitReady() {
        AtomicBoolean ready = new AtomicBoolean();
        try {
            Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
                app.onMain(() -> {
                    assertNull(app.controller.getPlayerError());
                    assertFalse(app.controller.getPlayWhenReady());
                    ready.set(app.controller.getPlaybackState() == Player.STATE_READY);
                });
                return ready.get();
            });
        } catch (ConditionTimeoutException timeout) {
            logState("readiness timeout");
            throw timeout;
        }
    }

    private void seekAndSample(int target) {
        seekStart = -1;
        requestedSeek = target * 1000L;
        app.onMain(() -> app.controller.seekTo(target * 1000L));
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> seekStart >= 0);
        measure(seekStart, 0, target);
    }

    private void sample(Runnable action, int edition, int target) {
        int first = audio.heard().size();
        app.onMain(action);
        measure(first, edition, target);
    }

    private boolean reaches(int program) {
        for (double ad : new double[] {2, 2 + 2299 * 1152.0 / 44100}) {
            long target = Math.round((program + ad) * 1000);
            AtomicLong duration = new AtomicLong();
            app.onMain(() -> duration.set(app.controller.getDuration()));
            logState("passage=" + program + " candidate=" + target + " readDuration=" + duration.get());
            if (duration.get() <= 0) {
                Log.i("ServiceAudioObserver", "programTarget=" + program + " unavailable: no episode duration");
                return false;
            }
            Log.i("ServiceAudioObserver", "programTarget=" + program + " seek=" + target
                    + " duration=" + duration.get());
            if (target + 6000 >= duration.get()) {
                Log.i("ServiceAudioObserver", "candidate outside reported duration");
                continue;
            }
            seekStart = -1;
            requestedSeek = target;
            app.onMain(() -> app.controller.seekTo(target));
            Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> seekStart >= 0);
            List<ResolvedTaggedAudio.Heard> segment = sampledContent(seekStart, true);
            boolean matches = matchesPassage(segment, program);
            Log.i("ServiceAudioObserver", "programTarget=" + program + " candidateMatched=" + matches);
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPassage(List<ResolvedTaggedAudio.Heard> segment, int program) {
        if (segment.size() != 4) {
            return false;
        }
        for (int index = 0; index < segment.size(); index++) {
            ResolvedTaggedAudio.Heard window = segment.get(index);
            if (window.edition < 0 || window.program < program || window.program > program + 6
                    || Math.abs(window.program - segment.get(0).program - index * 0.25) >= 0.25) {
                return false;
            }
        }
        return true;
    }

    private List<ResolvedTaggedAudio.Heard> sampledContent(int first, boolean allowUnavailable) {
        try {
            Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> audio.heard().size() >= first + 12);
        } catch (ConditionTimeoutException unavailable) {
            app.onMain(() -> Log.i("ServiceAudioObserver", "unavailable state="
                    + app.controller.getPlaybackState() + " error=" + app.controller.getPlayerError()));
            if (!allowUnavailable) {
                throw unavailable;
            }
        }
        List<ResolvedTaggedAudio.Heard> history = audio.heard();
        for (int index = first; index < history.size(); index++) {
            ResolvedTaggedAudio.Heard window = history.get(index);
            Log.i("ServiceAudioObserver", "sample=" + index + " epoch=" + window.epoch + " edition="
                    + window.edition + " program=" + window.program + " output=" + window.outputSeconds);
        }
        if (history.size() < first + 12) {
            return Collections.emptyList();
        }
        return history.subList(first + 8, first + 12);
    }

    private void measure(int first, int edition, int target) {
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
            app.onMain(() -> assertNull(app.controller.getPlayerError()));
            return audio.heard().size() >= first + 12;
        });
        List<ResolvedTaggedAudio.Heard> history = audio.heard();
        for (int index = first; index < history.size(); index++) {
            ResolvedTaggedAudio.Heard window = history.get(index);
            Log.i("ServiceAudioObserver", "sample=" + index + " epoch=" + window.epoch + " edition="
                    + window.edition + " program=" + window.program + " output=" + window.outputSeconds);
        }
        double program = target - 2 - (edition == 0 ? 2299 * 1152.0 / 44100 : 0);
        for (ResolvedTaggedAudio.Heard window : ResolvedTaggedAudio.segment(history, first + 8, 0.25)) {
            assertEquals("Known episode edition", edition, window.edition);
            assertTrue("Expected program passage " + program + " but heard " + window.program,
                    window.program >= program && window.program <= program + 6);
        }
    }

    private void logState(String stage) {
        app.onMain(() -> {
            for (Player player : new Player[] {app.controller, observedPlayer}) {
                Log.i("ServiceAudioObserver", stage + " observer="
                        + (player == app.controller ? "controller" : "player")
                        + " item=" + (player.getCurrentMediaItem() == null ? "none"
                        : player.getCurrentMediaItem().mediaId) + " state=" + player.getPlaybackState()
                        + " position=" + player.getCurrentPosition() + " buffered=" + player.getBufferedPosition()
                        + " duration=" + player.getDuration() + " loading=" + player.isLoading()
                        + " playWhenReady=" + player.getPlayWhenReady() + " error=" + player.getPlayerError());
            }
        });
    }

    private void observe(ExoPlayer player) {
        observedPlayer = player;
        player.addListener((Player.Listener) Proxy.newProxyInstance(Player.Listener.class.getClassLoader(),
                new Class<?>[] {Player.Listener.class}, (listener, method, arguments) -> {
                    switch (method.getName()) {
                        case "onPositionDiscontinuity":
                            if (arguments.length == 3 && (int) arguments[2] == Player.DISCONTINUITY_REASON_SEEK
                                    && requestedSeek >= 0) {
                                requestedSeek = -1;
                                player.createMessage((type, payload) -> seekStart = audio.heard().size()).send();
                            }
                            return null;
                        case "hashCode":
                            return System.identityHashCode(listener);
                        case "equals":
                            return listener == arguments[0];
                        case "toString":
                            return "ServiceSeekObserver";
                        default:
                            return null;
                    }
                }));
        AudioOutputProvider provider = new ForwardingAudioOutputProvider(
                new AudioTrackAudioOutputProvider.Builder(app.context).build()) {
            @Override
            public AudioOutput getAudioOutput(OutputConfig config) throws InitializationException {
                assertEquals(C.ENCODING_PCM_16BIT, config.encoding);
                assertEquals(AudioFormat.CHANNEL_OUT_MONO, config.channelMask);
                assertFalse(config.isOffload);
                AudioTrackAudioOutput output = (AudioTrackAudioOutput) super.getAudioOutput(config);
                return audio.wrap(output, () -> output.getAudioTrack().getPlaybackHeadPosition() & 0xffffffffL);
            }
        };
        for (int index = 0; index < player.getRendererCount(); index++) {
            Renderer renderer = player.getRenderer(index);
            if (renderer.getTrackType() == C.TRACK_TYPE_AUDIO) {
                messages.add(player.createMessage(renderer).setType(Renderer.MSG_SET_AUDIO_OUTPUT_PROVIDER)
                        .setPayload(provider).send());
            }
        }
    }
}
