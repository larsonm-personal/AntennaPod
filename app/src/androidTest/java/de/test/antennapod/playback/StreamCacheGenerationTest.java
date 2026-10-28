package de.test.antennapod.playback;

import android.content.Context;
import android.util.Log;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.test.platform.app.InstrumentationRegistry;
import de.danoeh.antennapod.playback.service.internal.StreamCacheGeneration;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

@OptIn(markerClass = UnstableApi.class)
public class StreamCacheGenerationTest {
    @Test
    public void persistedGenerationSurvivesProcessRestart() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        StreamCacheGeneration previous = new StreamCacheGeneration(context, "https://example.invalid/persistence");
        String expected = InstrumentationRegistry.getArguments().getString("expectedGeneration");
        if (expected != null) {
            assertEquals(expected, previous.token);
        } else {
            previous.invalidate();
            StreamCacheGeneration current = new StreamCacheGeneration(context, "https://example.invalid/persistence");
            assertNotEquals(previous.token, current.token);
            Log.i("StreamCacheGenerationTest", "persistedGeneration=" + current.token);
        }
    }

    @Test
    public void lateCommitCannotPopulateReplacementGeneration() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        StreamCacheGeneration previous = new StreamCacheGeneration(context, "https://example.invalid/late-write");
        File directory = new File(context.getCacheDir(), "generation-isolation-test");
        StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(context);
        SimpleCache cache = new SimpleCache(directory, new LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024), database);
        try {
            String oldKey = previous.cacheKey("audio");
            CacheSpan hole = cache.startReadWrite(oldKey, 0, 3);
            File file = cache.startFile(oldKey, 0, 3);
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(1);
                previous.invalidate();
                output.write(new byte[] {2, 3});
            }
            cache.commitFile(file, 3);
            cache.releaseHoleSpan(hole);
            StreamCacheGeneration current = new StreamCacheGeneration(context, "https://example.invalid/late-write");
            assertEquals(3, cache.getCachedBytes(oldKey, 0, 3));
            assertEquals(0, cache.getCachedBytes(current.cacheKey("audio"), 0, 3));
        } finally {
            cache.release();
            SimpleCache.delete(directory, database);
            database.close();
        }
    }
}
