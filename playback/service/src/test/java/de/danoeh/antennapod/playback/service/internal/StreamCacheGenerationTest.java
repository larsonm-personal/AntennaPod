package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

@RunWith(RobolectricTestRunner.class)
public class StreamCacheGenerationTest {
    @Test
    public void invalidationPersistsWithoutChangingOldSourcesOrOtherEpisodes() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String enclosure = "https://example.invalid/episode?token=synthetic";
        StreamCacheGeneration first = new StreamCacheGeneration(context, enclosure);
        StreamCacheGeneration concurrent = new StreamCacheGeneration(context, enclosure);
        StreamCacheGeneration other = new StreamCacheGeneration(context, enclosure + "2");
        String oldKey = first.cacheKey(enclosure);
        first.invalidate();
        StreamCacheGeneration next = new StreamCacheGeneration(context, enclosure);
        assertNotEquals(oldKey, next.cacheKey(enclosure));
        assertEquals(oldKey, first.cacheKey(enclosure));
        concurrent.invalidate();
        assertEquals(next.token, new StreamCacheGeneration(context, enclosure).token);
        assertEquals(other.token, new StreamCacheGeneration(context, enclosure + "2").token);
        assertFalse(context.getSharedPreferences("stream-cache-generations", Context.MODE_PRIVATE)
                .getAll().toString().contains(enclosure));
    }
}
