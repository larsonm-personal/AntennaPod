package de.test.antennapod.playback.hazards;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioOutput;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@OptIn(markerClass = UnstableApi.class)
public class ResolvedTaggedAudioTest {
    @Test
    public void partialWritesMatchWholeWritesAndExcludeQueuedAudio() throws Exception {
        ResolvedTaggedAudio partial = new ResolvedTaggedAudio();
        FakeOutput device = new FakeOutput();
        device.limit = 0;
        AudioOutput output = partial.wrap(device.output(), () -> device.position * 44100 / 1000000);
        ByteBuffer data = sweep(500);
        output.write(data, 1, 0);
        assertEquals(0, data.position());
        assertTrue(partial.heard().isEmpty());
        device.limit = 8820;
        while (data.hasRemaining()) {
            output.write(data, 1, 0);
        }
        assertTrue(partial.heard().isEmpty());
        device.position = 2000000;
        output.getPositionUs();
        List<ResolvedTaggedAudio.Heard> heard = partial.heard();
        assertEquals(7, heard.size());
        assertEquals(0, heard.get(0).edition);
        ResolvedTaggedAudio whole = new ResolvedTaggedAudio();
        FakeOutput other = new FakeOutput();
        AudioOutput otherOutput = whole.wrap(other.output(), () -> other.position * 44100 / 1000000);
        otherOutput.write(sweep(500), 1, 0);
        other.position = device.position;
        otherOutput.getPositionUs();
        for (int index = 0; index < heard.size(); index++) {
            assertEquals(whole.heard().get(index).program, heard.get(index).program, 0);
            assertEquals(heard.get(index).outputSeconds, heard.get(index).program, 0.01);
        }
    }

    @Test
    public void flushReuseAndReplacementRetainOnlyPlayedEvidence() throws Exception {
        ResolvedTaggedAudio audio = new ResolvedTaggedAudio();
        FakeOutput device = new FakeOutput();
        AudioOutput output = audio.wrap(device.output(), () -> device.position * 44100 / 1000000);
        output.write(sweep(500), 1, 0);
        device.position = 2000000;
        output.flush();
        assertEquals(7, audio.heard().size());
        output.write(sweep(3500), 1, 0);
        assertEquals(7, audio.heard().size());
        device.position = 2000000;
        output.release();
        assertEquals(14, audio.heard().size());
        assertEquals(0, audio.heard().get(0).epoch);
        assertEquals(1, audio.heard().get(7).epoch);
        assertEquals(1, audio.heard().get(7).edition);
        FakeOutput replacement = new FakeOutput();
        output = audio.wrap(replacement.output(), () -> replacement.position * 44100 / 1000000);
        output.write(sweep(500), 1, 0);
        replacement.position = 2000000;
        output.getPositionUs();
        assertEquals(21, audio.heard().size());
        assertEquals(2, audio.heard().get(14).epoch);
        assertEquals(0, audio.heard().get(14).edition);
    }

    @Test(expected = AssertionError.class)
    public void unexplainedClockRegressionIsNotAccepted() {
        ResolvedTaggedAudio audio = new ResolvedTaggedAudio();
        FakeOutput device = new FakeOutput();
        AudioOutput output = audio.wrap(device.output(), () -> device.position * 44100 / 1000000);
        device.position = 2000000;
        audio.heard();
        device.position = 1000000;
        audio.heard();
    }

    @Test
    public void completeSegmentFollowsOneEdition() {
        assertEquals(4, ResolvedTaggedAudio.segment(Arrays.asList(
                heard(0, 1.125, 20.125), heard(0, 1.375, 20.375),
                heard(0, 1.625, 20.625), heard(0, 1.875, 20.875)), 0, 0.25).size());
    }

    @Test(expected = AssertionError.class)
    public void wrongPositionInsideSelectedSegmentIsRejected() {
        ResolvedTaggedAudio.segment(Arrays.asList(
                heard(0, 1.125, 20.125), heard(0, 1.375, 40.375),
                heard(0, 1.625, 20.625), heard(0, 1.875, 20.875)), 0, 1);
    }

    @Test(expected = AssertionError.class)
    public void incompleteSegmentCannotCountAsMeasured() {
        ResolvedTaggedAudio.segment(Arrays.asList(heard(0, 1.125, 20.125)), 0, 1);
    }

    @Test(expected = AssertionError.class)
    public void missingWindowCannotCountAsCompleteSegment() {
        ResolvedTaggedAudio.segment(Arrays.asList(
                heard(0, 1.125, 20.125), heard(0, 1.625, 20.625),
                heard(0, 1.875, 20.875), heard(0, 2.125, 21.125)), 0, 1);
    }

    @Test(expected = AssertionError.class)
    public void segmentCannotStraddleAnEditionChange() {
        ResolvedTaggedAudio.segment(Arrays.asList(
                heard(0, 1.125, 20.125), heard(0, 1.375, 20.375),
                heard(0, 1.625, 80.625), heard(0, 1.875, 80.875)), 0, 1);
    }

    @Test
    public void unsampledAudioIsRetainedButNotClassified() {
        List<ResolvedTaggedAudio.Heard> history = Arrays.asList(
                heard(0, 0.875, 40), heard(0, 1.125, 20.125), heard(0, 1.375, 20.375),
                heard(0, 1.625, 20.625), heard(0, 1.875, 20.875));
        ResolvedTaggedAudio.segment(history, 1, 0.25);
        assertEquals(40, history.get(0).program, 0);
    }

    private static ResolvedTaggedAudio.Heard heard(int epoch, double output, double program) {
        double base = program < 50 ? 500 : 3500;
        return new ResolvedTaggedAudio.Heard(new double[] {output, base + 12.5 * program}, epoch);
    }

    private static ByteBuffer sweep(int start) {
        ByteBuffer data = ByteBuffer.allocate(44100 * 4 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int sample = 0; sample < 44100 * 4; sample++) {
            double time = sample / 44100.0;
            data.putShort((short) Math.round(16000 * Math.sin(2 * Math.PI * (start * time + 6.25 * time * time))));
        }
        data.flip();
        return data;
    }

    private static final class FakeOutput {
        long position;
        int limit = Integer.MAX_VALUE;

        AudioOutput output() {
            return (AudioOutput) Proxy.newProxyInstance(AudioOutput.class.getClassLoader(),
                    new Class<?>[] {AudioOutput.class}, (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "getSampleRate": return 44100;
                            case "getPositionUs": return position;
                            case "write":
                                ByteBuffer buffer = (ByteBuffer) arguments[0];
                                buffer.position(buffer.position() + Math.min(limit, buffer.remaining()));
                                return !buffer.hasRemaining();
                            case "flush":
                                position = 0;
                                return null;
                            case "release": return null;
                            default: throw new AssertionError("Unexpected output call: " + method.getName());
                        }
                    });
        }
    }
}
