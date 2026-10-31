package de.danoeh.antennapod.playback.service.internal;

import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpUtil;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.ContentMetadataMutations;

import java.io.IOException;
import java.io.EOFException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@OptIn(markerClass = UnstableApi.class)
public final class ValidatingStreamDataSource implements DataSource {
    private final HttpDataSource upstream;
    private final HttpDataSource probe;
    private final Cache cache;
    private final StreamCacheGeneration generation;

    private DataSpec openingSpec;
    private IOException rejectedResponse;
    private long probePosition;
    private boolean rangeProbesSupported = true;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");
    private long responseRemaining;
    private long remaining;
    private byte[] left = new byte[0];
    private byte[] right = new byte[0];

    public ValidatingStreamDataSource(DefaultHttpDataSource.Factory factory, Cache cache,
                                     StreamCacheGeneration generation) {
        this.cache = cache;
        this.generation = generation;
        probe = factory.setContentTypePredicate(type -> probeRangeMatches()).createDataSource();
        upstream = factory.setContentTypePredicate(type -> {
            try {
                validateResponse(openingSpec, false);
                return true;
            } catch (IOException error) {
                rejectedResponse = error;
                return false;
            }
        }).createDataSource();
    }

    public ValidatingStreamDataSource(HttpDataSource upstream, Cache cache, StreamCacheGeneration generation) {
        this.upstream = upstream;
        probe = null;
        this.cache = cache;
        this.generation = generation;
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        openingSpec = spec;
        rejectedResponse = null;
        remaining = C.LENGTH_UNSET;
        responseRemaining = C.LENGTH_UNSET;
        left = new byte[0];
        right = new byte[0];
        if (spec.key == null) {
            return upstream.open(spec);
        }
        validateOverlap(spec);
        boolean expanded = left.length > 0 || right.length > 0;
        if (expanded) {
            if (spec.length > Long.MAX_VALUE - left.length - right.length
                    || spec.position - left.length > Long.MAX_VALUE - spec.length - left.length - right.length) {
                throw new IOException("Expanded range exceeds supported offsets");
            }
            openingSpec = spec.buildUpon().setPosition(spec.position - left.length)
                    .setLength(spec.length + left.length + right.length).build();
        }
        ContentMetadata metadata = cache.getContentMetadata(spec.key);
        long previousLength = ContentMetadata.getContentLength(metadata);
        Uri previousUri = getCachedUri(metadata);
        boolean cached = previousLength != C.LENGTH_UNSET || previousUri != null
                || !cache.getCachedSpans(spec.key).isEmpty();
        long result;
        try {
            result = upstream.open(openingSpec);
        } catch (HttpDataSource.InvalidContentTypeException error) {
            throw rejectedResponse == null ? error : rejectedResponse;
        } catch (HttpDataSource.InvalidResponseCodeException error) {
            long rejectedLength = error.responseCode == 416 ? totalLength(416, error.headerFields) : C.LENGTH_UNSET;
            validateCachedLength(spec, rejectedLength);
            if (previousLength != C.LENGTH_UNSET && rejectedLength != C.LENGTH_UNSET
                    && previousLength != rejectedLength) {
                throw new ChangedException(generation, spec.uri.toString(), false, error);
            }
            if (cached && (error.responseCode == 403 || error.responseCode == 404 || error.responseCode == 410)) {
                throw new ChangedException(generation, spec.uri.toString(), true, error);
            }
            throw error;
        }
        validateResponse(openingSpec, false);
        if (expanded) {
            compareActual(left);
        }
        long total = totalLength(upstream.getResponseCode(), upstream.getResponseHeaders());
        if (expanded || (upstream.getResponseCode() == 206 && spec.length != C.LENGTH_UNSET)) {
            remaining = right.length == 0 && total >= spec.position
                    ? Math.min(spec.length, total - spec.position) : spec.length;
        } else if (spec.length == C.LENGTH_UNSET && upstream.getResponseCode() == 206 && total >= spec.position) {
            remaining = total - spec.position;
            result = remaining;
        }
        validateResponse(openingSpec, true);
        return expanded ? spec.length : result;
    }

    private void validateResponse(DataSpec spec, boolean store) throws IOException {
        if (spec == null || spec.key == null) {
            return;
        }
        if (!store) {
            validateRange(spec);
        }
        long length = totalLength(upstream.getResponseCode(), upstream.getResponseHeaders());
        synchronized (cache) {
            validateCachedLength(spec, length);
            ContentMetadata metadata = cache.getContentMetadata(spec.key);
            long previousLength = ContentMetadata.getContentLength(metadata);
            Uri previousUri = getCachedUri(metadata);
            String etag = header(upstream.getResponseHeaders(), "ETag");
            String previousEtag = metadata.get("custom_stream_etag", (String) null);
            if ((previousLength != C.LENGTH_UNSET && length != C.LENGTH_UNSET && previousLength != length)
                    || (previousUri != null && !previousUri.equals(upstream.getUri()))
                    || (isStrongEtag(etag) && previousEtag != null && !previousEtag.equals(etag))) {
                throw new ChangedException(generation, spec.uri.toString(), false, null);
            }
            if (!store) {
                return;
            }
            ContentMetadataMutations mutations = new ContentMetadataMutations();
            if (length != C.LENGTH_UNSET) {
                ContentMetadataMutations.setContentLength(mutations, length);
            }
            mutations.set("custom_stream_actual_uri", upstream.getUri().toString());
            if (isStrongEtag(etag)) {
                mutations.set("custom_stream_etag", etag);
            }
            cache.applyContentMetadataMutations(spec.key, mutations);
        }
    }

    private void validateCachedLength(DataSpec spec, long length) throws IOException {
        if (length == C.LENGTH_UNSET) {
            return;
        }
        for (CacheSpan span : cache.getCachedSpans(spec.key)) {
            if (span.position > length || span.length > length - span.position) {
                generation.invalidate();
                throw new ChangedException(generation, spec.uri.toString(), false, null);
            }
        }
    }

    private void validateOverlap(DataSpec spec) throws IOException {
        CacheSpan before = null;
        CacheSpan after = null;
        CacheSpan prefix = null;
        boolean checkedPrefix = false;
        for (CacheSpan span : cache.getCachedSpans(spec.key)) {
            if (span.position == 0) {
                prefix = span;
            }
            if (span.position + span.length <= spec.position) {
                before = span;
            } else if (span.position >= spec.position) {
                after = span;
                break;
            }
        }
        if (before != null) {
            long position = before.position + before.length - Math.min(4096, before.length);
            if (spec.length != C.LENGTH_UNSET && before.position + before.length == spec.position) {
                left = cachedBytes(before, position);
            } else if (probe != null && rangeProbesSupported) {
                compareOverlap(spec, before, position);
                checkedPrefix = position == 0;
            }
        }
        if (after != null && spec.length != C.LENGTH_UNSET && after.position - spec.position == spec.length) {
            right = cachedBytes(after, after.position);
        } else if (probe != null && rangeProbesSupported && after != null
                && (spec.length == C.LENGTH_UNSET || after.position - spec.position <= spec.length)) {
            compareOverlap(spec, after, after.position);
            checkedPrefix |= after.position == 0;
        }
        if (probe != null && !rangeProbesSupported && prefix != null && !checkedPrefix) {
            compareOverlap(spec, prefix, 0);
        }
    }

    private static byte[] cachedBytes(CacheSpan span, long position) throws IOException {
        byte[] bytes = new byte[(int) Math.min(4096, span.position + span.length - position)];
        try (RandomAccessFile file = new RandomAccessFile(span.file, "r")) {
            file.seek(position - span.position);
            file.readFully(bytes);
        }
        return bytes;
    }

    private void validateRange(DataSpec spec) throws IOException {
        responseRemaining = C.LENGTH_UNSET;
        if (upstream.getResponseCode() != 206) {
            return;
        }
        String value = header(upstream.getResponseHeaders(), "Content-Range");
        for (Map.Entry<String, List<String>> entry : upstream.getResponseHeaders().entrySet()) {
            if ("Content-Encoding".equalsIgnoreCase(entry.getKey())
                    && !entry.getValue().equals(Collections.singletonList("identity"))) {
                throw new IOException("Encoded partial response cannot establish byte offsets");
            }
        }
        Matcher range = CONTENT_RANGE.matcher(value == null ? "" : value);
        if (!range.matches()) {
            throw new IOException("Missing or malformed Content-Range");
        }
        try {
            long start = Long.parseLong(range.group(1));
            long end = Long.parseLong(range.group(2));
            long total = "*".equals(range.group(3)) ? C.LENGTH_UNSET : Long.parseLong(range.group(3));
            if (start != spec.position || end < start || end == Long.MAX_VALUE
                    || (total != C.LENGTH_UNSET && end >= total)
                    || (spec.length != C.LENGTH_UNSET && end - start >= spec.length)) {
                throw new IOException("Unexpected partial response range");
            }
            responseRemaining = end - start + 1;
            for (Map.Entry<String, List<String>> entry : upstream.getResponseHeaders().entrySet()) {
                if ("Content-Length".equalsIgnoreCase(entry.getKey())) {
                    for (String contentLength : entry.getValue()) {
                        if (Long.parseLong(contentLength.trim()) != responseRemaining) {
                            throw new IOException("Content-Length disagrees with partial response range");
                        }
                    }
                }
            }
        } catch (NumberFormatException error) {
            throw new IOException("Invalid partial response length or offset", error);
        }
    }

    private int readActual(byte[] buffer, int offset, int length) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Stream read interrupted");
        }
        if (responseRemaining == 0) {
            throw new EOFException("Partial response ended before requested data");
        }
        int count = upstream.read(buffer, offset, responseRemaining == C.LENGTH_UNSET
                ? length : (int) Math.min(length, responseRemaining));
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Stream read interrupted");
        }
        if (count == C.RESULT_END_OF_INPUT) {
            throw new EOFException("Response ended before requested data");
        }
        if (responseRemaining != C.LENGTH_UNSET) {
            responseRemaining -= count;
        }
        return count;
    }

    private void compareActual(byte[] expected) throws IOException {
        byte[] actual = new byte[expected.length];
        int read = 0;
        while (read < actual.length) {
            read += readActual(actual, read, actual.length - read);
        }
        if (!Arrays.equals(expected, actual)) {
            generation.invalidate();
            throw new ChangedException(generation, openingSpec.uri.toString(), false, null);
        }
    }

    private boolean probeRangeMatches() {
        String range = header(probe.getResponseHeaders(), "Content-Range");
        return (probe.getResponseCode() == 200 && probePosition == 0)
                || (probe.getResponseCode() == 206 && range != null
                && range.startsWith("bytes " + probePosition + "-"));
    }

    private void compareOverlap(DataSpec spec, CacheSpan span, long position) throws IOException {
        byte[] expected = cachedBytes(span, position);
        int size = expected.length;
        probePosition = position;
        try {
            probe.open(spec.buildUpon().setPosition(position).setLength(size).setKey(null).build());
            if (probe.getResponseCode() == 200) {
                rangeProbesSupported = false;
            }
            byte[] actual = new byte[size];
            int read = 0;
            while (read < size) {
                int count = probe.read(actual, read, size - read);
                if (count == C.RESULT_END_OF_INPUT) {
                    break;
                }
                read += count;
            }
            if (read != size || !Arrays.equals(expected, actual)) {
                if (read == size) {
                    generation.invalidate();
                }
                throw new ChangedException(generation, spec.uri.toString(), false, null);
            }
        } catch (HttpDataSource.InvalidContentTypeException error) {
            if (probe.getResponseCode() == 200) {
                rangeProbesSupported = false;
                return;
            }
            throw new ChangedException(generation, spec.uri.toString(), false, error);
        } catch (HttpDataSource.InvalidResponseCodeException error) {
            if (error.responseCode == 403 || error.responseCode == 404 || error.responseCode == 410) {
                throw new ChangedException(generation, spec.uri.toString(), true, error);
            }
            if (error.responseCode == 416) {
                throw new ChangedException(generation, spec.uri.toString(), false, error);
            }
            throw error;
        } finally {
            probe.close();
        }
    }

    private static boolean isStrongEtag(String etag) {
        return etag != null && etag.startsWith("\"") && etag.endsWith("\"");
    }

    private static String header(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue().size() == 1) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    static Uri getCachedUri(ContentMetadata metadata) {
        String uri = metadata.get("custom_stream_actual_uri", (String) null);
        return uri == null ? ContentMetadata.getRedirectedUri(metadata) : Uri.parse(uri);
    }

    static long totalLength(int status, Map<String, List<String>> headers) {
        String name = status == 200 ? "Content-Length" : "Content-Range";
        if (status != 200 && status != 206 && status != 416) {
            return C.LENGTH_UNSET;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if ("Content-Encoding".equalsIgnoreCase(entry.getKey())
                    && !entry.getValue().equals(Collections.singletonList("identity"))) {
                return C.LENGTH_UNSET;
            }
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue().size() == 1) {
                String value = entry.getValue().get(0);
                if (status != 200) {
                    return HttpUtil.getDocumentSize(value);
                }
                try {
                    long length = Long.parseLong(value);
                    return length >= 0 ? length : C.LENGTH_UNSET;
                } catch (NumberFormatException invalid) {
                    return C.LENGTH_UNSET;
                }
            }
        }
        return C.LENGTH_UNSET;
    }

    @Override
    public void addTransferListener(TransferListener listener) {
        upstream.addTransferListener(listener);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (remaining == C.LENGTH_UNSET) {
            return upstream.read(buffer, offset, length);
        }
        if (length == 0) {
            return 0;
        }
        if (remaining == 0) {
            return C.RESULT_END_OF_INPUT;
        }
        if (rejectedResponse != null) {
            throw rejectedResponse;
        }
        try {
            int count = readActual(buffer, offset, (int) Math.min(length, remaining));
            if (remaining == count) {
                compareActual(right);
            }
            remaining -= count;
            return count;
        } catch (IOException error) {
            rejectedResponse = error;
            throw error;
        }
    }

    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        upstream.close();
    }

    public static final class ChangedException extends IOException {
        public final StreamCacheGeneration generation;
        public final String failedUri;
        public final boolean needsResolution;

        ChangedException(StreamCacheGeneration generation, String failedUri, boolean needsResolution, Throwable cause) {
            super("Streaming resource requires fresh preparation", cause);
            this.generation = generation;
            this.failedUri = failedUri;
            this.needsResolution = needsResolution;
        }

        public static ChangedException find(Throwable error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof ChangedException) {
                    return (ChangedException) cause;
                }
            }
            return null;
        }
    }
}
