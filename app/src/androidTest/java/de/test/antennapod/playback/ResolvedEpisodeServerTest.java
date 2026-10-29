package de.test.antennapod.playback;

import androidx.test.platform.app.InstrumentationRegistry;
import org.apache.commons.io.IOUtils;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
