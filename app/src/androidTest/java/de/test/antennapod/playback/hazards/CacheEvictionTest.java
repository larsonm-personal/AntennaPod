package de.test.antennapod.playback.hazards;

import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.test.filters.LargeTest;

import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@LargeTest
@OptIn(markerClass = UnstableApi.class)
@IgnoreOnCi
public class CacheEvictionTest extends PlaybackCase {
    @Test
    public void evictedStableEditionKeepsPassagesReachable() throws Exception {
        primeCachedEpisode(false);
        evictPrimedAudio();
        playOriginalAndCheckPassages();
    }

    @Test
    public void evictedEpisodeMayUseFreshEdition() throws Exception {
        primeCachedEpisode(false);
        evictPrimedAudio();
        app.server.switchFreshResolutionsToB();
        playOriginalAndCheckPassages();
    }

    private void playOriginalAndCheckPassages() throws Exception {
        app.connect();
        select(app.media, 87);
        int first = audio.heard().size();
        app.onMain(() -> app.controller.play());
        sampledContent(first, false);
        checkPassages();
    }

    private void evictPrimedAudio() throws Exception {
        List<File> primed = cacheSpans(new File(app.context.getCacheDir(), "streaming"));
        assertFalse("No primed spans in the production cache", primed.isEmpty());
        app.server.resumeResponses();
        app.connect();
        for (int episode = 0; episode < 26; episode++) {
            select(addEpisode("pressure/" + episode), 0);
            awaitFullyBuffered();
            Log.i("ServiceAudioObserver", "pressure episode=" + episode + " fully buffered");
        }
        app.stopService();
        long bytes = 0;
        for (File span : cacheSpans(new File(app.context.getCacheDir(), "streaming"))) {
            bytes += span.length();
        }
        assertTrue("Cache pressure did not fill the real cache: " + bytes, bytes > 95L * 1024 * 1024);
        assertTrue("Cache exceeded its production bound: " + bytes, bytes <= 100L * 1024 * 1024);
        for (File span : primed) {
            assertFalse("Primed cache span survived LRU pressure: " + span.getName(), span.exists());
        }
        Log.i("ServiceAudioObserver", "eviction removedSpans=" + primed.size() + " cacheBytes=" + bytes);
    }

    private List<File> cacheSpans(File directory) {
        List<File> spans = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    spans.addAll(cacheSpans(file));
                } else if (file.getName().endsWith(".v3.exo")) {
                    spans.add(file);
                }
            }
        }
        return spans;
    }
}
