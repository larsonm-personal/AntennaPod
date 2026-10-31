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
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.Map;

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
        if (spec.key == null) {
            return upstream.open(spec);
        }
        validateOverlap(spec);
        ContentMetadata metadata = cache.getContentMetadata(spec.key);
        long previousLength = ContentMetadata.getContentLength(metadata);
        Uri previousUri = getCachedUri(metadata);
        boolean cached = previousLength != C.LENGTH_UNSET || previousUri != null
                || !cache.getCachedSpans(spec.key).isEmpty();
        long result;
        try {
            result = upstream.open(spec);
        } catch (HttpDataSource.InvalidContentTypeException error) {
            throw rejectedResponse == null ? error : rejectedResponse;
        } catch (HttpDataSource.InvalidResponseCodeException error) {
            long rejectedLength = error.responseCode == 416 ? totalLength(416, error.headerFields) : C.LENGTH_UNSET;
            if (previousLength != C.LENGTH_UNSET && rejectedLength != C.LENGTH_UNSET
                    && previousLength != rejectedLength) {
                throw new ChangedException(generation, spec.uri.toString(), false, error);
            }
            if (cached && (error.responseCode == 403 || error.responseCode == 404 || error.responseCode == 410)) {
                throw new ChangedException(generation, spec.uri.toString(), true, error);
            }
            throw error;
        }
        validateResponse(spec, true);
        return result;
    }

    private void validateResponse(DataSpec spec, boolean store) throws IOException {
        if (spec == null || spec.key == null) {
            return;
        }
        long length = totalLength(upstream.getResponseCode(), upstream.getResponseHeaders());
        synchronized (cache) {
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

    private void validateOverlap(DataSpec spec) throws IOException {
        if (probe == null || !rangeProbesSupported) {
            return;
        }
        CacheSpan before = null;
        CacheSpan after = null;
        for (CacheSpan span : cache.getCachedSpans(spec.key)) {
            if (span.position + span.length <= spec.position) {
                before = span;
            } else if (span.position >= spec.position) {
                after = span;
                break;
            }
        }
        if (before != null) {
            compareOverlap(spec, before, before.position + before.length - Math.min(4096, before.length));
        }
        if (rangeProbesSupported && after != null
                && (spec.length == C.LENGTH_UNSET || after.position <= spec.position + spec.length)) {
            compareOverlap(spec, after, after.position);
        }
    }

    private boolean probeRangeMatches() {
        String range = header(probe.getResponseHeaders(), "Content-Range");
        return probe.getResponseCode() == 206 && range != null && range.startsWith("bytes " + probePosition + "-");
    }

    private void compareOverlap(DataSpec spec, CacheSpan span, long position) throws IOException {
        int size = (int) Math.min(4096, span.position + span.length - position);
        byte[] expected = new byte[size];
        try (RandomAccessFile file = new RandomAccessFile(span.file, "r")) {
            file.seek(position - span.position);
            file.readFully(expected);
        }
        probePosition = position;
        try {
            probe.open(spec.buildUpon().setPosition(position).setLength(size).setKey(null).build());
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
        return upstream.read(buffer, offset, length);
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
