package de.test.antennapod.playback;

import android.content.Context;
import android.util.Log;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import de.danoeh.antennapod.playback.service.internal.ExoPlayerUtils;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@LargeTest
@OptIn(markerClass = UnstableApi.class)
public class DynamicAdPlaybackTest {
    private static final String TAG = "DynamicAdPlaybackTest";
    private static final int DISCONNECT_BYTE = 320000;
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
    public void dynamicAdByteRangesMatchResponseHeaders() throws Exception {
        server.serveDynamicAds(initial, changed, DISCONNECT_BYTE);
        HttpURLConnection connection = (HttpURLConnection) new URL(
                server.getBaseUrl() + "/dynamic-ad/episode").openConnection();
        connection.setRequestProperty("Range", "bytes=10-29");
        try {
            assertEquals(206, connection.getResponseCode());
            assertEquals("bytes 10-29/" + initial.length(), connection.getHeaderField("Content-Range"));
            assertEquals(20, connection.getContentLength());
            byte[] expected = new byte[20];
            try (InputStream input = new FileInputStream(initial)) {
                IOUtils.skipFully(input, 10);
                IOUtils.readFully(input, expected);
            }
            try (InputStream input = connection.getInputStream()) {
                assertArrayEquals(expected, IOUtils.toByteArray(input));
            }
        } finally {
            connection.disconnect();
        }
    }

    @Test
    public void stableReconnectPreservesContent() throws Exception {
        assertContinuous(initial);
    }

    @Test
    public void changedAdReconnectPreservesContent() throws Exception {
        assertContinuous(changed);
    }

    private void assertContinuous(File reconnected) throws Exception {
        server.serveDynamicAds(initial, reconnected, DISCONNECT_BYTE);
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
            player.setMediaItem(MediaItem.fromUri(server.getBaseUrl() + "/dynamic-ad/" + initial.getName()));
            player.prepare();
            player.play();
        });
        Awaitility.await().atMost(120, TimeUnit.SECONDS).until(() -> {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertEquals("Player error", null, player.getPlayerError());
                playerPosition = player.getCurrentPosition();
                Log.i(TAG, "playerPosition=" + playerPosition + " consumedUs=" + sink.consumedUs);
            });
            return sink.consumedUs >= 27000000;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> player.pause());
        assertEquals("Reconnect must request the interrupted byte", DISCONNECT_BYTE, server.getResumedByte());
        assertFalse("PCM was flushed before it could be consumed", sink.flushedAfterPcm);
        List<double[]> windows = sink.consumedWindows();
        int afterReconnect = 0;
        double worstError = 0;
        for (double[] window : windows) {
            double mediaTime = window[0];
            if (mediaTime < 1 || (mediaTime > 4.7 && mediaTime < 7.3)) {
                continue;
            }
            double expected = mediaTime < 5 ? mediaTime : mediaTime - 2;
            double observed = (window[1] - 20) / ((10000.0 - 20) / 120);
            double error = observed - expected;
            Log.i(TAG, "variant=" + reconnected.getName() + " media=" + mediaTime
                    + " expectedContent=" + expected + " observedContent=" + observed
                    + " error=" + error + " consumed=" + sink.consumedUs / 1000000.0
                    + " playerPosition=" + playerPosition / 1000.0);
            worstError = Math.max(worstError, Math.abs(error));
            if (mediaTime > 22) {
                afterReconnect++;
            }
        }
        assertTrue("No consumed PCM after reconnect", afterReconnect >= 8);
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
