package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
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
    @Test
    public void failedCommitRestoresMemoryAndDoesNotBecomeAFalseDurableGeneration() throws Exception {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        Map<String, String> memory = new HashMap<>();
        Map<String, String> disk = new HashMap<>();
        AtomicBoolean failWrites = new AtomicBoolean(true);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(preferences.getString(anyString(), anyString())).thenAnswer(call ->
                memory.getOrDefault(call.getArgument(0), call.getArgument(1)));
        when(preferences.edit()).thenAnswer(call -> {
            SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
            Map<String, String> pending = new HashMap<>();
            when(editor.putString(anyString(), anyString())).thenAnswer(write -> {
                pending.put(write.getArgument(0), write.getArgument(1));
                return editor;
            });
            when(editor.commit()).thenAnswer(commit -> {
                memory.putAll(pending);
                if (failWrites.get()) {
                    return false;
                }
                disk.putAll(pending);
                return true;
            });
            return editor;
        });
        String uri = "https://example.invalid/write-failure";
        StreamCacheGeneration original = new StreamCacheGeneration(context, uri);
        assertThrows(IOException.class, original::invalidate);
        assertEquals(original.token, new StreamCacheGeneration(context, uri).token);
        memory.clear();
        memory.putAll(disk);
        assertEquals(original.token, new StreamCacheGeneration(context, uri).token);
        failWrites.set(false);
        original.invalidate();
        String persisted = new StreamCacheGeneration(context, uri).token;
        assertNotEquals(original.token, persisted);
        memory.clear();
        memory.putAll(disk);
        assertEquals(persisted, new StreamCacheGeneration(context, uri).token);
    }
}
