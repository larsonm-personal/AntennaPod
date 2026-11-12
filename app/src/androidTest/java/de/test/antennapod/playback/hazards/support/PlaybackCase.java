package de.test.antennapod.playback.hazards.support;

import android.media.AudioFormat;
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

import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.danoeh.antennapod.playback.service.Media3PlaybackService;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.After;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// Drives the real playback service and checks fixed program passages in consumed audio.
@LargeTest
@OptIn(markerClass = UnstableApi.class)
public abstract class PlaybackCase {
    protected final ServicePlaybackFixture app = new ServicePlaybackFixture();
    protected final ResolvedTaggedAudio audio = new ResolvedTaggedAudio();
    private final List<PlayerMessage> messages = new ArrayList<>();
    private boolean initialized;
    private ExoPlayer observedPlayer;
    private volatile long requestedSeek = -1;
    private volatile int seekStart = -1;

    @After
    public void tearDown() throws Exception {
        try {
            if (initialized) {
                app.tearDown();
            }
        } finally {
            Media3PlaybackService.setPlayerObserverForTests(null);
        }
    }

    protected void primePartialOriginal() throws Exception {
        start("original", 70);
        app.onMain(() -> app.controller.play());
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
            List<ResolvedTaggedAudio.Heard> heard = audio.heard();
            return app.server.hasThrottledResponse() && !heard.isEmpty()
                    && heard.get(heard.size() - 1).edition == 0
                    && heard.get(heard.size() - 1).program >= 25;
        });
        app.stopService();
        app.assertPartialCache();
    }

    protected void playReplacement() throws Exception {
        start("B", 27);
        app.onMain(() -> app.controller.play());
    }

    protected void restartForReplay() throws Exception {
        app.server.resumeResponses();
        app.connect();
        app.onMain(() -> app.controller.play());
    }

    private void start(String edition, int position) throws Exception {
        initialized = true;
        app.setUp(edition);
        if (edition.equals("original")) {
            app.server.throttleResponsesAfter(512000);
        }
        Media3PlaybackService.setPlayerObserverForTests(this::observe);
        app.connect();
        assertEquals(1, messages.size());
        messages.get(0).blockUntilDelivered(5000);
        app.onMain(() -> {
            app.controller.pause();
            app.controller.setMediaItem(MediaItemAdapter.fromMediaIdStub(app.media.getId()));
            app.controller.prepare();
        });
        AtomicBoolean ready = new AtomicBoolean();
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
            app.onMain(() -> {
                assertNull(app.controller.getPlayerError());
                ready.set(app.controller.getPlaybackState() == Player.STATE_READY);
            });
            return ready.get();
        });
        app.onMain(() -> app.controller.seekTo(position * 1000L));
    }

    private String replayEvidence(int first, int program) {
        List<ResolvedTaggedAudio.Heard> heard = audio.heard();
        StringBuilder evidence = new StringBuilder("replay: ");
        for (int index = first; index < heard.size(); index++) {
            ResolvedTaggedAudio.Heard current = heard.get(index);
            ResolvedTaggedAudio.Heard previous = index > first ? heard.get(index - 1) : current;
            if (index == first) {
                evidence.append("start ").append(position(current));
            } else if (current.edition != previous.edition
                    || Math.abs(current.program - previous.program - 0.25) > 1) {
                evidence.append("; jump ").append(position(previous)).append(" -> ").append(position(current));
            }
            if (index == heard.size() - 1) {
                evidence.append("; end ").append(position(current));
            }
        }
        return evidence.append("; passage ").append(program).append(" heard=")
                .append(containsPassage(heard.subList(first, heard.size()), program)).toString();
    }

    private String position(ResolvedTaggedAudio.Heard window) {
        return window.edition < 0 ? "unrecognized audio" : String.format(Locale.ROOT, "%s %.2fs",
                window.edition == 0 ? "A" : "B", window.program);
    }

    protected void rewindAndHearPassage(int program) {
        AtomicBoolean paused = new AtomicBoolean();
        Awaitility.await().atMost(120, TimeUnit.SECONDS).until(() -> {
            app.onMain(() -> {
                if (app.controller.getCurrentPosition() >= 100000) {
                    app.controller.pause();
                    paused.set(true);
                }
            });
            return paused.get();
        });
        logState("before short rewind");
        for (int tap = 0; tap < 3; tap++) {
            seekStart = -1;
            app.onMain(() -> {
                requestedSeek = app.controller.getCurrentPosition() - 10000;
                app.controller.seekTo(requestedSeek);
            });
            Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> seekStart >= 0);
            logState("short rewind " + (tap + 1));
        }
        int first = seekStart;
        app.onMain(() -> app.controller.play());
        try {
            Awaitility.await().atMost(120, TimeUnit.SECONDS).until(() -> {
                app.onMain(() -> assertNull("Playback failed during replay", app.controller.getPlayerError()));
                List<ResolvedTaggedAudio.Heard> history = audio.heard();
                return containsPassage(history.subList(first, history.size()), program);
            });
        } catch (ConditionTimeoutException unavailable) {
            List<ResolvedTaggedAudio.Heard> history = audio.heard();
            assertTrue("Replay did not reach beyond the requested passage",
                    history.subList(first, history.size()).stream().anyMatch(window ->
                            window.edition >= 0 && window.program >= program + 2));
            throw new AssertionError("Program passage " + program + " missing after three 10-second rewinds. "
                    + "Expected two seconds of program audio from either edition; " + replayEvidence(first, program),
                    unavailable);
        } finally {
            app.onMain(() -> app.controller.pause());
            Log.i("ServiceAudioObserver", replayEvidence(first, program));
        }
    }

    static boolean containsPassage(List<ResolvedTaggedAudio.Heard> history, int program) {
        int consecutive = 0;
        double anchor = 0;
        for (ResolvedTaggedAudio.Heard window : history) {
            if (window.edition >= 0 && consecutive > 0
                    && Math.abs(window.program - anchor - consecutive * 0.25) < 0.25) {
                if (++consecutive == 8) {
                    return true;
                }
            } else {
                consecutive = window.edition >= 0 && window.program >= program && window.program < program + 0.25
                        ? 1 : 0;
                anchor = window.program;
            }
        }
        return false;
    }

    void logState(String stage) {
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
        player.addListener(new Player.Listener() {
            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition,
                                                int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && requestedSeek >= 0) {
                    requestedSeek = -1;
                    player.createMessage((type, payload) -> seekStart = audio.heard().size()).send();
                }
            }
        });
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
