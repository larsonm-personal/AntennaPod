package de.danoeh.antennapod.playback.service.internal;

import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.test.platform.app.InstrumentationRegistry;
import de.danoeh.antennapod.net.common.PlaybackTrace;
import de.danoeh.antennapod.net.common.RedirectChecker;
import de.danoeh.antennapod.net.common.UserAgentInterceptor;
import de.test.antennapod.util.service.download.HTTPBin;
import org.apache.commons.io.IOUtils;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PlaybackDiagnosticsTest {
    @Test
    public void redirectsErrorsAndIgnoredRangeRetainActualEvidence() throws Exception {
        List<String> received = new ArrayList<>();
        HTTPBin server = new HTTPBin() {
            @Override
            public Response serve(IHTTPSession session) {
                synchronized (received) {
                    received.add(session.getMethod() + " " + session.getUri() + " "
                            + session.getHeaders().get("range"));
                }
                Response response;
                if (session.getUri().equals("/observe/start")) {
                    response = new Response(Response.Status.REDIRECT, "text/plain", "");
                    response.addHeader("Location", "/observe/final?session=SYNTHETIC");
                } else if (session.getUri().equals("/observe/error")) {
                    response = new Response(Response.Status.FORBIDDEN, "text/plain", "denied");
                    response.addHeader("X-Diagnostic-Fixture", "failure-response");
                } else {
                    response = new Response(Response.Status.OK, "text/plain", "0123456789");
                }
                response.addHeader("Connection", "close");
                return response;
            }
        };
        server.start();
        File exported = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "http-evidence.txt");
        try {
            String start = server.getBaseUrl() + "/observe/start";
            PlaybackTrace.select(start, false);
            assertEquals(server.getBaseUrl() + "/observe/final?session=SYNTHETIC",
                    RedirectChecker.getFinalUrl(start));
            PlaybackDiagnostics source = new PlaybackDiagnostics(new DefaultHttpDataSource.Factory()
                    .setUserAgent(UserAgentInterceptor.USER_AGENT).setAllowCrossProtocolRedirects(true)
                    .setKeepPostFor302Redirects(true).createDataSource(), start, false);
            try {
                assertEquals(6, source.open(new DataSpec.Builder().setUri(start).setPosition(4).build()));
                byte[] bytes = new byte[6];
                assertEquals(6, source.read(bytes, 0, 6));
                assertEquals("456789", new String(bytes, StandardCharsets.UTF_8));
            } finally {
                source.close();
            }
            try {
                source.open(new DataSpec.Builder().setUri(server.getBaseUrl() + "/observe/error").build());
                fail("Expected HTTP failure");
            } catch (HttpDataSource.InvalidResponseCodeException expected) {
                assertEquals(403, expected.responseCode);
            } finally {
                source.close();
            }
            exported.delete();
            PlaybackTrace.export(exported);
            String evidence;
            try (FileInputStream input = new FileInputStream(exported)) {
                evidence = IOUtils.toString(input, StandardCharsets.UTF_8);
            }
            String current = evidence.substring(evidence.lastIndexOf("processStartedUtcMillis"));
            assertTrue(current.contains(".method=\"HEAD\""));
            assertTrue(current.contains("response.Location"));
            assertTrue(current.contains("session=SYNTHETIC"));
            assertTrue(current.matches("(?s).* INPUT [0-9]+ 4 0.*"));
            assertTrue(current.matches("(?s).* CLOSE [0-9]+ 6 10.*"));
            assertTrue(current.contains("failure-response"));
            synchronized (received) {
                assertTrue(received.contains("HEAD /observe/start null"));
                assertTrue(received.contains("HEAD /observe/final null"));
                assertTrue(received.contains("GET /observe/start bytes=4-"));
                assertTrue(received.contains("GET /observe/final bytes=4-"));
            }
        } finally {
            exported.delete();
            server.stop();
        }
    }
}
