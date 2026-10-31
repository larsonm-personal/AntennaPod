package de.test.antennapod.playback.hazards.support;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioOutput;
import androidx.media3.exoplayer.audio.ForwardingAudioOutput;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

// Measures accepted PCM frequencies and returns only windows behind the supplied playback head.
@OptIn(markerClass = UnstableApi.class)
final class PcmOutput extends ForwardingAudioOutput {
    private final List<double[]> windows = new ArrayList<>();
    private final int sampleRate;
    private long acceptedSamples;
    private double previous;
    private double firstCrossing;
    private double lastCrossing;
    private int crossings;
    private int samples;

    PcmOutput(AudioOutput delegate) {
        super(delegate);
        sampleRate = delegate.getSampleRate();
    }

    @Override
    public synchronized boolean write(ByteBuffer buffer, int units, long timeUs) throws WriteException {
        ByteBuffer pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        boolean handled = super.write(buffer, units, timeUs);
        pcm.limit(buffer.position());
        while (pcm.remaining() >= 2) {
            double time = acceptedSamples++ / (double) sampleRate;
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
                } else {
                    windows.add(new double[] {(acceptedSamples - samples / 2.0) / sampleRate, Double.NaN});
                }
                samples = 0;
                crossings = 0;
            }
        }
        return handled;
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
