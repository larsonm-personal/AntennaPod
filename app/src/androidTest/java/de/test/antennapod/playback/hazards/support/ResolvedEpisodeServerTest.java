package de.test.antennapod.playback.hazards.support;

import androidx.test.platform.app.InstrumentationRegistry;

import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.test.antennapod.IgnoreOnCi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@IgnoreOnCi
public class ResolvedEpisodeServerTest {
    private final ResolvedEpisodeServer server = new ResolvedEpisodeServer();
    private final byte[] bytes = new byte[6000];
    private File initial;
    private File changed;

    @Before
    public void setUp() throws Exception {
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) index;
        }
        initial = write(bytes);
        changed = write(Arrays.copyOfRange(bytes, 2000, 6000));
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
    public void onlyFreshResolutionsChangeEdition() throws Exception {
        assertRedirect("A");
        server.switchFreshResolutionsToB();
        assertRedirect("B");
        assertArrayEquals(bytes, read("A", null, 200));
        assertArrayEquals(Arrays.copyOfRange(bytes, 2000, 6000), read("B", null, 200));
    }

    @Test
    public void rangesReturnOnlyRequestedBytes() throws Exception {
        assertArrayEquals(Arrays.copyOfRange(bytes, 100, 200), read("A", "bytes=100-199", 206));
        assertArrayEquals(Arrays.copyOfRange(bytes, 5900, 6000), read("B", "bytes=3900-9999", 206));
        HttpURLConnection response = open("B", "bytes=5000-");
        try {
            assertEquals(416, response.getResponseCode());
            assertEquals("bytes */4000", response.getHeaderField("Content-Range"));
        } finally {
            response.disconnect();
        }
    }

    @Test
    public void responseSlowsAfterTheRequestedBoundary() throws Exception {
        server.throttleResponsesAfter(1024);
        HttpURLConnection response = open("A", "bytes=1000-");
        try (InputStream input = response.getInputStream()) {
            byte[] prefix = new byte[1024];
            IOUtils.readFully(input, prefix);
            assertArrayEquals(Arrays.copyOfRange(bytes, 1000, 2024), prefix);
            FutureTask<Integer> nextByte = new FutureTask<>(() -> input.read());
            new Thread(nextByte, "FixtureResponseRead").start();
            try {
                nextByte.get(500, TimeUnit.MILLISECONDS);
                fail("The response did not slow at the requested boundary");
            } catch (TimeoutException expected) {
                assertTrue(server.hasThrottledResponse());
            }
            server.resumeResponses();
            assertEquals(bytes[2024] & 0xff, (int) nextByte.get(5, TimeUnit.SECONDS));
            assertArrayEquals(Arrays.copyOfRange(bytes, 2025, 6000), IOUtils.toByteArray(input));
        } finally {
            server.resumeResponses();
            response.disconnect();
        }
    }

    private void assertRedirect(String edition) throws Exception {
        HttpURLConnection response = open("original", null);
        try {
            assertEquals(302, response.getResponseCode());
            assertTrue(response.getHeaderField("Location").endsWith("/" + edition));
        } finally {
            response.disconnect();
        }
    }

    private byte[] read(String edition, String range, int status) throws Exception {
        HttpURLConnection response = open(edition, range);
        try (InputStream input = response.getInputStream()) {
            assertEquals(status, response.getResponseCode());
            byte[] body = IOUtils.toByteArray(input);
            assertEquals(body.length, response.getContentLength());
            return body;
        } finally {
            response.disconnect();
        }
    }

    private HttpURLConnection open(String edition, String range) throws Exception {
        HttpURLConnection response = (HttpURLConnection) new URL(
                server.getBaseUrl() + "/resolved-ad/" + edition).openConnection();
        response.setConnectTimeout(5000);
        response.setReadTimeout(5000);
        response.setInstanceFollowRedirects(false);
        if (range != null) {
            response.setRequestProperty("Range", range);
        }
        return response;
    }

    private File write(byte[] body) throws Exception {
        File file = File.createTempFile("episode", ".mp3",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(body);
        }
        return file;
    }
}
