package de.test.antennapod.playback;

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
    public void cleanOneTimeEditionTransitionIsAccepted() {
        ResolvedTaggedAudio.assertIntegrity(Arrays.asList(
                heard(0, 0, 20), heard(0, 0.25, 20.25),
                heard(1, 0, 80), heard(1, 0.25, 80.25)), 0.25);
    }

    @Test
    public void oneSplitWindowDoesNotInventAProgramPosition() throws Exception {
        ResolvedTaggedAudio audio = new ResolvedTaggedAudio();
        FakeOutput device = new FakeOutput();
        AudioOutput output = audio.wrap(device.output(), () -> device.position * 44100 / 1000000);
        ByteBuffer first = sweep(500);
        first.limit(49612 * 2);
        output.write(first, 1, 0);
        output.write(sweep(4500), 1, 0);
        device.position = 4000000;
        List<ResolvedTaggedAudio.Heard> heard = audio.heard();
        assertEquals(-1, heard.get(4).edition);
        assertEquals(15, heard.size());
        ResolvedTaggedAudio.assertIntegrity(heard, 1);
    }

    @Test(expected = AssertionError.class)
    public void revisitingPatchworkDoesNotBecomeCoherent() {
        ResolvedTaggedAudio.assertIntegrity(Arrays.asList(
                heard(0, 0, 20), heard(1, 0, 80), heard(2, 0, 20)), 0.25);
    }

    @Test(expected = AssertionError.class)
    public void laterCleanAudioDoesNotEraseAnEarlierJump() {
        ResolvedTaggedAudio.assertIntegrity(Arrays.asList(
                heard(0, 0, 20), heard(0, 0.25, 40), heard(0, 0.5, 40.25),
                heard(0, 0.75, 40.5), heard(1, 0, 80)), 0.25);
    }

    @Test(expected = AssertionError.class)
    public void smallErrorsCannotAccumulateAcrossAContinuousSegment() {
        ResolvedTaggedAudio.assertIntegrity(Arrays.asList(
                heard(0, 0, 20), heard(0, 0.5, 20), heard(0, 1, 20), heard(0, 1.5, 20)), 1);
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
