package de.test.antennapod.playback;

import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioOutput;
import androidx.media3.exoplayer.audio.ForwardingAudioOutput;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

@OptIn(markerClass = UnstableApi.class)
final class ResolvedTaggedAudio {
    private final List<Heard> finished = new ArrayList<>();
    private Output current;
    private int epoch;

    synchronized AudioOutput wrap(AudioOutput output, PlaybackHead playbackHead) {
        assertTrue("Previous output still active", current == null);
        current = new Output(output, playbackHead);
        return current;
    }

    synchronized List<Heard> heard() {
        List<Heard> result = new ArrayList<>(finished);
        if (current != null) {
            result.addAll(current.heard());
        }
        return result;
    }

    synchronized int epoch() {
        return epoch;
    }

    synchronized List<Heard> after(int previousEpoch) {
        List<Heard> result = new ArrayList<>();
        for (Heard window : heard()) {
            if (window.epoch > previousEpoch) {
                result.add(window);
            }
        }
        return result;
    }

    static void assertIntegrity(List<Heard> history, double tolerance) {
        int edition = -1;
        int boundary = -1;
        for (int index = 0; index < history.size(); index++) {
            int next = history.get(index).edition;
            if (next >= 0) {
                if (edition >= 0 && next != edition) {
                    assertTrue("Repeated edition changes in consumed audio", boundary < 0);
                    boundary = index;
                }
                edition = next;
            }
        }
        if (followsProgram(history, -1, tolerance)) {
            return;
        }
        int uncertain = boundary >= 0 && followsProgram(history, boundary - 1, tolerance) ? boundary - 1 : boundary;
        assertTrue("Consumed audio does not follow a coherent program, even allowing one transition window",
                boundary >= 0 && followsProgram(history, uncertain, tolerance));
        Log.i("ResolvedTaggedAudio", "transitionWindow=" + uncertain
                + " retained; mixed frequencies do not identify its program position");
    }

    private static boolean followsProgram(List<Heard> history, int transitionWindow, double tolerance) {
        Heard anchor = null;
        for (int index = 0; index < history.size(); index++) {
            if (index == transitionWindow) {
                continue;
            }
            Heard window = history.get(index);
            if (window.edition < 0) {
                return false;
            }
            if (anchor == null || window.edition != anchor.edition || window.epoch != anchor.epoch) {
                anchor = window;
            } else {
                double jump = window.program - anchor.program - window.outputSeconds + anchor.outputSeconds;
                if (Math.abs(jump) >= tolerance) {
                    return false;
                }
            }
        }
        return true;
    }

    interface PlaybackHead {
        long frames();
    }

    static final class Heard {
        final int edition;
        final double program;
        final double outputSeconds;
        final int epoch;

        Heard(double[] window, int epoch) {
            double frequency = window[1];
            edition = frequency >= 500 && frequency < 2000 ? 0
                    : frequency >= 3500 && frequency < 5000 ? 1 : -1;
            program = (frequency - (edition == 0 ? 500 : 3500)) / 12.5;
            outputSeconds = window[0];
            this.epoch = epoch;
        }
    }

    private final class Output extends ForwardingAudioOutput {
        private final AudioOutput delegate;
        private ResolvedUrlPlaybackTest.PcmOutput pcm;
        private final PlaybackHead playbackHead;
        private long lastPosition;

        Output(AudioOutput delegate, PlaybackHead playbackHead) {
            super(delegate);
            this.delegate = delegate;
            this.playbackHead = playbackHead;
            pcm = new ResolvedUrlPlaybackTest.PcmOutput(delegate, 0);
        }

        @Override
        public boolean write(ByteBuffer buffer, int units, long timeUs) throws WriteException {
            synchronized (ResolvedTaggedAudio.this) {
                return pcm.write(buffer, units, timeUs);
            }
        }

        List<Heard> heard() {
            long frames = playbackHead.frames();
            assertTrue("Playback head regressed without flush: previous=" + lastPosition + " current=" + frames,
                    frames >= lastPosition);
            lastPosition = frames;
            List<Heard> result = new ArrayList<>();
            for (double[] window : pcm.consumedWindows(frames * 1000000 / delegate.getSampleRate())) {
                result.add(new Heard(window, epoch));
            }
            return result;
        }

        private void finish() {
            finished.addAll(heard());
            epoch++;
        }

        @Override
        public void flush() {
            synchronized (ResolvedTaggedAudio.this) {
                finish();
                pcm.flush();
                pcm = new ResolvedUrlPlaybackTest.PcmOutput(delegate, 0);
                lastPosition = 0;
            }
        }

        @Override
        public void release() {
            synchronized (ResolvedTaggedAudio.this) {
                finish();
                pcm.release();
                current = null;
            }
        }
    }
}
