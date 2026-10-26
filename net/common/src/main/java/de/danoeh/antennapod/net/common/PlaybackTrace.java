package de.danoeh.antennapod.net.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import android.util.Log;
import org.json.JSONObject;
import okhttp3.Interceptor;
import okhttp3.Response;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public final class PlaybackTrace {
    public enum Event { START, STOP, SOURCE, HEAD, HEAD_FAILURE, RESOLVE, OPEN, RESPONSE, RANGE,
        READ, CLOSE, FAILURE, CACHE, CACHE_IGNORED, POSITION, DISCONTINUITY, STATE, PLAYER_ERROR }

    private static final Set<Long> sources = new HashSet<>();
    private static byte[] salt = new byte[32];
    private static long target;
    private static long started;
    private static long sequence;
    private static boolean active;

    private PlaybackTrace() {
    }

    public static final class HeadRequest {
        final String source;

        public HeadRequest(String source) {
            this.source = source;
        }
    }

    public static Response observeHead(Interceptor.Chain chain) throws IOException {
        HeadRequest tag = chain.request().tag(HeadRequest.class);
        if (tag == null || !matches(tag.source)) {
            return chain.proceed(chain.request());
        }
        long request = nextRequest();
        detail("head." + request + ".url", chain.request().url().toString());
        detail("head." + request + ".method", chain.request().method());
        for (String name : new String[]{"User-Agent", "Accept-Encoding", "Range", "If-Range"}) {
            detail("head." + request + "." + name, chain.request().header(name));
        }
        for (String name : new String[]{"Authorization", "Cookie"}) {
            detail("head." + request + "." + name + "Present",
                    Boolean.toString(chain.request().header(name) != null));
        }
        Response response = chain.proceed(chain.request());
        record(Event.HEAD, request, identity(response.request().url().toString()), response.code());
        for (String name : new String[]{"Location", "Content-Length", "Content-Range", "ETag", "Last-Modified",
                "Content-Type", "Accept-Ranges"}) {
            detail("head." + request + ".response." + name, response.header(name));
        }
        return response;
    }

    public static synchronized void start(String url) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        new SecureRandom().nextBytes(salt);
        sources.clear();
        sequence = 0;
        started = System.nanoTime();
        target = identity(url);
        sources.add(target);
        active = true;
        record(Event.START);
    }

    public static synchronized void stop() {
        record(Event.STOP);
        active = false;
        sources.clear();
    }

    public static synchronized long identity(String value) {
        if (value == null) {
            return 0;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            long result = 0;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (bytes[i] & 255L);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException();
        }
    }

    public static synchronized void select(String stream, String feed, String source, boolean local) {
        if (active && (identity(stream) == target || identity(feed) == target)) {
            sources.add(identity(source));
            record(Event.SOURCE, identity(source), local ? 1 : 0);
            detail("source", source);
        }
    }

    public static synchronized boolean matches(String source) {
        return BuildConfig.DEBUG && active && sources.contains(identity(source));
    }

    public static synchronized long nextRequest() {
        return ++sequence;
    }

    public static synchronized void detail(String field, String value) {
        if (BuildConfig.DEBUG && active) {
            Log.d("PlaybackTrace", ((System.nanoTime() - started) / 1000000) + " " + field + "="
                    + JSONObject.quote(value == null ? "" : value));
        }
    }

    public static synchronized void record(Event event, long... values) {
        if (!BuildConfig.DEBUG || !active) {
            return;
        }
        StringBuilder line = new StringBuilder().append((System.nanoTime() - started) / 1000000)
                .append(' ').append(event.name());
        for (long value : values) {
            line.append(' ').append(value);
        }
        Log.d("PlaybackTrace", line.toString());
    }
}
