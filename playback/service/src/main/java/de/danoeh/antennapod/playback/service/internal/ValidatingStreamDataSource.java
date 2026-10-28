package de.danoeh.antennapod.playback.service.internal;

import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpUtil;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.ContentMetadataMutations;

import java.io.IOException;
import java.util.List;
import java.util.Collections;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
public final class ValidatingStreamDataSource implements DataSource {
    private final HttpDataSource upstream;
    private final Cache cache;
    private final StreamCacheGeneration generation;

    public ValidatingStreamDataSource(HttpDataSource upstream, Cache cache, StreamCacheGeneration generation) {
        this.upstream = upstream;
        this.cache = cache;
        this.generation = generation;
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        if (spec.key == null) {
            return upstream.open(spec);
        }
        ContentMetadata metadata = cache.getContentMetadata(spec.key);
        long previousLength = ContentMetadata.getContentLength(metadata);
        Uri previousUri = getCachedUri(metadata);
        boolean cached = previousLength != C.LENGTH_UNSET || previousUri != null
                || !cache.getCachedSpans(spec.key).isEmpty();
        long result;
        try {
            result = upstream.open(spec);
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
        long length = totalLength(upstream.getResponseCode(), upstream.getResponseHeaders());
        synchronized (cache) {
            metadata = cache.getContentMetadata(spec.key);
            previousLength = ContentMetadata.getContentLength(metadata);
            previousUri = getCachedUri(metadata);
            if ((previousLength != C.LENGTH_UNSET && length != C.LENGTH_UNSET && previousLength != length)
                    || (previousUri != null && !previousUri.equals(upstream.getUri()))) {
                throw new ChangedException(generation, spec.uri.toString(), false, null);
            }
            ContentMetadataMutations mutations = new ContentMetadataMutations();
            if (length != C.LENGTH_UNSET) {
                ContentMetadataMutations.setContentLength(mutations, length);
            }
            mutations.set("custom_stream_actual_uri", upstream.getUri().toString());
            cache.applyContentMetadataMutations(spec.key, mutations);
        }
        return result;
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
