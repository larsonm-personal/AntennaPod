package de.test.antennapod.playback.hazards.support;

import android.util.Log;

import fi.iki.elonen.NanoHTTPD;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ResolvedEpisodeServer extends NanoHTTPD {
    private File initial;
    private File changed;
    private volatile String resolved = "A";
    private volatile CountDownLatch responseGate = new CountDownLatch(0);
    private int throttleAfter;
    private volatile boolean throttled;

    public ResolvedEpisodeServer() {
        super("127.0.0.1", 0);
    }

    public String getBaseUrl() {
        return "http://127.0.0.1:" + getListeningPort();
    }

    public void start(File a, File b) throws IOException {
        initial = a;
        changed = b;
        super.start();
    }

    public void switchFreshResolutionsToB() {
        resolved = "B";
    }

    public void throttleResponsesAfter(int bytes) {
        throttleAfter = bytes;
        throttled = false;
        responseGate = new CountDownLatch(1);
    }

    public boolean hasThrottledResponse() {
        return throttled;
    }

    public void resumeResponses() {
        responseGate.countDown();
    }

    @Override
    public void stop() {
        resumeResponses();
        super.stop();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String name = session.getUri().replace("/resolved-ad/", "");
        if (name.equals("original")) {
            Response response = new Response(new Response.IStatus() {
                @Override
                public int getRequestStatus() {
                    return 302;
                }

                @Override
                public String getDescription() {
                    return "302 Found";
                }
            }, "text/plain", "");
            response.addHeader("Location", getBaseUrl() + "/resolved-ad/" + resolved);
            Log.i("ServiceAudioObserver", session.getMethod() + " original -> " + resolved);
            return response;
        }
        if (!name.equals("A") && !name.equals("B")) {
            return new Response(Response.Status.NOT_FOUND, "text/plain", "");
        }
        File file = name.equals("A") ? initial : changed;
        String range = session.getHeaders().get("range");
        Log.i("ServiceAudioObserver", session.getMethod() + " " + name + " " + StringUtils.abbreviate(range, 96));
        long start = 0;
        long end = file.length() - 1;
        if (range != null) {
            Matcher match = Pattern.compile("bytes=([0-9]+)-([0-9]*)").matcher(range);
            try {
                if (!match.matches()) {
                    return unsatisfiable(file);
                }
                start = Long.parseLong(match.group(1));
                end = match.group(2).isEmpty() ? end : Math.min(end, Long.parseLong(match.group(2)));
            } catch (NumberFormatException invalid) {
                return unsatisfiable(file);
            }
            if (start > end) {
                return unsatisfiable(file);
            }
        }
        byte[] bytes = new byte[(int) (end - start + 1)];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(start);
            input.readFully(bytes);
        } catch (IOException error) {
            return new Response(Response.Status.INTERNAL_ERROR, "text/plain", "");
        }
        CountDownLatch gate = responseGate;
        int limit = throttleAfter;
        InputStream body = new ByteArrayInputStream(bytes) {
            private int sent;

            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                if (gate.getCount() > 0) {
                    if (sent >= limit) {
                        throttled = true;
                        try {
                            if (!gate.await(1, TimeUnit.SECONDS)) {
                                length = Math.min(length, 1);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return -1;
                        }
                    } else {
                        length = Math.min(length, limit - sent);
                    }
                }
                int count = super.read(buffer, offset, length);
                sent += Math.max(0, count);
                return count;
            }
        };
        Response response = new Response(range == null ? Response.Status.OK : Response.Status.PARTIAL_CONTENT,
                "audio/mpeg", body);
        response.addHeader("Content-Length", String.valueOf(bytes.length));
        response.addHeader("Accept-Ranges", "bytes");
        if (range != null) {
            response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + file.length());
        }
        return response;
    }

    private Response unsatisfiable(File file) {
        Response response = new Response(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
        response.addHeader("Content-Range", "bytes */" + file.length());
        return response;
    }
}
