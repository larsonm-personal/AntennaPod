package de.danoeh.antennapod.playback.service.internal;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSink;
import androidx.media3.datasource.TeeDataSource;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.EOFException;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import androidx.media3.datasource.cache.DefaultContentMetadata;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;

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
        when(upstream.open(any())).thenReturn(100L);
        when(upstream.getUri()).thenReturn(spec.uri);
        when(upstream.getResponseCode()).thenReturn(206);
        when(upstream.getResponseHeaders()).thenReturn(Map.of("Content-Range", List.of("bytes 100-199/1000")));
        source = new ValidatingStreamDataSource(upstream, cache,
                new StreamCacheGeneration(RuntimeEnvironment.getApplication(), spec.uri.toString()));
    }

    @Test
    public void changedStrongEtagRejectsAnEqualLengthResponse() {
        ContentMetadataMutations metadata = new ContentMetadataMutations().set("custom_stream_etag", "\"A\"");
        when(cache.getContentMetadata(anyString())).thenReturn(
                new DefaultContentMetadata().copyWithMutationsApplied(metadata));
        when(upstream.getResponseHeaders()).thenReturn(Map.of("ETag", List.of("\"B\""),
                "Content-Range", List.of("bytes 100-199/1000")));
        assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(spec));
    }

    @Test
    public void matchingStrongEtagAllowsTheResponse() throws Exception {
        ContentMetadataMutations metadata = new ContentMetadataMutations().set("custom_stream_etag", "\"A\"");
        when(cache.getContentMetadata(anyString())).thenReturn(
                new DefaultContentMetadata().copyWithMutationsApplied(metadata));
        when(upstream.getResponseHeaders()).thenReturn(Map.of("ETag", List.of("\"A\""),
                "Content-Range", List.of("bytes 100-199/1000")));
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
    public void changedRightCacheSeamRejectsBeforeTheFinalPayloadReadReturns() throws Exception {
        checkOverlaps(16384);
    }

    @Test
    public void ignoredRangeDoesNotInvalidateStableCachedContent() throws Exception {
        checkIgnoredOverlap(false, false);
    }

    @Test
    public void ignoredRangeRejectsAnEqualLengthEditionChange() throws Exception {
        checkIgnoredOverlap(true, false);
    }

    @Test
    public void ignoredRangeChecksAnEarlierNonadjacentCacheSpan() throws Exception {
        checkIgnoredOverlap(true, true);
    }

    @Test
    public void largeIgnoredRangesRetainTheCachedPrefixAcrossOpens() throws Exception {
        checkLargeIgnoredRange(false, false);
    }

    @Test
    public void largeIgnoredRangeRejectsAChangedCachedPrefix() throws Exception {
        checkLargeIgnoredRange(true, false);
    }

    @Test
    public void largeIgnoredRangeRechecksThePrefixAfterRangeSupportIsDisabled() throws Exception {
        checkLargeIgnoredRange(false, true);
    }

    private void checkLargeIgnoredRange(boolean changedInitially, boolean changedLater) throws Exception {
        File bytes = temporary.newFile();
        Files.write(bytes.toPath(), new byte[8192]);
        when(cache.getCachedSpans("key")).thenReturn(new TreeSet<>(List.of(
                new CacheSpan("key", 0, 8192, 0, bytes), new CacheSpan("key", 8192, 8192, 0, bytes))));
        byte[] edition = new byte[32768];
        if (changedInitially) {
            edition[50] = 1;
        }
        try (RangeServer server = new RangeServer(edition, true, false)) {
            DataSpec local = spec.buildUpon().setUri(server.uri()).setPosition(24576).setLength(1024).build();
            AtomicInteger transferred = new AtomicInteger();
            StreamCacheGeneration generation = configureSource(local, edition.length, countingFactory(transferred));
            try {
                if (changedInitially) {
                    assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(local));
                    assertEquals(4096, transferred.get());
                    assertEquals(2, server.requests.get());
                    assertEquals(List.of("bytes=12288-16383", "bytes=0-4095"),
                            List.copyOf(server.requestedRanges));
                    assertNotEquals(generation.token, new StreamCacheGeneration(
                            RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                    return;
                }
                assertEquals(1024, source.open(local));
                readPayload(new byte[1024]);
                assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[1], 0, 1));
                source.close();
                assertEquals(4096 + 24576 + 1024, transferred.get());
                assertEquals(3, server.requests.get());
                assertEquals(List.of("bytes=12288-16383", "bytes=0-4095", "bytes=24576-25599"),
                        List.copyOf(server.requestedRanges));
                assertEquals(generation.token, new StreamCacheGeneration(
                        RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                transferred.set(0);
                if (changedLater) {
                    synchronized (edition) {
                        edition[50] = 1;
                    }
                    assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(local));
                    assertEquals(4096, transferred.get());
                    assertEquals(4, server.requests.get());
                    assertEquals("bytes=0-4095", server.requestedRanges.get(3));
                    assertNotEquals(generation.token, new StreamCacheGeneration(
                            RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                } else {
                    assertEquals(1024, source.open(local));
                    readPayload(new byte[1024]);
                    assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[1], 0, 1));
                    assertEquals(4096 + 24576 + 1024, transferred.get());
                    assertEquals(5, server.requests.get());
                    assertEquals(List.of("bytes=0-4095", "bytes=24576-25599"),
                            List.copyOf(server.requestedRanges).subList(3, 5));
                    assertEquals(generation.token, new StreamCacheGeneration(
                            RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                }
            } finally {
                source.close();
            }
        }
    }

    private void checkIgnoredOverlap(boolean changed, boolean distant) throws Exception {
        File bytes = temporary.newFile();
        Files.write(bytes.toPath(), new byte[100]);
        when(cache.getCachedSpans("key")).thenReturn(new TreeSet<>(List.of(
                new CacheSpan("key", 0, 100, 0, bytes))));
        byte[] edition = new byte[1000];
        edition[50] = changed ? (byte) 1 : 0;
        try (RangeServer server = new RangeServer(edition, true, false)) {
            DataSpec local = spec.buildUpon().setUri(server.uri()).setPosition(distant ? 800 : 100).build();
            StreamCacheGeneration generation = configureSource(local, 1000);
            AtomicInteger transferred = countTransfers();
            try {
                if (changed) {
                    assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(local));
                    assertNotEquals(generation.token, new StreamCacheGeneration(
                            RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                } else {
                    assertEquals(100, source.open(local));
                    byte[] payload = new byte[100];
                    readPayload(payload);
                    assertArrayEquals(new byte[100], payload);
                    assertEquals(C.RESULT_END_OF_INPUT, source.read(payload, 0, payload.length));
                    assertEquals(generation.token, new StreamCacheGeneration(
                            RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                }
                assertEquals(1, server.requests.get());
                assertTrue(transferred.get() <= local.position + local.length);
            } finally {
                source.close();
            }
        }
    }

    private void checkOverlaps(long changedPosition) throws Exception {
        File bytes = temporary.newFile();
        Files.write(bytes.toPath(), new byte[8192]);
        when(cache.getCachedSpans("key")).thenReturn(new TreeSet<>(Arrays.asList(
                new CacheSpan("key", 0, 8192, 0, bytes), new CacheSpan("key", 16384, 8192, 0, bytes))));
        byte[] edition = new byte[24576];
        if (changedPosition >= 0) {
            edition[(int) changedPosition] = 1;
        }
        try (RangeServer server = new RangeServer(edition, false, true)) {
            DataSpec gap = spec.buildUpon().setUri(server.uri()).setPosition(8192).setLength(8192).build();
            StreamCacheGeneration generation = configureSource(gap, edition.length);
            AtomicInteger transferred = countTransfers();
            try {
                if (changedPosition == 4096) {
                    assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(gap));
                } else {
                    byte[] payload = new byte[8192];
                    if (changedPosition == 16384) {
                        DataSink sink = mock(DataSink.class);
                        AtomicInteger written = new AtomicInteger();
                        doAnswer(call -> {
                            written.addAndGet(call.getArgument(2));
                            return null;
                        }).when(sink).write(any(), anyInt(), anyInt());
                        TeeDataSource tee = new TeeDataSource(source, sink);
                        assertEquals(8192, tee.open(gap));
                        AtomicInteger delivered = new AtomicInteger();
                        try {
                            int first = tee.read(payload, 0, 128);
                            assertTrue(first > 0);
                            delivered.set(first);
                            assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> {
                                while (delivered.get() < payload.length) {
                                    int count = tee.read(payload, delivered.get(), payload.length - delivered.get());
                                    assertTrue(count > 0);
                                    delivered.addAndGet(count);
                                }
                            });
                        } finally {
                            tee.close();
                        }
                        assertEquals(delivered.get(), written.get());
                        assertTrue(delivered.get() < payload.length);
                    } else {
                        assertEquals(8192, source.open(gap));
                        readPayload(payload);
                        assertArrayEquals(new byte[8192], payload);
                        assertEquals(C.RESULT_END_OF_INPUT, source.read(payload, 0, payload.length));
                        assertEquals(16384, transferred.get());
                    }
                }
                assertEquals(1, server.requests.get());
                assertTrue(transferred.get() <= 16384);
                String token = new StreamCacheGeneration(RuntimeEnvironment.getApplication(), gap.uri.toString()).token;
                if (changedPosition >= 0) {
                    assertNotEquals(generation.token, token);
                } else {
                    assertEquals(generation.token, token);
                }
            } finally {
                source.close();
            }
        }
    }

    @Test
    public void closingBeforeTheRightSeamLeavesTheNextReadGuarded() throws Exception {
        File bytes = temporary.newFile();
        Files.write(bytes.toPath(), new byte[100]);
        when(cache.getCachedSpans("key")).thenReturn(new TreeSet<>(List.of(
                new CacheSpan("key", 200, 100, 0, bytes))));
        byte[] edition = new byte[1000];
        edition[200] = 1;
        try (RangeServer server = new RangeServer(edition, false, false)) {
            DataSpec local = spec.withUri(server.uri());
            StreamCacheGeneration generation = configureSource(local, edition.length);
            try {
                assertEquals(100, source.open(local));
                assertEquals(1, source.read(new byte[1], 0, 1));
            } finally {
                source.close();
            }
            assertEquals(generation.token, new StreamCacheGeneration(
                    RuntimeEnvironment.getApplication(), local.uri.toString()).token);
            when(cache.getCachedSpans("key")).thenReturn(new TreeSet<>(List.of(
                    new CacheSpan("key", 100, 1, 0, bytes), new CacheSpan("key", 200, 100, 0, bytes))));
            DataSpec remaining = local.buildUpon().setPosition(101).setLength(99).build();
            try {
                assertEquals(99, source.open(remaining));
                assertThrows(ValidatingStreamDataSource.ChangedException.class,
                        () -> readPayload(new byte[99]));
                assertNotEquals(generation.token, new StreamCacheGeneration(
                        RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                assertEquals(2, server.requests.get());
            } finally {
                source.close();
            }
        }
    }

    @Test
    public void cancellationDuringPrefixSkipDoesNotInvalidateTheGeneration() throws Exception {
        try (RangeServer server = new RangeServer(new byte[10000], true, false)) {
            DataSpec local = spec.buildUpon().setUri(server.uri()).setPosition(8192).build();
            StreamCacheGeneration generation = configureSource(local, 10000);
            source.addTransferListener(new TransferListener() {
                @Override
                public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) { }
                @Override
                public void onTransferStart(DataSource source, DataSpec spec, boolean network) { }
                @Override
                public void onTransferEnd(DataSource source, DataSpec spec, boolean network) { }
                @Override
                public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int count) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                IOException error = assertThrows(IOException.class, () -> source.open(local));
                assertTrue(hasCause(error, InterruptedIOException.class));
                assertFalse(error instanceof ValidatingStreamDataSource.ChangedException);
                assertEquals(generation.token, new StreamCacheGeneration(
                        RuntimeEnvironment.getApplication(), local.uri.toString()).token);
            } finally {
                Thread.interrupted();
                source.close();
            }
        }
    }

    @Test
    public void shortPartialResponseDeliversItsPrefixThenFailsWithoutRetiringTheGeneration() throws Exception {
        File bytes = temporary.newFile();
        Files.write(bytes.toPath(), new byte[100]);
        when(cache.getCachedSpans("key")).thenReturn(new TreeSet<>(List.of(
                new CacheSpan("key", 200, 100, 0, bytes))));
        byte[] edition = new byte[1000];
        Arrays.fill(edition, 100, 150, (byte) 7);
        try (RangeServer server = new RangeServer(edition, false, false, false, 50)) {
            DataSpec local = spec.withUri(server.uri());
            StreamCacheGeneration generation = configureSource(local, edition.length);
            try {
                assertEquals(100, source.open(local));
                byte[] prefix = new byte[50];
                readPayload(prefix);
                byte[] expected = new byte[50];
                Arrays.fill(expected, (byte) 7);
                assertArrayEquals(expected, prefix);
                assertThrows(EOFException.class, () -> source.read(new byte[1], 0, 1));
                assertEquals(generation.token, new StreamCacheGeneration(
                        RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                assertEquals(1, server.requests.get());
            } finally {
                source.close();
            }
        }
    }

    @Test
    public void partialContentLengthDisagreementIsRejectedBeforeReadingTheBody() throws Exception {
        checkPartialFraming(false);
    }

    @Test
    public void extraPartialBytesWithoutContentLengthCannotCrossTheDeclaredRange() throws Exception {
        checkPartialFraming(true);
    }

    private void checkPartialFraming(boolean omitLength) throws Exception {
        byte[] edition = new byte[1000];
        Arrays.fill(edition, 100, 150, (byte) 7);
        Arrays.fill(edition, 150, 200, (byte) 9);
        try (RangeServer server = new RangeServer(edition, false, false, omitLength, 100, 50)) {
            DataSpec local = spec.withUri(server.uri());
            AtomicInteger transferred = new AtomicInteger();
            StreamCacheGeneration generation = configureSource(local, edition.length, countingFactory(transferred));
            try {
                if (omitLength) {
                    assertEquals(100, source.open(local));
                    byte[] prefix = new byte[50];
                    readPayload(prefix);
                    byte[] expected = new byte[50];
                    Arrays.fill(expected, (byte) 7);
                    assertArrayEquals(expected, prefix);
                    assertThrows(EOFException.class, () -> source.read(new byte[1], 0, 1));
                    assertEquals(50, transferred.get());
                } else {
                    IOException error = assertThrows(IOException.class, () -> source.open(local));
                    assertFalse(error instanceof ValidatingStreamDataSource.ChangedException);
                    verify(cache, never()).applyContentMetadataMutations(anyString(), any());
                    assertEquals(0, transferred.get());
                }
                assertEquals(generation.token, new StreamCacheGeneration(
                        RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                assertEquals(1, server.requests.get());
            } finally {
                source.close();
            }
        }
    }

    @Test
    public void boundedPartialResponseAtTrueEofEndsWithoutCacheWitnesses() throws Exception {
        try (RangeServer server = new RangeServer(new byte[150], false, false)) {
            DataSpec local = spec.withUri(server.uri());
            StreamCacheGeneration generation = configureSource(local, 150);
            try {
                assertEquals(100, source.open(local));
                readPayload(new byte[50]);
                assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[1], 0, 1));
                assertEquals(generation.token, new StreamCacheGeneration(
                        RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                assertEquals(1, server.requests.get());
            } finally {
                source.close();
            }
        }
    }

    @Test
    public void leftOverlapAllowsTheRemainingPayloadAtKnownEof() throws Exception {
        checkKnownEofAfterLeftOverlap(150);
    }

    @Test
    public void leftOverlapAtKnownEofReturnsNoPayload() throws Exception {
        checkKnownEofAfterLeftOverlap(100);
    }

    private void checkKnownEofAfterLeftOverlap(int total) throws Exception {
        File bytes = temporary.newFile();
        Files.write(bytes.toPath(), new byte[100]);
        when(cache.getCachedSpans("key")).thenReturn(new TreeSet<>(List.of(
                new CacheSpan("key", 0, 100, 0, bytes))));
        try (RangeServer server = new RangeServer(new byte[total], false, false)) {
            DataSpec local = spec.buildUpon().setUri(server.uri()).setLength(200).build();
            StreamCacheGeneration generation = configureSource(local, C.LENGTH_UNSET);
            try {
                assertEquals(200, source.open(local));
                byte[] payload = new byte[total - 100];
                readPayload(payload);
                assertArrayEquals(new byte[payload.length], payload);
                assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[1], 0, 1));
                assertEquals(generation.token, new StreamCacheGeneration(
                        RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                assertEquals(1, server.requests.get());
            } finally {
                source.close();
            }
        }
    }

    @Test
    public void cachedRightSpanBeyondANewlyKnownTotalRetiresTheGenerationBeforeStoringMetadata() throws Exception {
        File bytes = temporary.newFile();
        Files.write(bytes.toPath(), new byte[100]);
        when(cache.getCachedSpans("key")).thenReturn(new TreeSet<>(List.of(
                new CacheSpan("key", 0, 100, 0, bytes), new CacheSpan("key", 300, 100, 0, bytes))));
        try (RangeServer server = new RangeServer(new byte[150], false, false)) {
            DataSpec local = spec.buildUpon().setUri(server.uri()).setLength(200).build();
            StreamCacheGeneration generation = configureSource(local, C.LENGTH_UNSET);
            try {
                assertThrows(ValidatingStreamDataSource.ChangedException.class, () -> source.open(local));
                assertNotEquals(generation.token, new StreamCacheGeneration(
                        RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                verify(cache, never()).applyContentMetadataMutations(anyString(), any());
                assertEquals(1, server.requests.get());
            } finally {
                source.close();
            }
        }
    }

    @Test
    public void shortUnboundedPartialResponseDoesNotTruncateTheCachedResourceLength() throws Exception {
        checkKnownTotalUnboundedPartialResponse(true);
    }

    @Test
    public void completeUnboundedPartialResponseKeepsItsKnownResourceLength() throws Exception {
        checkKnownTotalUnboundedPartialResponse(false);
    }

    private void checkKnownTotalUnboundedPartialResponse(boolean shortened) throws Exception {
        try (RangeServer server = new RangeServer(new byte[1000], false, false, false, shortened ? 50 : 0)) {
            DataSpec local = spec.buildUpon().setUri(server.uri()).setLength(C.LENGTH_UNSET).build();
            StreamCacheGeneration generation = configureSource(local, C.LENGTH_UNSET);
            when(cache.startReadWriteNonBlocking(anyString(), anyLong(), anyLong())).thenReturn(
                    new CacheSpan("key", 100, C.LENGTH_UNSET, 0, null));
            CacheDataSource cachedSource = new CacheDataSource.Factory().setCache(cache)
                    .setUpstreamDataSourceFactory(() -> source)
                    .setCacheWriteDataSinkFactory(() -> mock(DataSink.class)).createDataSource();
            try {
                assertEquals(900, cachedSource.open(local));
                byte[] payload = new byte[shortened ? 50 : 900];
                int read = 0;
                while (read < payload.length) {
                    int count = cachedSource.read(payload, read, payload.length - read);
                    assertTrue(count > 0);
                    read += count;
                }
                assertArrayEquals(new byte[payload.length], payload);
                if (shortened) {
                    assertThrows(EOFException.class, () -> cachedSource.read(new byte[1], 0, 1));
                } else {
                    assertEquals(C.RESULT_END_OF_INPUT, cachedSource.read(new byte[1], 0, 1));
                }
                ArgumentCaptor<ContentMetadataMutations> mutations = ArgumentCaptor.forClass(
                        ContentMetadataMutations.class);
                verify(cache, atLeastOnce()).applyContentMetadataMutations(eq("key"), mutations.capture());
                for (ContentMetadataMutations mutation : mutations.getAllValues()) {
                    assertEquals(1000, ContentMetadata.getContentLength(
                            new DefaultContentMetadata().copyWithMutationsApplied(mutation)));
                }
                assertEquals(generation.token, new StreamCacheGeneration(
                        RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                assertEquals(1, server.requests.get());
            } finally {
                cachedSource.close();
            }
        }
    }

    @Test
    public void unknownLengthFullResponseEndsAtItsActualEof() throws Exception {
        try (RangeServer server = new RangeServer(new byte[1000], true, false, true)) {
            DataSpec local = spec.buildUpon().setUri(server.uri()).setLength(C.LENGTH_UNSET).build();
            configureSource(local, C.LENGTH_UNSET);
            try {
                assertEquals(C.LENGTH_UNSET, source.open(local));
                readPayload(new byte[900]);
                assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[1], 0, 1));
                assertEquals(1, server.requests.get());
            } finally {
                source.close();
            }
        }
    }

    @Test
    public void rangeAtUnchangedEofRemainsAnEmptySuccessfulRead() throws Exception {
        try (RangeServer server = new RangeServer(new byte[1000], false, false)) {
            DataSpec local = spec.buildUpon().setUri(server.uri()).setPosition(1000)
                    .setLength(C.LENGTH_UNSET).build();
            StreamCacheGeneration generation = configureSource(local, 1000);
            try {
                assertEquals(0, source.open(local));
                assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[1], 0, 1));
                assertEquals(generation.token, new StreamCacheGeneration(
                        RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                assertEquals(1, server.requests.get());
            } finally {
                source.close();
            }
        }
    }

    @Test
    public void empty416DoesNotTurnANonzeroPositionIntoSuccessfulEof() throws Exception {
        checkEmpty416(100);
    }

    @Test
    public void empty416AtZeroRemainsAnEmptySuccessfulRead() throws Exception {
        checkEmpty416(0);
    }

    private void checkEmpty416(long position) throws Exception {
        try (RangeServer server = new RangeServer(new byte[0], false, false)) {
            DataSpec local = spec.buildUpon().setUri(server.uri()).setPosition(position)
                    .setLength(C.LENGTH_UNSET).build();
            StreamCacheGeneration generation = configureSource(local, C.LENGTH_UNSET);
            try {
                if (position == 0) {
                    assertEquals(0, source.open(local));
                    assertEquals(C.RESULT_END_OF_INPUT, source.read(new byte[1], 0, 1));
                } else {
                    IOException error = assertThrows(IOException.class, () -> source.open(local));
                    assertTrue(hasCause(error, DataSourceException.class));
                    assertFalse(error instanceof ValidatingStreamDataSource.ChangedException);
                }
                assertEquals(generation.token, new StreamCacheGeneration(
                        RuntimeEnvironment.getApplication(), local.uri.toString()).token);
                assertEquals(1, server.requests.get());
            } finally {
                source.close();
            }
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
        when(upstream.open(any())).thenThrow(responseError(403, Collections.emptyMap()));
        assertTrue(assertThrows(ValidatingStreamDataSource.ChangedException.class,
                () -> source.open(spec)).needsResolution);
    }

    @Test
    public void ordinaryServerFailureDoesNotRequestInvalidation() throws Exception {
        HttpDataSource.InvalidResponseCodeException error = responseError(503, Collections.emptyMap());
        when(upstream.open(any())).thenThrow(error);
        assertSame(error, assertThrows(HttpDataSource.InvalidResponseCodeException.class, () -> source.open(spec)));
    }

    @Test
    public void rangeRejectionWithChangedTotalIsAnIdentityFailure() throws Exception {
        when(upstream.open(any())).thenThrow(responseError(416,
                Map.of("Content-Range", Collections.singletonList("bytes */900"))));
        assertFalse(assertThrows(ValidatingStreamDataSource.ChangedException.class,
                () -> source.open(spec)).needsResolution);
    }

    @Test
    public void rangeRejectionBelowCachedBytesRetiresAGenerationWithUnknownPriorLength() throws Exception {
        File bytes = temporary.newFile();
        Files.write(bytes.toPath(), new byte[100]);
        when(cache.getCachedSpans("key")).thenReturn(new TreeSet<>(List.of(
                new CacheSpan("key", 300, 100, 0, bytes))));
        when(cache.getContentMetadata("key")).thenReturn(new DefaultContentMetadata());
        when(upstream.open(any())).thenThrow(responseError(416,
                Map.of("Content-Range", List.of("bytes */150"))));
        StreamCacheGeneration generation = new StreamCacheGeneration(
                RuntimeEnvironment.getApplication(), spec.uri.toString());
        source = new ValidatingStreamDataSource(upstream, cache, generation);
        assertFalse(assertThrows(ValidatingStreamDataSource.ChangedException.class,
                () -> source.open(spec)).needsResolution);
        assertNotEquals(generation.token, new StreamCacheGeneration(
                RuntimeEnvironment.getApplication(), spec.uri.toString()).token);
        verify(cache, never()).applyContentMetadataMutations(anyString(), any());
    }

    @Test
    public void successfulOpenAtChangedEofStillChecksTheTotal() throws Exception {
        when(upstream.getResponseCode()).thenReturn(416);
        when(upstream.getResponseHeaders()).thenReturn(
                Map.of("Content-Range", Collections.singletonList("bytes */100")));
        when(upstream.open(any())).thenReturn(0L);
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
        when(upstream.open(any())).thenAnswer(invocation -> {
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
    public void partialResponseMustStartAtTheRequestedOffset() throws Exception {
        checkPartialResponse(0);
    }

    @Test
    public void partialResponseAtTheRequestedOffsetRemainsReadable() throws Exception {
        checkPartialResponse(100);
    }

    private void checkPartialResponse(int start) throws Exception {
        ServerSocket server = new ServerSocket(0);
        server.setSoTimeout(5000);
        Thread response = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                BufferedReader request = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                while (!request.readLine().isEmpty()) {
                }
                socket.getOutputStream().write(("HTTP/1.1 206 Partial Content\r\nContent-Type: audio/mpeg\r\n"
                        + "Content-Range: bytes " + start + "-" + (start + 99) + "/1000\r\n"
                        + "Content-Length: 100\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(new byte[100]);
            } catch (IOException disconnected) {
            }
        }, "partial-response-test");
        response.start();
        DataSpec local = spec.buildUpon().setUri("http://127.0.0.1:" + server.getLocalPort() + "/A").build();
        ContentMetadataMutations metadata = new ContentMetadataMutations();
        ContentMetadataMutations.setContentLength(metadata, 1000);
        ContentMetadataMutations.setRedirectedUri(metadata, local.uri);
        when(cache.getContentMetadata(anyString())).thenReturn(
                new DefaultContentMetadata().copyWithMutationsApplied(metadata));
        source = new ValidatingStreamDataSource(new DefaultHttpDataSource.Factory(), cache,
                new StreamCacheGeneration(RuntimeEnvironment.getApplication(), local.uri.toString()));
        try {
            if (start == local.position) {
                assertEquals(100, source.open(local));
                assertEquals(100, source.read(new byte[100], 0, 100));
            } else {
                IOException error = assertThrows(IOException.class, () -> source.open(local));
                assertFalse(error instanceof ValidatingStreamDataSource.ChangedException);
                verify(cache, never()).applyContentMetadataMutations(anyString(), any());
            }
        } finally {
            source.close();
            server.close();
            response.join(5000);
            assertFalse(response.isAlive());
        }
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

    private StreamCacheGeneration configureSource(DataSpec local, long length) {
        return configureSource(local, length, new DefaultHttpDataSource.Factory());
    }

    private StreamCacheGeneration configureSource(DataSpec local, long length,
                                                  DefaultHttpDataSource.Factory factory) {
        ContentMetadataMutations metadata = new ContentMetadataMutations();
        if (length != C.LENGTH_UNSET) {
            ContentMetadataMutations.setContentLength(metadata, length);
        }
        ContentMetadataMutations.setRedirectedUri(metadata, local.uri);
        when(cache.getContentMetadata(anyString())).thenReturn(
                new DefaultContentMetadata().copyWithMutationsApplied(metadata));
        StreamCacheGeneration generation = new StreamCacheGeneration(
                RuntimeEnvironment.getApplication(), local.uri.toString());
        source = new ValidatingStreamDataSource(factory, cache, generation);
        return generation;
    }

    private static DefaultHttpDataSource.Factory countingFactory(AtomicInteger transferred) {
        return new DefaultHttpDataSource.Factory().setTransferListener(new TransferListener() {
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
    }

    private AtomicInteger countTransfers() {
        AtomicInteger transferred = new AtomicInteger();
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
        return transferred;
    }

    private void readPayload(byte[] payload) throws IOException {
        int read = 0;
        while (read < payload.length) {
            int count = source.read(payload, read, payload.length - read);
            assertTrue(count > 0);
            read += count;
        }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static final class RangeServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread response;
        private final AtomicInteger requests = new AtomicInteger();
        private final List<String> requestedRanges = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<IOException> failure = new AtomicReference<>();

        RangeServer(byte[] edition, boolean ignoreRange, boolean oldProbes) throws IOException {
            this(edition, ignoreRange, oldProbes, false);
        }

        RangeServer(byte[] edition, boolean ignoreRange, boolean oldProbes, boolean unknownLength) throws IOException {
            this(edition, ignoreRange, oldProbes, unknownLength, 0);
        }

        RangeServer(byte[] edition, boolean ignoreRange, boolean oldProbes, boolean unknownLength,
                    int maximumBodyBytes) throws IOException {
            this(edition, ignoreRange, oldProbes, unknownLength, maximumBodyBytes, 0);
        }

        RangeServer(byte[] edition, boolean ignoreRange, boolean oldProbes, boolean unknownLength,
                    int maximumBodyBytes, int declaredRangeBytes) throws IOException {
            server = new ServerSocket(0);
            server.setSoTimeout(5000);
            response = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        BufferedReader request = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII));
                        String range = null;
                        String line;
                        while ((line = request.readLine()) != null && !line.isEmpty()) {
                            if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                                range = line.substring(6).trim();
                            }
                        }
                        requestedRanges.add(range == null ? "" : range);
                        requests.incrementAndGet();
                        int start = 0;
                        int end = edition.length - 1;
                        if (!ignoreRange && range != null) {
                            String[] bounds = range.substring("bytes=".length()).split("-", -1);
                            start = Integer.parseInt(bounds[0]);
                            if (!bounds[1].isEmpty()) {
                                end = Math.min(end, Integer.parseInt(bounds[1]));
                            }
                        }
                        if (maximumBodyBytes > 0) {
                            end = (int) Math.min(end, (long) start + maximumBodyBytes - 1);
                        }
                        boolean eof = start >= edition.length;
                        byte[] body;
                        synchronized (edition) {
                            body = eof ? new byte[0] : Arrays.copyOfRange(edition, start, end + 1);
                        }
                        if (oldProbes && body.length <= 4096) {
                            Arrays.fill(body, (byte) 0);
                        }
                        String status = eof ? "416 Range Not Satisfiable"
                                : ignoreRange ? "200 OK" : "206 Partial Content";
                        int declaredEnd = declaredRangeBytes > 0 ? start + declaredRangeBytes - 1 : end;
                        String contentRange = eof ? "Content-Range: bytes */" + edition.length + "\r\n"
                                : ignoreRange ? "" : "Content-Range: bytes " + start + "-" + declaredEnd
                                + "/" + edition.length + "\r\n";
                        String contentLength = unknownLength ? "" : "Content-Length: " + body.length + "\r\n";
                        try {
                            socket.getOutputStream().write(("HTTP/1.1 " + status + "\r\nContent-Type: audio/mpeg\r\n"
                                    + contentRange + contentLength + "Connection: close\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().write(body);
                        } catch (IOException disconnected) {
                        }
                    } catch (IOException error) {
                        if (!server.isClosed()) {
                            failure.set(error);
                        }
                        return;
                    }
                }
            }, "overlap-response-test");
            response.start();
        }

        Uri uri() {
            return Uri.parse("http://127.0.0.1:" + server.getLocalPort() + "/A");
        }

        @Override
        public void close() throws IOException {
            server.close();
            try {
                response.join(5000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while closing synthetic HTTP server", error);
            }
            assertFalse(response.isAlive());
            assertNull(failure.get());
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
