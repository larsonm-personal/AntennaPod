package de.test.antennapod.playback.hazards;

import de.test.antennapod.util.service.download.HTTPBin;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ResolvedEpisodeServer extends HTTPBin {
    private final CountDownLatch ready = new CountDownLatch(1);
    private final CountDownLatch switchRequested = new CountDownLatch(1);
    private final CountDownLatch switched = new CountDownLatch(1);
    private final List<String> requests = new ArrayList<>();
    private volatile String resolved;
    private File initial;
    private File changed;
    private Thread scenario;
    private volatile CountDownLatch responseGate = new CountDownLatch(0);
    private volatile int throttleAfter;
    private volatile boolean prefixSent;
    private volatile boolean replaceA;
    private volatile boolean redirectAGets;

    public void start(File a, File b) throws Exception {
        initial = a;
        changed = b;
        scenario = new Thread(() -> {
            resolved = "A";
            ready.countDown();
            try {
                switchRequested.await();
                resolved = "B";
                switched.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "resolved-episode-scenario");
        scenario.setDaemon(true);
        scenario.start();
        if (!ready.await(5, TimeUnit.SECONDS)) {
            throw new IOException("Server scenario did not start");
        }
        super.start();
    }

    public void switchFreshResolutionsToB() throws InterruptedException {
        switchRequested.countDown();
        if (!switched.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Server scenario did not switch to B");
        }
    }

    public void replaceAWithB() {
        replaceA = true;
    }

    public void redirectGetsFromAToB() {
        redirectAGets = true;
    }

    public void throttleResponsesAfter(int bytes) {
        throttleAfter = bytes;
        prefixSent = false;
        responseGate = new CountDownLatch(1);
    }

    public boolean hasSentPrefix() {
        return prefixSent;
    }

    public void resumeResponses() {
        responseGate.countDown();
    }

    public synchronized List<String> getRequests() {
        return new ArrayList<>(requests);
    }

    @Override
    public void stop() {
        resumeResponses();
        try {
            if (scenario != null) {
                scenario.interrupt();
                try {
                    scenario.join(5000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (scenario.isAlive()) {
                    throw new IllegalStateException("Server scenario did not stop");
                }
            }
        } finally {
            super.stop();
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String name = session.getUri().replace("/resolved-ad/", "");
        String range = session.getHeaders().remove("range");
        synchronized (this) {
            requests.add(session.getMethod() + " " + name + " " + range);
        }
        boolean redirectGet = redirectAGets && name.equals("A") && session.getMethod() == Method.GET;
        if (name.equals("original") || redirectGet) {
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
            response.addHeader("Location", getBaseUrl() + "/resolved-ad/" + (redirectGet ? "B" : resolved));
            return response;
        }
        if (!name.equals("A") && !name.equals("B")) {
            return new Response(Response.Status.NOT_FOUND, "text/plain", "");
        }
        File file = name.equals("B") || replaceA ? changed : initial;
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
        boolean throttle = session.getMethod() == Method.GET && gate.getCount() > 0;
        InputStream body = new InputStream() {
            private final ByteArrayInputStream source = new ByteArrayInputStream(bytes);
            private int sent;

            @Override
            public int available() {
                return source.available();
            }

            @Override
            public int read() throws IOException {
                byte[] single = new byte[1];
                return read(single, 0, 1) == -1 ? -1 : single[0] & 255;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (throttle && sent >= throttleAfter) {
                    prefixSent = true;
                    try {
                        if (!gate.await(200, TimeUnit.MILLISECONDS)) {
                            length = Math.min(length, 1024);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Server response interrupted");
                    }
                } else if (throttle) {
                    length = Math.min(length, throttleAfter - sent);
                }
                int count = source.read(buffer, offset, length);
                if (count > 0) {
                    sent += count;
                }
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
