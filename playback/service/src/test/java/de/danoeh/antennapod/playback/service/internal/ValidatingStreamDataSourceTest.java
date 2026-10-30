package de.danoeh.antennapod.playback.service.internal;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.DataSource;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.datasource.cache.DefaultContentMetadata;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.Files;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
public class ValidatingStreamDataSourceTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();
    private final DefaultHttpDataSource upstream = mock(DefaultHttpDataSource.class);
    private final Cache cache = mock(Cache.class);
    private final DataSpec spec = new DataSpec.Builder().setUri("https://example.invalid/A")
            .setKey("key").setPosition(100).setLength(100).build();
    private ValidatingStreamDataSource source;

    @Before
    public void setUp() throws Exception {
        when(cache.getCachedSpans(anyString())).thenReturn(new TreeSet<>());
        ContentMetadataMutations metadata = new ContentMetadataMutations();
        ContentMetadataMutations.setContentLength(metadata, 1000);
        ContentMetadataMutations.setRedirectedUri(metadata, spec.uri);
        when(cache.getContentMetadata(anyString())).thenReturn(
                new DefaultContentMetadata().copyWithMutationsApplied(metadata));
        when(upstream.open(spec)).thenReturn(100L);
        when(upstream.getUri()).thenReturn(spec.uri);
        when(upstream.getResponseCode()).thenReturn(206);
        source = new ValidatingStreamDataSource(upstream, cache,
                new StreamCacheGeneration(RuntimeEnvironment.getApplication(), spec.uri.toString()));
    }

    @Test
    public void changedStrongEtagRejectsAnEqualLengthResponse() {
        ContentMetadataMutations metadata = new ContentMetadataMutations().set("custom_stream_etag", "\"A\"");
        when(cache.getContentMetadata(anyString())).thenReturn(
                new DefaultContentMetadata().copyWithMutationsApplied(metadata));
        when(upstream.getResponseHeaders()).thenReturn(Map.of("ETag", List.of("\"B\"")));
        assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(spec));
    }

    @Test
    public void matchingStrongEtagAllowsTheResponse() throws Exception {
        ContentMetadataMutations metadata = new ContentMetadataMutations().set("custom_stream_etag", "\"A\"");
        when(cache.getContentMetadata(anyString())).thenReturn(
                new DefaultContentMetadata().copyWithMutationsApplied(metadata));
        when(upstream.getResponseHeaders()).thenReturn(Map.of("ETag", List.of("\"A\"")));
        assertEquals(100, source.open(spec));
    }

    @Test
    public void matchingCacheSeamsCostAtMostEightKilobytes() throws Exception {
        checkOverlaps(-1);
    }

    @Test
    public void changedLeftCacheSeamRejectsTheOpen() throws Exception {
        checkOverlaps(4096);
    }

    @Test
    public void changedRightCacheSeamRejectsTheOpen() throws Exception {
        checkOverlaps(16384);
    }

    @Test
    public void ignoredProbeRangeDoesNotInvalidateStableContent() throws Exception {
        checkIgnoredProbeRange(false);
    }

    @Test
    public void ignoredProbeRangeStillRejectsAChangedTotal() throws Exception {
        checkIgnoredProbeRange(true);
    }

    private void checkIgnoredProbeRange(boolean changed) throws Exception {
        File bytes = temporary.newFile();
        Files.write(bytes.toPath(), new byte[100]);
        when(cache.getCachedSpans("key")).thenReturn(new TreeSet<>(List.of(
                new CacheSpan("key", 0, 100, 0, bytes))));
        DefaultHttpDataSource probe = mock(DefaultHttpDataSource.class);
        DefaultHttpDataSource.Factory factory = mock(DefaultHttpDataSource.Factory.class);
        when(factory.setContentTypePredicate(any())).thenReturn(factory);
        when(factory.createDataSource()).thenReturn(probe, upstream);
        when(probe.getResponseCode()).thenReturn(200);
        when(probe.open(any())).thenThrow(new HttpDataSource.InvalidContentTypeException("audio/mpeg", spec));
        when(upstream.getResponseHeaders()).thenReturn(Map.of("Content-Range",
                List.of("bytes 100-199/" + (changed ? 900 : 1000))));
        source = new ValidatingStreamDataSource(factory, cache,
                new StreamCacheGeneration(RuntimeEnvironment.getApplication(), spec.uri.toString()));
        if (changed) {
            assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(spec));
        } else {
            assertEquals(100, source.open(spec));
            source.close();
            assertEquals(100, source.open(spec));
            verify(probe).open(any());
        }
        verify(probe, never()).read(any(), anyInt(), anyInt());
    }

    private void checkOverlaps(long changedPosition) throws Exception {
        File bytes = temporary.newFile();
        Files.write(bytes.toPath(), new byte[8192]);
        when(cache.getCachedSpans("key")).thenReturn(new TreeSet<>(Arrays.asList(
                new CacheSpan("key", 0, 8192, 0, bytes), new CacheSpan("key", 16384, 8192, 0, bytes))));
        when(cache.getContentMetadata("key")).thenReturn(new DefaultContentMetadata());
        DefaultHttpDataSource probe = mock(DefaultHttpDataSource.class);
        DefaultHttpDataSource.Factory factory = mock(DefaultHttpDataSource.Factory.class);
        when(factory.setContentTypePredicate(any())).thenReturn(factory);
        when(factory.createDataSource()).thenReturn(probe, upstream);
        List<DataSpec> requests = new ArrayList<>();
        AtomicLong position = new AtomicLong();
        when(probe.open(any())).thenAnswer(call -> {
            DataSpec request = call.getArgument(0);
            requests.add(request);
            position.set(request.position);
            return request.length;
        });
        when(probe.read(any(), anyInt(), anyInt())).thenAnswer(call -> {
            byte[] buffer = call.getArgument(0);
            int offset = call.getArgument(1);
            int length = call.getArgument(2);
            Arrays.fill(buffer, offset, offset + length, (byte) (position.get() == changedPosition ? 1 : 0));
            return length;
        });
        source = new ValidatingStreamDataSource(factory, cache,
                new StreamCacheGeneration(RuntimeEnvironment.getApplication(), spec.uri.toString()));
        DataSpec gap = spec.buildUpon().setPosition(8192).setLength(8192).build();
        when(upstream.open(gap)).thenReturn(8192L);
        if (changedPosition >= 0) {
            assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(gap));
            verify(upstream, never()).open(any());
        } else {
            assertEquals(8192, source.open(gap));
            assertEquals(List.of(4096L, 16384L), requests.stream().map(request -> request.position).toList());
        }
        assertTrue(requests.size() <= 2);
        for (DataSpec request : requests) {
            assertEquals(4096, request.length);
        }
    }

    @Test
    public void partialResponseUsesResourceTotalRatherThanBodyLength() throws Exception {
        when(upstream.getResponseHeaders()).thenReturn(Collections.singletonMap(
                "content-range", Collections.singletonList("bytes 100-199/1000")));
        assertEquals(100, source.open(spec));
        verify(upstream, never()).read(any(byte[].class), anyInt(), anyInt());
    }

    @Test
    public void changedTotalIsRejectedBeforeAnyBytesAreReadOrMetadataUpdated() throws Exception {
        when(upstream.getResponseHeaders()).thenReturn(Collections.singletonMap(
                "Content-Range", Collections.singletonList("bytes 100-199/900")));
        ValidatingStreamDataSource.ChangedException error = assertThrows(
                ValidatingStreamDataSource.ChangedException.class, () -> source.open(spec));
        assertFalse(error.needsResolution);
        verify(upstream, never()).read(any(byte[].class), anyInt(), anyInt());
        verify(cache, never()).applyContentMetadataMutations(anyString(), any());
    }

    @Test
    public void getRedirectCannotJoinThePreviouslyCachedUri() {
        when(upstream.getUri()).thenReturn(Uri.parse("https://example.invalid/B"));
        assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(spec));
    }

    @Test
    public void totalsHandleFullResponsesUnknownRangesAndInvalidValues() {
        assertEquals(1000, total(200, "Content-Length", "1000"));
        assertEquals(1000, total(206, "Content-Range", "bytes 10-19/1000"));
        assertEquals(C.LENGTH_UNSET, total(206, "Content-Length", "10"));
        assertEquals(C.LENGTH_UNSET, total(206, "Content-Range", "bytes 10-19/*"));
        assertEquals(C.LENGTH_UNSET, total(200, "Content-Length", "-1"));
        assertEquals(C.LENGTH_UNSET, total(200, "Content-Length", "invalid"));
    }

    @Test
    public void expiredCachedResponseNeedsSuccessfulReresolution() throws Exception {
        when(upstream.open(spec)).thenThrow(responseError(403, Collections.emptyMap()));
        assertTrue(assertThrows(ValidatingStreamDataSource.ChangedException.class,
                () -> source.open(spec)).needsResolution);
    }

    @Test
    public void ordinaryServerFailureDoesNotRequestInvalidation() throws Exception {
        HttpDataSource.InvalidResponseCodeException error = responseError(503, Collections.emptyMap());
        when(upstream.open(spec)).thenThrow(error);
        assertSame(error, assertThrows(HttpDataSource.InvalidResponseCodeException.class, () -> source.open(spec)));
    }

    @Test
    public void rangeRejectionWithChangedTotalIsAnIdentityFailure() throws Exception {
        when(upstream.open(spec)).thenThrow(responseError(416,
                Map.of("Content-Range", Collections.singletonList("bytes */900"))));
        assertFalse(assertThrows(ValidatingStreamDataSource.ChangedException.class,
                () -> source.open(spec)).needsResolution);
    }

    @Test
    public void successfulOpenAtChangedEofStillChecksTheTotal() throws Exception {
        when(upstream.getResponseCode()).thenReturn(416);
        when(upstream.getResponseHeaders()).thenReturn(
                Map.of("Content-Range", Collections.singletonList("bytes */100")));
        when(upstream.open(spec)).thenReturn(0L);
        assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(spec));
    }

    @Test
    public void compressedBodyLengthIsNotDecodedResourceLength() {
        assertEquals(C.LENGTH_UNSET, ValidatingStreamDataSource.totalLength(200,
                Map.of("Content-Length", Collections.singletonList("1000"),
                        "Content-Encoding", Collections.singletonList("gzip"))));
    }

    @Test
    public void concurrentOpenRechecksIdentityAfterHeadersArrive() throws Exception {
        AtomicReference<DefaultContentMetadata> current = new AtomicReference<>(new DefaultContentMetadata());
        when(cache.getContentMetadata(anyString())).thenAnswer(invocation -> current.get());
        when(cache.getCachedSpans(anyString())).thenReturn(new TreeSet<>());
        when(upstream.getUri()).thenReturn(Uri.parse("https://example.invalid/B"));
        when(upstream.open(spec)).thenAnswer(invocation -> {
            ContentMetadataMutations firstResponse = new ContentMetadataMutations();
            firstResponse.set("custom_stream_actual_uri", "https://example.invalid/A");
            current.set(current.get().copyWithMutationsApplied(firstResponse));
            return 100L;
        });
        assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(spec));
        verify(cache, never()).applyContentMetadataMutations(anyString(), any());
    }

    @Test
    public void ignoredRangeChangedLengthIsRejectedBeforePrefixSkip() throws Exception {
        checkIgnoredRange(90, true);
    }

    @Test
    public void ignoredRangeUnchangedLengthRetainsNormalSkipBehavior() throws Exception {
        checkIgnoredRange(1000, false);
    }

    private void checkIgnoredRange(int length, boolean changed) throws Exception {
        ServerSocket server = new ServerSocket(0);
        server.setSoTimeout(5000);
        Thread response = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                BufferedReader request = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                while (!request.readLine().isEmpty()) {
                }
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\n"
                        + "Content-Length: " + length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(new byte[length]);
            } catch (IOException disconnected) {
            }
        }, "full-response-test");
        response.start();
        AtomicInteger transferred = new AtomicInteger();
        DataSpec local = spec.buildUpon().setUri("http://127.0.0.1:" + server.getLocalPort() + "/A").build();
        ContentMetadataMutations metadata = new ContentMetadataMutations();
        ContentMetadataMutations.setContentLength(metadata, 1000);
        ContentMetadataMutations.setRedirectedUri(metadata, local.uri);
        when(cache.getContentMetadata(anyString())).thenReturn(
                new DefaultContentMetadata().copyWithMutationsApplied(metadata));
        source = new ValidatingStreamDataSource(new DefaultHttpDataSource.Factory(), cache,
                new StreamCacheGeneration(RuntimeEnvironment.getApplication(), local.uri.toString()));
        source.addTransferListener(new TransferListener() {
            @Override
            public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) { }
            @Override
            public void onTransferStart(DataSource source, DataSpec spec, boolean network) { }
            @Override
            public void onTransferEnd(DataSource source, DataSpec spec, boolean network) { }
            @Override
            public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int count) {
                transferred.addAndGet(count);
            }
        });
        try {
            if (changed) {
                assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(local));
                assertEquals(0, transferred.get());
                verify(cache, never()).applyContentMetadataMutations(anyString(), any());
            } else {
                assertEquals(100, source.open(local));
                assertEquals(100, transferred.get());
            }
        } finally {
            source.close();
            server.close();
            response.join(5000);
            assertFalse(response.isAlive());
        }
    }

    private HttpDataSource.InvalidResponseCodeException responseError(int code,
            Map<String, List<String>> headers) {
        return new HttpDataSource.InvalidResponseCodeException(code, "Synthetic response", null,
                headers, spec, new byte[0]);
    }

    private long total(int status, String header, String value) {
        return ValidatingStreamDataSource.totalLength(status, Map.of(header, Collections.singletonList(value)));
    }
}
