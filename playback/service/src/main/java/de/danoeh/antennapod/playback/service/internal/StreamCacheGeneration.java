package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class StreamCacheGeneration {
    private final SharedPreferences preferences;
    private final String identifier;
    public final String token;
    public final String enclosure;

    public StreamCacheGeneration(Context context, String enclosure) {
        this.enclosure = enclosure;
        preferences = context.getSharedPreferences("stream-cache-generations", Context.MODE_PRIVATE);
        try {
            identifier = Base64.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(enclosure.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        synchronized (StreamCacheGeneration.class) {
            token = preferences.getString(identifier, "0");
        }
    }

    public String cacheKey(String resource) {
        return "resolved-stream-v2:" + identifier + ":" + token + ":" + resource;
    }

    public void invalidate() throws IOException {
        synchronized (StreamCacheGeneration.class) {
            if (token.equals(preferences.getString(identifier, "0"))
                    && !preferences.edit().putString(identifier, UUID.randomUUID().toString()).commit()) {
                preferences.edit().putString(identifier, token).commit();
                throw new IOException("Could not persist streaming cache invalidation");
            }
        }
    }
}
