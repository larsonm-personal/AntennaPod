package de.danoeh.antennapod.net.common;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;

import java.util.stream.Collectors;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class PlaybackTraceTest {
    @After
    public void stop() {
        PlaybackTrace.stop();
    }

    @Test
    public void playbackAutomaticallySelectsSources() {
        String feed = "https://private.invalid/secret/feed?token=TOKEN";
        String audio = "https://private.invalid/secret/audio?next=https%3A%2F%2Fnested.invalid%2FTOKEN";

        assertFalse(PlaybackTrace.matches("file:///secret/other"));
        PlaybackTrace.select(audio, false);
        assertEquals(BuildConfig.DEBUG, PlaybackTrace.matches(audio));
        PlaybackTrace.select("file:///secret/local", true);
        assertEquals(BuildConfig.DEBUG, PlaybackTrace.matches("file:///secret/local"));
        assertFalse(PlaybackTrace.matches(audio + "unrelated"));
        if (BuildConfig.DEBUG) {
            assertTrue(logged().contains("TOKEN"));
            assertFalse(logged().contains("/secret/other"));
        }
    }

    @Test
    public void stoppedTraceDoesNotLogAndSessionIdentifiersChange() {
        String secret = "https://user:password@private.invalid/TOKEN?redirect=https%253A%252F%252Fnested.invalid%252FTOKEN";
        PlaybackTrace.select(secret, false);
        PlaybackTrace.record(PlaybackTrace.Event.RESPONSE, PlaybackTrace.identity(secret),
                PlaybackTrace.identity("Authorization: Bearer TOKEN"),
                PlaybackTrace.identity(new IllegalArgumentException(secret).toString()));
        assertFalse(logged().contains("Authorization: Bearer TOKEN"));
        long previous = PlaybackTrace.identity(secret);
        PlaybackTrace.stop();
        PlaybackTrace.select(secret, false);
        if (BuildConfig.DEBUG) {
            assertNotEquals(previous, PlaybackTrace.identity(secret));
        }
        PlaybackTrace.stop();
        String stopped = logged();
        PlaybackTrace.record(PlaybackTrace.Event.FAILURE, 403);
        assertEquals(stopped, logged());
    }

    @Test
    public void retainedFilesSurviveReinitializationAndRotateWithinLimit() throws Exception {
        File root = Files.createTempDirectory("playback-trace-test").toFile();
        PlaybackTrace.initialize(root);
        PlaybackTrace.select("https://synthetic.invalid/episode", false);
        File directory = new File(root, "playback-diagnostics");
        if (!BuildConfig.DEBUG) {
            assertFalse(directory.exists());
            return;
        }
        File firstExport = new File(root, "first.txt");
        PlaybackTrace.export(firstExport);
        assertTrue(new String(Files.readAllBytes(firstExport.toPath()), StandardCharsets.UTF_8)
                .contains("synthetic.invalid"));
        PlaybackTrace.initialize(root);
        File restarted = new File(root, "restarted.txt");
        PlaybackTrace.export(restarted);
        assertTrue(new String(Files.readAllBytes(restarted.toPath()), StandardCharsets.UTF_8)
                .contains("synthetic.invalid"));
        for (int i = 0; i < 8; i++) {
            Files.write(new File(directory, "trace." + i).toPath(), new byte[1024 * 1024]);
        }
        PlaybackTrace.detail("rotation", "sentinel");
        File rotated = new File(root, "rotated.txt");
        PlaybackTrace.export(rotated);
        assertEquals(8, directory.listFiles().length);
        long total = 0;
        for (File file : directory.listFiles()) {
            assertTrue(file.length() <= 1024 * 1024);
            total += file.length();
        }
        assertTrue(total <= 8 * 1024 * 1024);
        assertTrue(new String(Files.readAllBytes(rotated.toPath()), StandardCharsets.UTF_8).contains("sentinel"));
    }

    private String logged() {
        return ShadowLog.getLogsForTag("PlaybackTrace").stream().map(entry -> entry.msg)
                .collect(Collectors.joining("\n"));
    }

}
