package de.danoeh.antennapod.net.common;

import de.danoeh.antennapod.model.download.DownloadRequest;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.util.ReflectionHelpers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class RedirectCheckerTest {
    private static final String AUTHORIZATION = "Basic Zml4dHVyZTpwYXNzd29yZA==";
    private OkHttpClient previousClient;
    private OkHttpClient client;
    private final AtomicInteger applicationReads = new AtomicInteger();
    private final AtomicLong applicationBytes = new AtomicLong();
    private final AtomicInteger bodyCloses = new AtomicInteger();
    private final AtomicBoolean canceledBeforeClose = new AtomicBoolean();

    @Before
    public void installClient() {
        previousClient = ReflectionHelpers.getStaticField(AntennapodHttpClient.class, "httpClient");
        client = new OkHttpClient.Builder().addInterceptor(new BasicAuthorizationInterceptor())
                .addNetworkInterceptor(this::observeFinalBody)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build();
        ReflectionHelpers.setStaticField(AntennapodHttpClient.class, "httpClient", client);
    }

    @After
    public void restoreClient() {
        ReflectionHelpers.setStaticField(AntennapodHttpClient.class, "httpClient", previousClient);
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }

    private Response observeFinalBody(Interceptor.Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        if (!"GET".equals(response.request().method()) || !"/fresh".equals(response.request().url().encodedPath())) {
            return response;
        }
        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException("Missing fixture response body");
        }
        return response.newBuilder().body(new ResponseBody() {
            private final BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    applicationReads.incrementAndGet();
                    long count = super.read(sink, byteCount);
                    if (count > 0) {
                        applicationBytes.addAndGet(count);
                    }
                    return count;
                }

                @Override
                public void close() throws IOException {
                    bodyCloses.incrementAndGet();
                    canceledBeforeClose.set(chain.call().isCanceled());
                    super.close();
                }
            });

            @Override
            public MediaType contentType() {
                return body.contentType();
            }

            @Override
            public long contentLength() {
                return body.contentLength();
            }

            @Override
            public BufferedSource source() {
                return source;
            }
        }).build();
    }

    @Test
    public void strict405RestartsGetAtTheOriginalUrl() throws Exception {
        checkFallback(405, true);
    }

    @Test
    public void strict501RestartsGetAtTheOriginalUrl() throws Exception {
        checkFallback(501, true);
    }

    @Test
    public void successfulStrictHeadDoesNotIssueGet() throws Exception {
        checkHead(200, true);
    }

    @Test
    public void forbiddenStrictHeadDoesNotIssueGet() throws Exception {
        checkHead(403, true);
    }

    @Test
    public void forbiddenStrictFallbackGetStillFails() throws Exception {
        checkFailedGet(true);
    }

    @Test
    public void ignoredRangeIsCanceledBeforeClosingWithoutApplicationBodyReads() throws Exception {
        try (Fixture fixture = new Fixture(405, 200, true, false)) {
            assertEquals(fixture.freshUrl(), resolve(fixture, true));
            assertFallbackRequests(fixture, AUTHORIZATION);
            assertEquals(0, applicationReads.get());
            assertEquals(0, applicationBytes.get());
            assertEquals(1, bodyCloses.get());
            assertTrue(canceledBeforeClose.get());
        }
    }

    @Test
    public void successfulCrossHostGetRedirectStripsAuthorization() throws Exception {
        try (Fixture fixture = new Fixture(405, 206, false, true)) {
            assertEquals(fixture.freshUrl(), resolve(fixture, true));
            assertFallbackRequests(fixture, AUTHORIZATION);
        }
    }

    @Test
    public void crossHostUnauthorizedHeadNeverReceivesOriginalAuthorization() throws Exception {
        try (Fixture fixture = new Fixture(200, 206, false, false)) {
            fixture.headRedirect = fixture.url("/challenge").replace("127.0.0.1", "localhost");
            assertThrows(IOException.class, () -> resolve(fixture, true));
            assertEquals(AUTHORIZATION, fixture.requests.get(0).headers.get("authorization"));
            assertNoChallengeAuthorization(fixture);
        }
    }

    @Test
    public void crossPortUnauthorizedHeadNeverReceivesOriginalAuthorization() throws Exception {
        try (Fixture origin = new Fixture(200, 206, false, false);
                Fixture target = new Fixture(200, 206, false, false)) {
            origin.headRedirect = target.url("/challenge");
            assertThrows(IOException.class, () -> resolve(origin, true));
            assertEquals(AUTHORIZATION, origin.requests.get(0).headers.get("authorization"));
            assertNoChallengeAuthorization(target);
        }
    }

    @Test
    public void crossOriginUnauthorizedGetFallbackNeverReceivesOriginalAuthorization() throws Exception {
        try (Fixture origin = new Fixture(405, 206, false, false);
                Fixture target = new Fixture(200, 206, false, false)) {
            origin.getRedirect = target.url("/challenge");
            assertThrows(IOException.class, () -> resolve(origin, true));
            assertEquals(List.of("HEAD /origin", "HEAD /head-only", "GET /origin"), origin.paths());
            assertEquals("bytes=0-0", target.requests.get(0).headers.get("range"));
            assertNoChallengeAuthorization(target);
        }
    }

    @Test
    public void sameOriginUnauthorizedRedirectRetainsAuthorization() throws Exception {
        try (Fixture fixture = new Fixture(200, 206, false, false)) {
            fixture.headRedirect = fixture.url("/challenge");
            fixture.acceptedAuthorization = AUTHORIZATION;
            fixture.challengesBeforeSuccess = 1;
            assertEquals(fixture.url("/challenge"), resolve(fixture, true));
            assertEquals(List.of(AUTHORIZATION, AUTHORIZATION), fixture.challengeHeaders());
        }
    }

    @Test
    public void returnToOriginalOriginCanRecoverAuthorizationAfterForeignHop() throws Exception {
        try (Fixture origin = new Fixture(200, 206, false, false);
                Fixture foreign = new Fixture(200, 206, false, false)) {
            origin.headRedirect = foreign.url("/origin");
            foreign.headRedirect = origin.url("/challenge");
            origin.acceptedAuthorization = AUTHORIZATION;
            assertEquals(origin.url("/challenge"), resolve(origin, true));
            assertNull(foreign.requests.get(0).headers.get("authorization"));
            assertEquals(Arrays.asList(null, AUTHORIZATION), origin.challengeHeaders());
        }
    }

    @Test
    public void crossOriginChallengeNeverReceivesDownloadTagCredentials() throws Exception {
        checkDownloadTag(false, true);
    }

    @Test
    public void crossOriginChallengeNeverReceivesDownloadSourceUserInfo() throws Exception {
        checkDownloadTag(true, true);
    }

    @Test
    public void sameOriginDownloadTagTriesBothCredentialEncodings() throws Exception {
        checkDownloadTag(false, false);
    }

    @Test
    public void sameOriginDownloadSourceUserInfoTriesBothCredentialEncodings() throws Exception {
        checkDownloadTag(true, false);
    }

    private void checkDownloadTag(boolean sourceUserInfo, boolean crossOrigin) throws Exception {
        try (Fixture origin = new Fixture(200, 206, false, false);
                Fixture target = new Fixture(200, 206, false, false)) {
            String url = crossOrigin ? origin.url("/origin") : target.url("/challenge");
            origin.getRedirect = target.url("/challenge");
            String source = sourceUserInfo ? url.replace("http://", "http://f%C3%AFxture:p%C3%A4ssword@") : url;
            DownloadRequest download = new DownloadRequest("unused", source, "Synthetic download", 0, 0,
                    sourceUserInfo ? "ignored" : "fïxture", sourceUserInfo ? "ignored" : "pässword", null, false);
            String iso = HttpCredentialEncoder.encode("fïxture", "pässword", "ISO-8859-1");
            String utf = HttpCredentialEncoder.encode("fïxture", "pässword", "UTF-8");
            if (!crossOrigin) {
                target.acceptedAuthorization = utf;
            }
            try (Response response = client.newCall(new Request.Builder().url(url).tag(download).build()).execute()) {
                assertEquals(crossOrigin ? 401 : 200, response.code());
            }
            if (crossOrigin) {
                assertNoChallengeAuthorization(target);
            } else {
                assertFalse(iso.equals(utf));
                assertEquals(Arrays.asList(null, iso, utf), target.challengeHeaders());
            }
        }
    }

    private static void assertNoChallengeAuthorization(Fixture fixture) {
        List<String> headers = fixture.challengeHeaders();
        assertFalse(headers.isEmpty());
        for (String header : headers) {
            assertNull("Credentials crossed the original HTTP origin", header);
        }
    }

    @Test
    public void legacy405RestartsGetAtTheOriginalUrl() throws Exception {
        checkFallback(405, false);
    }

    @Test
    public void legacy501RestartsGetAtTheOriginalUrl() throws Exception {
        checkFallback(501, false);
    }

    @Test
    public void successfulLegacyHeadRetainsItsFinalUrl() throws Exception {
        checkHead(200, false);
    }

    @Test
    public void forbiddenLegacyHeadRetainsItsFinalErrorUrl() throws Exception {
        checkHead(403, false);
    }

    @Test
    public void forbiddenLegacyFallbackGetReturnsTheOriginalUrl() throws Exception {
        checkFailedGet(false);
    }

    private static String resolve(Fixture fixture, boolean strict) throws IOException {
        return strict ? RedirectChecker.getFinalUrlOrThrow(fixture.url("/origin"), AUTHORIZATION)
                : RedirectChecker.getFinalUrl(fixture.url("/origin"));
    }

    private static void checkFallback(int status, boolean strict) throws Exception {
        try (Fixture fixture = new Fixture(status, 206, false, false)) {
            assertEquals(fixture.freshUrl(), resolve(fixture, strict));
            assertFallbackRequests(fixture, strict ? AUTHORIZATION : null);
        }
    }

    private static void checkHead(int status, boolean strict) throws Exception {
        try (Fixture fixture = new Fixture(status, 206, false, false)) {
            if (status == 200) {
                assertEquals(fixture.url("/head-only"), resolve(fixture, strict));
            } else if (strict) {
                assertThrows(IOException.class, () -> resolve(fixture, true));
            } else {
                assertEquals(fixture.url("/head-only"), resolve(fixture, false));
            }
            assertEquals(List.of("HEAD /origin", "HEAD /head-only"), fixture.paths());
            for (RecordedRequest request : fixture.requests) {
                assertNull(request.headers.get("range"));
                assertEquals(strict ? AUTHORIZATION : null, request.headers.get("authorization"));
            }
        }
    }

    private static void checkFailedGet(boolean strict) throws Exception {
        try (Fixture fixture = new Fixture(405, 403, false, false)) {
            if (strict) {
                assertThrows(IOException.class, () -> resolve(fixture, true));
            } else {
                assertEquals(fixture.url("/origin"), resolve(fixture, false));
            }
            assertFallbackRequests(fixture, strict ? AUTHORIZATION : null);
        }
    }

    private static void assertFallbackRequests(Fixture fixture, String authorization) {
        assertEquals(List.of("HEAD /origin", "HEAD /head-only", "GET /origin", "GET /fresh"), fixture.paths());
        List<RecordedRequest> requests = List.copyOf(fixture.requests);
        for (int index = 0; index < requests.size(); index++) {
            assertEquals(index < 2 ? null : "bytes=0-0", requests.get(index).headers.get("range"));
            assertEquals(fixture.crossHost && index == 3 ? null : authorization,
                    requests.get(index).headers.get("authorization"));
        }
    }

    private static final class RecordedRequest {
        final String method;
        final String path;
        final Map<String, String> headers;

        RecordedRequest(String method, String path, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.headers = headers;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final boolean crossHost;
        final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        volatile String headRedirect;
        volatile String getRedirect;
        volatile String acceptedAuthorization;
        volatile int challengesBeforeSuccess;
        private final ServerSocket server;
        private final Thread thread;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile Socket activeSocket;
        private int challenges;

        Fixture(int headStatus, int getStatus, boolean ignoreRange, boolean crossHost) throws IOException {
            this.crossHost = crossHost;
            server = new ServerSocket(0);
            server.setSoTimeout(5000);
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        activeSocket = socket;
                        socket.setSoTimeout(5000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII));
                        String[] firstLine = input.readLine().split(" ", 3);
                        Map<String, String> headers = new HashMap<>();
                        String line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) {
                            int colon = line.indexOf(':');
                            headers.put(line.substring(0, colon).toLowerCase(Locale.US),
                                    line.substring(colon + 1).trim());
                        }
                        String method = firstLine[0];
                        String path = firstLine[1];
                        requests.add(new RecordedRequest(method, path, headers));
                        String location = "";
                        int status;
                        if ("/origin".equals(path)) {
                            status = 302;
                            String target = "HEAD".equals(method) ? headRedirect : getRedirect;
                            location = "Location: " + (target != null ? target
                                    : "HEAD".equals(method) ? url("/head-only") : freshUrl()) + "\r\n";
                        } else if ("/challenge".equals(path)) {
                            status = ++challenges > challengesBeforeSuccess && acceptedAuthorization != null
                                    && acceptedAuthorization.equals(headers.get("authorization")) ? 200 : 401;
                        } else if ("HEAD".equals(method) && "/head-only".equals(path)) {
                            status = headStatus;
                        } else {
                            status = "GET".equals(method) && "/fresh".equals(path) ? getStatus : 404;
                        }
                        int length = status == 206 ? 1 : status == 200 && "GET".equals(method)
                                && ignoreRange ? 1024 * 1024 : 0;
                        String range = status == 206 ? "Content-Range: bytes 0-0/1\r\n" : "";
                        try {
                            socket.getOutputStream().write(("HTTP/1.1 " + status + " Fixture\r\n" + location + range
                                    + "Content-Type: audio/mpeg\r\nContent-Length: " + length
                                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            if (!"HEAD".equals(method)) {
                                socket.getOutputStream().write(new byte[length]);
                            }
                        } catch (IOException disconnected) {
                            if (!(disconnected instanceof SocketException) && !server.isClosed()) {
                                throw disconnected;
                            }
                        }
                    } catch (IOException | RuntimeException error) {
                        if (!server.isClosed()) {
                            failure.set(error);
                        }
                        return;
                    }
                }
            }, "redirect-fixture");
            thread.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getLocalPort() + path;
        }

        String freshUrl() {
            return "http://" + (crossHost ? "localhost" : "127.0.0.1") + ":" + server.getLocalPort() + "/fresh";
        }

        List<String> paths() {
            return List.copyOf(requests).stream().map(request -> request.method + " " + request.path).toList();
        }

        List<String> challengeHeaders() {
            return List.copyOf(requests).stream().filter(request -> "/challenge".equals(request.path))
                    .map(request -> request.headers.get("authorization")).toList();
        }

        @Override
        public void close() throws IOException {
            server.close();
            Socket socket = activeSocket;
            if (socket != null) {
                socket.close();
            }
            try {
                thread.join(5000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while closing synthetic HTTP server", error);
            }
            assertFalse(thread.isAlive());
            assertNull(failure.get());
        }
    }
}
