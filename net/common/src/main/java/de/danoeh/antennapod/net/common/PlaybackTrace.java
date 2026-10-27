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
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.HashSet;
import java.util.Set;

public final class PlaybackTrace {
    public enum Event { START, STOP, SOURCE, HEAD, HEAD_FAILURE, RESOLVE, OPEN, RESPONSE, RANGE,
        READ, INPUT, CLOSE, FAILURE, CACHE, CACHE_IGNORED, POSITION, DISCONTINUITY, STATE, PLAYER_ERROR }

    private static final Set<Long> sources = new HashSet<>();
    private static byte[] salt = new byte[32];
    private static File directory;
    private static final int FILE_LIMIT = 1024 * 1024;
    private static final int FILE_COUNT = 8;
    private static final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1024), new ThreadPoolExecutor.AbortPolicy());
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
        detail("head." + request + ".source", tag.source);
        detail("head." + request + ".url", chain.request().url().toString());
        detail("head." + request + ".method", chain.request().method());
        for (String name : chain.request().headers().names()) {
            detail("head." + request + ".request." + name,
                    name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Cookie")
                            ? "[present]" : chain.request().header(name));
        }
        try {
            Response response = chain.proceed(chain.request());
            record(Event.HEAD, request, identity(response.request().url().toString()), response.code());
            for (String name : response.headers().names()) {
                detail("head." + request + ".response." + name,
                        name.equalsIgnoreCase("Set-Cookie") ? "[present]" : response.header(name));
            }
            return response;
        } catch (IOException error) {
            record(Event.HEAD_FAILURE, request, identity(tag.source));
            detail("head." + request + ".errorClass", error.getClass().getName());
            throw error;
        }
    }

    private static synchronized void start() {
        if (!BuildConfig.DEBUG) {
            return;
        }
        new SecureRandom().nextBytes(salt);
        sources.clear();
        sequence = 0;
        started = System.nanoTime();
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

    public static synchronized void initialize(File files) {
        if (BuildConfig.DEBUG) {
            directory = new File(files, "playback-diagnostics");
            start();
            detail("processStartedUtcMillis", Long.toString(System.currentTimeMillis()));
        }
    }

    public static synchronized void select(String source, boolean local) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        if (!active) {
            start();
        }
        sources.add(identity(source));
        record(Event.SOURCE, identity(source), local ? 1 : 0);
        detail("source", source);
    }

    private static void log(String line) {
        Log.d("PlaybackTrace", line);
        File destination = directory;
        if (destination != null) {
            String retained = line.length() > 16384 ? line.substring(0, 16384) + " [truncated]" : line;
            byte[] bytes = (System.currentTimeMillis() + " " + retained + "\n").getBytes(StandardCharsets.UTF_8);
            try {
                writer.execute(() -> {
                    try {
                        destination.mkdirs();
                        File current = new File(destination, "trace.0");
                        if (current.length() + bytes.length > FILE_LIMIT) {
                            new File(destination, "trace." + (FILE_COUNT - 1)).delete();
                            for (int i = FILE_COUNT - 2; i >= 0; i--) {
                                File old = new File(destination, "trace." + i);
                                if (old.exists() && !old.renameTo(new File(destination, "trace." + (i + 1)))) {
                                    throw new IOException("Diagnostic rotation failed");
                                }
                            }
                        }
                        try (FileOutputStream output = new FileOutputStream(current, true)) {
                            output.write(bytes);
                        }
                    } catch (IOException e) {
                        Log.e("PlaybackTrace", "Diagnostic storage unavailable");
                    }
                });
            } catch (RejectedExecutionException e) {
                Log.e("PlaybackTrace", "Diagnostic queue full; event dropped");
            }
        }
    }

    public static void exportDetailed(File output) throws IOException {
        if (!BuildConfig.DEBUG) {
            return;
        }
        try {
            int status = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-f", output.getAbsolutePath()})
                    .waitFor();
            if (status != 0) {
                throw new IOException("Logcat export failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Log export interrupted", e);
        }
        export(output);
    }

    public static void export(File output) throws IOException {
        if (!BuildConfig.DEBUG) {
            return;
        }
        File source = directory;
        try {
            writer.submit(() -> {
                try {
                    appendRetained(output, source);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Diagnostic export interrupted", e);
        } catch (ExecutionException | RejectedExecutionException e) {
            throw new IOException("Diagnostic export failed", e);
        }
    }

    static void appendRetained(File output, File source) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(output, true)) {
            stream.write("\nRetained playback diagnostics (oldest first)\n".getBytes(StandardCharsets.UTF_8));
            if (source != null) {
                for (int i = FILE_COUNT - 1; i >= 0; i--) {
                    File file = new File(source, "trace." + i);
                    if (file.exists()) {
                        try (FileInputStream input = new FileInputStream(file)) {
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                stream.write(buffer, 0, count);
                            }
                        }
                    }
                }
            }
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
            log(((System.nanoTime() - started) / 1000000) + " " + field + "="
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
        log(line.toString());
    }
}
