package de.danoeh.antennapod.net.common;

import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.http.StatusLine;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;

public abstract class RedirectChecker {
    private static final String TAG = "RedirectChecker";

    @Nullable
    public static String getNewUrlIfPermanentRedirect(Response response) {
        // detect 301 Moved permanently and 308 Permanent Redirect
        ArrayList<Response> responses = new ArrayList<>();
        while (response != null) {
            responses.add(response);
            response = response.priorResponse();
        }
        if (responses.size() < 2) {
            return null;
        }
        Collections.reverse(responses);
        int firstCode = responses.get(0).code();
        String firstUrl = responses.get(0).request().url().toString();
        String secondUrl = responses.get(1).request().url().toString();
        if (firstCode == HttpURLConnection.HTTP_MOVED_PERM || firstCode == StatusLine.HTTP_PERM_REDIRECT) {
            Log.d(TAG, "Detected permanent redirect from " + firstUrl + " to " + secondUrl);
            return secondUrl;
        } else if (secondUrl.equals(firstUrl.replace("http://", "https://"))) {
            Log.d(TAG, "Treating http->https non-permanent redirect as permanent: " + firstUrl);
            return secondUrl;
        }
        return null;
    }

    @Nullable
    public static String getNewUrlIfPermanentRedirect(String downloadUrl) {
        try {
            Request httpReq = new Request.Builder().url(downloadUrl).head().build();
            Response response = AntennapodHttpClient.getHttpClient().newCall(httpReq).execute();
            return RedirectChecker.getNewUrlIfPermanentRedirect(response);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @NonNull
    public static String getFinalUrlOrThrow(@NonNull String url, @Nullable String authorization) throws IOException {
        Request.Builder request = new Request.Builder().url(url).head();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request.build()).execute()) {
            if (response.code() != HttpURLConnection.HTTP_BAD_METHOD
                    && response.code() != HttpURLConnection.HTTP_NOT_IMPLEMENTED) {
                if (!response.isSuccessful()) {
                    throw new IOException("Resolution failed with HTTP " + response.code());
                }
                return response.request().url().toString();
            }
        }
        return getFinalUrlWithGet(request);
    }

    private static String getFinalUrlWithGet(Request.Builder request) throws IOException {
        Call call = AntennapodHttpClient.getHttpClient().newCall(request.get().header("Range", "bytes=0-0").build());
        try (Response response = call.execute()) {
            call.cancel();
            if (!response.isSuccessful()) {
                throw new IOException("Resolution failed with HTTP " + response.code());
            }
            return response.request().url().toString();
        }
    }

    @NonNull
    public static String getFinalUrl(@NonNull String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            return url;
        }
        try {
            Request httpReq = new Request.Builder().url(url).head().build();
            Response response = AntennapodHttpClient.getHttpClient().newCall(httpReq).execute();
            response.close();
            if (response.code() == HttpURLConnection.HTTP_BAD_METHOD
                    || response.code() == HttpURLConnection.HTTP_NOT_IMPLEMENTED) {
                return getFinalUrlWithGet(httpReq.newBuilder());
            }
            return response.request().url().toString();
        } catch (IOException e) {
            Log.e(TAG, "Failed to follow redirects for " + url + ": " + e.getMessage());
            return url;
        }
    }
}
