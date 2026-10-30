package de.test.antennapod.playback.hazards;

import androidx.test.platform.app.InstrumentationRegistry;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

@IgnoreOnCi
public class ResolvedEpisodeServerTest {
    private final ResolvedEpisodeServer server = new ResolvedEpisodeServer();
    private final byte[] bytesA = new byte[6000];
    private final byte[] bytesB = new byte[4000];
    private File initial;
    private File changed;

    @Before
    public void setUp() throws Exception {
        for (int index = 0; index < bytesA.length; index++) {
            bytesA[index] = (byte) index;
        }
        for (int index = 0; index < bytesB.length; index++) {
            bytesB[index] = (byte) (255 - index);
        }
        initial = write(bytesA);
        changed = write(bytesB);
        server.start(initial, changed);
    }

    @After
    public void tearDown() {
        server.stop();
        if (initial != null) {
            initial.delete();
        }
        if (changed != null) {
            changed.delete();
        }
    }

    @Test
    public void serverBindsOnlyToItsLoopbackAddress() throws Exception {
        try (ServerSocket otherAddress = new ServerSocket()) {
            otherAddress.bind(new InetSocketAddress("127.0.0.2", server.getListeningPort()));
            assertTrue(otherAddress.isBound());
        }
        assertArrayEquals(bytesA, read("A", null, 200));
    }

    @Test
    public void requestHistoryKeepsRecentEntriesWithinItsLimit() throws Exception {
        for (int index = 0; index < 160; index++) {
            HttpURLConnection response = open("missing-" + index, "HEAD", null);
            try {
                assertEquals(404, response.getResponseCode());
            } finally {
                response.disconnect();
            }
        }
        List<String> history = server.getRequests();
        assertTrue(history.size() <= 128);
        assertEquals("HEAD missing-159 null", history.get(history.size() - 1));
        history.clear();
        assertFalse(server.getRequests().isEmpty());
    }

    @Test
    public void longRejectedRequestsHaveABoundedDiagnosticByteCost() throws Exception {
        String name = URLEncoder.encode(StringUtils.repeat("\u754c", 256), "UTF-8");
        String range = StringUtils.repeat("x", 512);
        for (int index = 0; index < 100; index++) {
            HttpURLConnection response = open("missing-" + name, "HEAD", range);
            try {
                assertEquals(404, response.getResponseCode());
            } finally {
                response.disconnect();
            }
        }
        int bytes = 0;
        for (String request : server.getRequests()) {
            assertTrue(request.length() <= 256);
            bytes += request.getBytes(StandardCharsets.UTF_8).length;
        }
        assertTrue(bytes <= 16 * 1024);
        assertArrayEquals(Arrays.copyOfRange(bytesA, 100, 200), read("A", "bytes=100-199", 206));
        assertEquals(100, server.getBodyBytes());
        server.expireA();
        HttpURLConnection expired = open("A", "HEAD", null);
        try {
            assertEquals(403, expired.getResponseCode());
            assertEquals(1, server.getExpiredResponses());
        } finally {
            expired.disconnect();
        }
    }

    @Test
    public void switchingResolutionDoesNotChangeEitherRepresentation() throws Exception {
        assertRedirect("HEAD", "A");
        assertRedirect("GET", "A");
        assertArrayEquals(bytesA, read("A", null, 200));
        server.switchFreshResolutionsToB();
        assertRedirect("HEAD", "B");
        assertRedirect("GET", "B");
        assertArrayEquals(bytesA, read("A", null, 200));
        assertArrayEquals(bytesB, read("B", null, 200));
        HttpURLConnection head = open("B", "HEAD", null);
        try {
            assertEquals(200, head.getResponseCode());
            assertEquals(bytesB.length, head.getContentLength());
            assertEquals(-1, head.getInputStream().read());
        } finally {
            head.disconnect();
        }
    }

    @Test
    public void sameUrlCanChangeBytesAndLength() throws Exception {
        assertArrayEquals(bytesA, read("A", null, 200));
        server.replaceAWithB();
        assertArrayEquals(bytesB, read("A", null, 200));
        assertRedirect("HEAD", "A");
    }

    @Test
    public void headAndGetCanSelectDifferentEditions() throws Exception {
        server.redirectGetsFromAToB();
        HttpURLConnection head = open("A", "HEAD", null);
        HttpURLConnection get = open("A", "GET", null);
        try {
            assertEquals(200, head.getResponseCode());
            assertEquals(bytesA.length, head.getContentLength());
            assertEquals(302, get.getResponseCode());
            assertTrue(get.getHeaderField("Location").endsWith("/B"));
            assertArrayEquals(bytesB, read("B", null, 200));
        } finally {
            head.disconnect();
            get.disconnect();
        }
    }

    @Test
    public void expiryRejectsAWhileFreshBRemainsAvailable() throws Exception {
        server.expireA();
        server.switchFreshResolutionsToB();
        HttpURLConnection response = open("A", "GET", null);
        try {
            assertEquals(403, response.getResponseCode());
            assertEquals(1, server.getExpiredResponses());
            assertRedirect("HEAD", "B");
            assertArrayEquals(bytesB, read("B", null, 200));
        } finally {
            response.disconnect();
        }
    }

    @Test
    public void ignoredRangeReturnsTheWholeResource() throws Exception {
        server.ignoreRanges();
        HttpURLConnection response = open("A", "GET", "bytes=100-199");
        try {
            assertEquals(200, response.getResponseCode());
            assertNull(response.getHeaderField("Content-Range"));
            assertEquals(bytesA.length, response.getContentLength());
            assertArrayEquals(bytesA, IOUtils.toByteArray(response.getInputStream()));
        } finally {
            response.disconnect();
        }
    }

    @Test
    public void bodyCounterMeasuresBytesRatherThanRequests() throws Exception {
        HttpURLConnection head = open("A", "HEAD", null);
        try {
            assertEquals(200, head.getResponseCode());
            assertEquals(0, server.getBodyBytes());
        } finally {
            head.disconnect();
        }
        assertArrayEquals(bytesA, read("A", null, 200));
        assertEquals(bytesA.length, server.getBodyBytes());
    }

    @Test
    public void mutationCanKeepTheResourceLength() throws Exception {
        byte[] equalLength = Arrays.copyOf(bytesB, bytesA.length);
        try (FileOutputStream output = new FileOutputStream(changed)) {
            output.write(equalLength);
        }
        server.replaceAWithB();
        assertArrayEquals(equalLength, read("A", null, 200));
        assertEquals(initial.length(), changed.length());
    }

    @Test
    public void disconnectedResponseDoesNotPoisonTheNextRequest() throws Exception {
        server.throttleResponsesAfter(1024);
        HttpURLConnection response = open("A", "GET", null);
        response.setReadTimeout(2000);
        int received = 1024;
        try (InputStream input = response.getInputStream()) {
            IOUtils.readFully(input, new byte[1024]);
            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(server::hasSentPrefix);
            server.disconnectActiveResponses();
            server.resumeResponses();
            try {
                while (input.read() != -1) {
                    received++;
                }
            } catch (IOException interrupted) {
                assertFalse("Disconnected response remained open", interrupted instanceof SocketTimeoutException);
                assertTrue("No synthetic interruption", server.getInterruptedResponses() > 0);
            }
            assertTrue("Response was not interrupted", received < bytesA.length);
            assertTrue("No synthetic interruption", server.getInterruptedResponses() > 0);
        } finally {
            response.disconnect();
        }
        assertArrayEquals(bytesA, read("A", null, 200));
    }

    @Test
    public void rangesAndEndOfFileAreCoherent() throws Exception {
        assertArrayEquals(Arrays.copyOfRange(bytesA, 100, 200), read("A", "bytes=100-199", 206));
        assertArrayEquals(Arrays.copyOfRange(bytesB, 3900, 4000), read("B", "bytes=3900-9999", 206));
        HttpURLConnection response = open("B", "GET", "bytes=4000-");
        try {
            assertEquals(416, response.getResponseCode());
            assertEquals("bytes */4000", response.getHeaderField("Content-Range"));
        } finally {
            response.disconnect();
        }
    }

    @Test
    public void resumingThrottledResponseDoesNotTruncateIt() throws Exception {
        assertThrottledResponse(0);
        assertThrottledResponse(1000);
    }

    @Test
    public void rangeHeaderDoesNotLeakAcrossKeepAliveRequests() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getListeningPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /resolved-ad/A HTTP/1.1\r\nHost: localhost\r\n"
                    + "Range: bytes=100-199\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertSocketResponse(socket.getInputStream(), 206, Arrays.copyOfRange(bytesA, 100, 200));
            socket.getOutputStream().write(("GET /resolved-ad/B HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            assertSocketResponse(socket.getInputStream(), 200, bytesB);
        }
    }

    private void assertSocketResponse(InputStream input, int status, byte[] expected) throws Exception {
        StringBuilder header = new StringBuilder();
        while (!header.toString().endsWith("\r\n\r\n")) {
            int next = input.read();
            assertTrue("Response header ended early", next != -1);
            assertTrue("Response header exceeds 8 KB", header.length() < 8192);
            header.append((char) next);
        }
        assertTrue(header.toString(), header.toString().startsWith("HTTP/1.1 " + status + " "));
        assertTrue(header.toString().toLowerCase(Locale.ROOT).contains("content-length: " + expected.length + "\r\n"));
        byte[] body = new byte[expected.length];
        IOUtils.readFully(input, body);
        assertArrayEquals(expected, body);
    }

    private void assertThrottledResponse(int start) throws Exception {
        server.throttleResponsesAfter(1024);
        HttpURLConnection response = open("A", "GET", start == 0 ? null : "bytes=" + start + "-");
        try (InputStream input = response.getInputStream()) {
            assertEquals(bytesA.length - start, response.getContentLength());
            byte[] prefix = new byte[1024];
            IOUtils.readFully(input, prefix);
            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(server::hasSentPrefix);
            server.resumeResponses();
            assertArrayEquals(Arrays.copyOfRange(bytesA, start, start + 1024), prefix);
            assertArrayEquals(Arrays.copyOfRange(bytesA, start + 1024, bytesA.length), IOUtils.toByteArray(input));
            assertEquals(-1, input.read());
        } finally {
            response.disconnect();
        }
    }

    private void assertRedirect(String method, String destination) throws Exception {
        HttpURLConnection response = open("original", method, null);
        try {
            assertEquals(302, response.getResponseCode());
            assertTrue(response.getHeaderField("Location").endsWith("/" + destination));
        } finally {
            response.disconnect();
        }
    }

    private byte[] read(String name, String range, int status) throws Exception {
        HttpURLConnection response = open(name, "GET", range);
        try (InputStream input = response.getInputStream()) {
            assertEquals(status, response.getResponseCode());
            byte[] bytes = IOUtils.toByteArray(input);
            assertEquals(response.getContentLength(), bytes.length);
            if (range != null) {
                assertEquals(name.equals("A") ? "bytes 100-199/6000" : "bytes 3900-3999/4000",
                        response.getHeaderField("Content-Range"));
            }
            return bytes;
        } finally {
            response.disconnect();
        }
    }

    private HttpURLConnection open(String name, String method, String range) throws Exception {
        HttpURLConnection response = (HttpURLConnection) new URL(
                server.getBaseUrl() + "/resolved-ad/" + name).openConnection();
        response.setConnectTimeout(5000);
        response.setReadTimeout(5000);
        response.setInstanceFollowRedirects(false);
        response.setRequestMethod(method);
        if (range != null) {
            response.setRequestProperty("Range", range);
        }
        return response;
    }

    private File write(byte[] bytes) throws Exception {
        File file = File.createTempFile("resolved-episode", ".bin",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        return file;
    }
}
