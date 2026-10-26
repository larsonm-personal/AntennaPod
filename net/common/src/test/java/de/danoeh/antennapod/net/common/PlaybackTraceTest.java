package de.danoeh.antennapod.net.common;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;

import java.util.stream.Collectors;

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
    public void onlyExactTargetSourcesAreSelected() {
        String feed = "https://private.invalid/secret/feed?token=TOKEN";
        String audio = "https://private.invalid/secret/audio?next=https%3A%2F%2Fnested.invalid%2FTOKEN";
        PlaybackTrace.start(feed);
        PlaybackTrace.select(audio, feed + "other", "file:///secret/other", true);
        assertFalse(PlaybackTrace.matches("file:///secret/other"));
        PlaybackTrace.select(audio, feed, audio, false);
        assertEquals(BuildConfig.DEBUG, PlaybackTrace.matches(audio));
        PlaybackTrace.select(audio, feed, "file:///secret/local", true);
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
        PlaybackTrace.start(secret);
        PlaybackTrace.record(PlaybackTrace.Event.RESPONSE, PlaybackTrace.identity(secret),
                PlaybackTrace.identity("Authorization: Bearer TOKEN"),
                PlaybackTrace.identity(new IllegalArgumentException(secret).toString()));
        assertFalse(logged().contains("Authorization: Bearer TOKEN"));
        long previous = PlaybackTrace.identity(secret);
        PlaybackTrace.start(secret);
        if (BuildConfig.DEBUG) {
            assertNotEquals(previous, PlaybackTrace.identity(secret));
        }
        PlaybackTrace.stop();
        String stopped = logged();
        PlaybackTrace.record(PlaybackTrace.Event.FAILURE, 403);
        assertEquals(stopped, logged());
    }

    private String logged() {
        return ShadowLog.getLogsForTag("PlaybackTrace").stream().map(entry -> entry.msg)
                .collect(Collectors.joining("\n"));
    }

}
