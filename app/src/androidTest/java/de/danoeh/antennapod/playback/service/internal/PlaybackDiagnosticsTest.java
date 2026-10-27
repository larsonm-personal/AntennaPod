package de.danoeh.antennapod.playback.service.internal;

import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.test.platform.app.InstrumentationRegistry;
import de.danoeh.antennapod.net.common.PlaybackTrace;
import de.danoeh.antennapod.net.common.RedirectChecker;
import de.danoeh.antennapod.net.common.UserAgentInterceptor;
import de.test.antennapod.util.service.download.HTTPBin;
import org.apache.commons.io.IOUtils;
import org.junit.Test;
import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

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
                Map<String, String> body = new HashMap<>();
                if (session.getMethod() == Method.POST) {
                    try {
                        session.parseBody(body);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }
                synchronized (received) {
                    received.add(session.getMethod() + " " + session.getUri() + " "
                            + session.getHeaders().get("range") + " " + session.getHeaders().get("x-fixture")
                            + " " + body.get("postData"));
                }
                Response response;
                if (session.getUri().equals("/observe/post")) {
                    response = new Response(new Response.IStatus() {
                        @Override
                        public int getRequestStatus() {
                            return 303;
                        }

                        @Override
                        public String getDescription() {
                            return "303 See Other";
                        }
                    }, "text/plain", "");
                    response.addHeader("Location", "/observe/final?session=SYNTHETIC");
                } else if (session.getUri().equals("/observe/start")) {
                    response = new Response(Response.Status.REDIRECT, "text/plain", "");
                    response.addHeader("Location", "/observe/final?session=SYNTHETIC");
                } else if (session.getUri().equals("/observe/error")) {
                    response = new Response(Response.Status.FORBIDDEN, "text/plain", "denied");
                    response.addHeader("X-Diagnostic-Fixture", "failure-response");
                } else {
                    response = new Response(Response.Status.OK, "text/plain", "0123456789");
                }
                response.addHeader("Connection", "close");
                response.addHeader("X-Fixture-Reply", "one");
                response.addHeader("x-fixture-reply", "two");
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
            synchronized (received) {
                received.clear();
            }
            DataSpec range = new DataSpec.Builder().setUri(start).setPosition(4)
                    .setHttpRequestHeaders(Collections.singletonMap("X-Fixture", "retained-header")).build();
            DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(UserAgentInterceptor.USER_AGENT).setAllowCrossProtocolRedirects(true)
                    .setKeepPostFor302Redirects(true);
            List<String> control;
            DataSource unobserved = factory.createDataSource();
            try {
                assertEquals(6, unobserved.open(range));
                byte[] bytes = new byte[6];
                assertEquals(6, unobserved.read(bytes, 0, 6));
                assertEquals("456789", new String(bytes, StandardCharsets.UTF_8));
            } finally {
                unobserved.close();
            }
            synchronized (received) {
                control = new ArrayList<>(received);
                received.clear();
            }
            PlaybackDiagnostics source = new PlaybackDiagnostics(factory.createDataSource(), start, false);
            try {
                assertEquals(6, source.open(range));
                byte[] bytes = new byte[6];
                assertEquals(6, source.read(bytes, 0, 6));
                assertEquals("456789", new String(bytes, StandardCharsets.UTF_8));
            } finally {
                source.close();
            }
            synchronized (received) {
                assertEquals(control, received);
                received.clear();
            }
            Map<String, String> postHeaders = new HashMap<>();
            postHeaders.put("X-Fixture", "post-header");
            postHeaders.put("Content-Type", "text/plain");
            DataSpec post = new DataSpec.Builder().setUri(server.getBaseUrl() + "/observe/post")
                    .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                    .setHttpBody("body-fixture".getBytes(StandardCharsets.UTF_8))
                    .setHttpRequestHeaders(postHeaders).build();
            try {
                assertEquals(10, unobserved.open(post));
            } finally {
                unobserved.close();
            }
            synchronized (received) {
                control = new ArrayList<>(received);
                received.clear();
            }
            try {
                assertEquals(10, source.open(post));
            } finally {
                source.close();
            }
            synchronized (received) {
                assertEquals(control, received);
                assertTrue(received.contains("POST /observe/post null post-header body-fixture"));
                assertTrue(received.contains("GET /observe/final null post-header null"));
                received.clear();
            }
            DataSpec denied = new DataSpec.Builder().setUri(server.getBaseUrl() + "/observe/error").build();
            try {
                unobserved.open(denied);
                fail("Expected control HTTP failure");
            } catch (HttpDataSource.InvalidResponseCodeException expected) {
                assertEquals(403, expected.responseCode);
            } finally {
                unobserved.close();
            }
            synchronized (received) {
                control = new ArrayList<>(received);
                received.clear();
            }
            try {
                source.open(denied);
                fail("Expected HTTP failure");
            } catch (HttpDataSource.InvalidResponseCodeException expected) {
                assertEquals(403, expected.responseCode);
            } finally {
                source.close();
            }
            synchronized (received) {
                assertEquals(control, received);
            }
            server.stop();
            HttpDataSource.HttpDataSourceException connectionFailure = null;
            try {
                unobserved.open(range);
                fail("Expected control connection failure");
            } catch (HttpDataSource.HttpDataSourceException expected) {
                connectionFailure = expected;
            } finally {
                unobserved.close();
            }
            try {
                source.open(range);
                fail("Expected observed connection failure");
            } catch (HttpDataSource.HttpDataSourceException expected) {
                assertEquals(connectionFailure.reason, expected.reason);
                assertEquals(connectionFailure.type, expected.type);
                assertEquals(connectionFailure.getCause().getClass(), expected.getCause().getClass());
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
            assertTrue(current.contains(".hop.1.method=\"GET\""));
            assertTrue(current.contains(".hop.2.method=\"GET\""));
            assertTrue(current.contains(".hop.1.method=\"POST\""));
            assertTrue(current.contains(".hop.1.status=\"303\""));
            assertTrue(current.contains(".hop.1.response.Location"));
            assertTrue(current.contains(".hop.2.url"));
            assertTrue(current.contains(".configuredRequest.X-Fixture"));
            assertTrue(current.contains(".hop.1.status=\"403\""));
            assertTrue(current.contains(".hop.1.errorClass"));
            assertTrue(current.contains(".hop.1.automaticRedirects=\"false\""));
            boolean duplicateHeader = false;
            for (String line : current.split("\n")) {
                if (line.toLowerCase(Locale.ROOT).contains(".response.x-fixture-reply=")) {
                    String quoted = line.substring(line.indexOf('=') + 1);
                    JSONArray values = new JSONArray(new JSONArray("[" + quoted + "]").getString(0));
                    assertEquals(2, values.length());
                    assertTrue(values.toString().contains("one"));
                    assertTrue(values.toString().contains("two"));
                    duplicateHeader = true;
                }
            }
            assertTrue(duplicateHeader);
        } finally {
            exported.delete();
            server.stop();
        }
    }
}
