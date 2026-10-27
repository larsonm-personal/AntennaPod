package de.danoeh.antennapod;

import android.util.Log;
import de.danoeh.antennapod.net.common.PlaybackTrace;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;

public final class Media3HttpTrace {
    private Media3HttpTrace() {
    }

    public static void connect(HttpURLConnection connection) throws IOException {
        observeRequest(connection);
        try {
            connection.connect();
        } catch (IOException error) {
            observeError(connection, error);
            throw error;
        }
    }

    public static int responseCode(HttpURLConnection connection) throws IOException {
        int status;
        try {
            status = connection.getResponseCode();
        } catch (IOException error) {
            observeError(connection, error);
            throw error;
        }
        try {
            PlaybackTrace.HttpRequest request = PlaybackTrace.currentHttpRequest();
            if (request != null && request.firstResponse(connection)) {
                String prefix = prefix(request, connection);
                PlaybackTrace.detail(prefix + "responseUrl", connection.getURL().toString());
                PlaybackTrace.detail(prefix + "status", Integer.toString(status));
                headers(prefix + "response.", connection.getHeaderFields());
            }
        } catch (RuntimeException error) {
            Log.e("PlaybackTrace", "Media3 response observation failed: " + error.getClass().getName());
        }
        return status;
    }

    private static void observeRequest(HttpURLConnection connection) {
        try {
            PlaybackTrace.HttpRequest request = PlaybackTrace.currentHttpRequest();
            if (request == null) {
                return;
            }
            String prefix = prefix(request, connection);
            PlaybackTrace.detail(prefix + "url", connection.getURL().toString());
            PlaybackTrace.detail(prefix + "method", connection.getRequestMethod());
            PlaybackTrace.detail(prefix + "automaticRedirects",
                    Boolean.toString(connection.getInstanceFollowRedirects()));
            PlaybackTrace.detail(prefix + "hasRequestBody", Boolean.toString(connection.getDoOutput()));
            headers(prefix + "configuredRequest.", connection.getRequestProperties());
        } catch (RuntimeException error) {
            Log.e("PlaybackTrace", "Media3 request observation failed: " + error.getClass().getName());
        }
    }

    private static void observeError(HttpURLConnection connection, IOException error) {
        try {
            PlaybackTrace.HttpRequest request = PlaybackTrace.currentHttpRequest();
            if (request != null) {
                PlaybackTrace.detail(prefix(request, connection) + "errorClass", error.getClass().getName());
            }
        } catch (RuntimeException observationError) {
            Log.e("PlaybackTrace", "Media3 error observation failed");
        }
    }

    private static String prefix(PlaybackTrace.HttpRequest request, HttpURLConnection connection) {
        return "get." + request.request + ".hop." + request.hop(connection) + ".";
    }

    private static void headers(String prefix, Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            PlaybackTrace.headerValues(prefix, header.getKey(), header.getValue());
        }
    }
}
