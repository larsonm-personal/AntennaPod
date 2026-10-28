package de.test.antennapod.playback;

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

    static List<Heard> segment(List<Heard> history, int first, double tolerance) {
        assertTrue("Incomplete predetermined segment", history.size() >= first + 4);
        List<Heard> segment = history.subList(first, first + 4);
        Heard anchor = segment.get(0);
        assertTrue("Unidentified sampled edition", anchor.edition >= 0);
        for (int index = 0; index < segment.size(); index++) {
            Heard window = segment.get(index);
            assertTrue("Sampled segment changes edition or output",
                    window.edition == anchor.edition && window.epoch == anchor.epoch);
            assertTrue("Sampled segment has missing audio windows",
                    Math.abs(window.outputSeconds - anchor.outputSeconds - index * 0.25) < 0.025);
            double jump = window.program - anchor.program - window.outputSeconds + anchor.outputSeconds;
            assertTrue("Sampled program jump=" + jump, Math.abs(jump) < tolerance);
        }
        return segment;
    }

    static void assertOneChange(List<Integer> editions) {
        int changes = 0;
        for (int index = 1; index < editions.size(); index++) {
            if (!editions.get(index).equals(editions.get(index - 1))) {
                changes++;
            }
        }
        assertTrue("Repeated edition changes between sampled segments", changes <= 1);
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
