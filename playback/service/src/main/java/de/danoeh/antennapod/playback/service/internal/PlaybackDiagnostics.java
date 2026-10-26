package de.danoeh.antennapod.playback.service.internal;

import android.net.Uri;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpUtil;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import de.danoeh.antennapod.net.common.PlaybackTrace;
import de.danoeh.antennapod.net.common.UserAgentInterceptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@OptIn(markerClass = UnstableApi.class)
final class PlaybackDiagnostics implements DataSource {
    private final DefaultHttpDataSource upstream;
    private final String source;
    private long request;
    private long bytes;
    private long reported;
    private final boolean authorizationPresent;

    PlaybackDiagnostics(DefaultHttpDataSource upstream, String source, boolean authorizationPresent) {
        this.upstream = upstream;
        this.source = source;
        this.authorizationPresent = authorizationPresent;
    }

    private void record(PlaybackTrace.Event event, long... values) {
        if (PlaybackTrace.matches(source)) {
            PlaybackTrace.record(event, values);
        }
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        request = PlaybackTrace.nextRequest();
        bytes = 0;
        reported = 0;
        record(PlaybackTrace.Event.OPEN, request, PlaybackTrace.identity(spec.uri.toString()),
                spec.httpMethod, spec.position, spec.length);
        if (PlaybackTrace.matches(source)) {
            PlaybackTrace.detail("get." + request + ".url", spec.uri.toString());
            PlaybackTrace.detail("get." + request + ".User-Agent", UserAgentInterceptor.USER_AGENT);
            PlaybackTrace.detail("get." + request + ".Range",
                    HttpUtil.buildRangeRequestHeader(spec.position, spec.length));
            PlaybackTrace.detail("get." + request + ".Accept-Encoding",
                    spec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP) ? "gzip" : "identity");
            for (String name : new String[]{"If-Range", "Icy-MetaData"}) {
                for (Map.Entry<String, String> header : spec.httpRequestHeaders.entrySet()) {
                    if (name.equalsIgnoreCase(header.getKey())) {
                        PlaybackTrace.detail("get." + request + "." + name, header.getValue());
                    }
                }
            }
            PlaybackTrace.detail("get." + request + ".authorizationPresent", Boolean.toString(authorizationPresent));
        }
        try {
            long length = upstream.open(spec);
            record(PlaybackTrace.Event.RESPONSE, request, upstream.getResponseCode(), length,
                    PlaybackTrace.identity(String.valueOf(upstream.getUri())),
                    PlaybackTrace.identity(header("ETag")), PlaybackTrace.identity(header("Last-Modified")));
            if (PlaybackTrace.matches(source)) {
                PlaybackTrace.detail("get." + request + ".finalUrl", String.valueOf(upstream.getUri()));
                for (String name : new String[]{"Content-Length", "Content-Range", "ETag", "Last-Modified",
                        "Content-Type", "Content-Encoding", "Accept-Ranges"}) {
                    PlaybackTrace.detail("get." + request + ".response." + name, header(name));
                }
            }
            String range = header("Content-Range");
            if (range != null) {
                Matcher matcher = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(range);
                if (matcher.matches()) {
                    try {
                        record(PlaybackTrace.Event.RANGE, request, Long.parseLong(matcher.group(1)),
                                Long.parseLong(matcher.group(2)), Long.parseLong(matcher.group(3)));
                    } catch (NumberFormatException ignored) {
                        record(PlaybackTrace.Event.RANGE, request, -1);
                    }
                } else {
                    record(PlaybackTrace.Event.RANGE, request, -1);
                }
            }
            return length;
        } catch (IOException error) {
            record(PlaybackTrace.Event.FAILURE, request,
                    error instanceof HttpDataSource.InvalidResponseCodeException
                            ? ((HttpDataSource.InvalidResponseCodeException) error).responseCode : -1, bytes);
            throw error;
        }
    }

    private String header(String name) {
        for (Map.Entry<String, List<String>> entry : upstream.getResponseHeaders().entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        try {
            int count = upstream.read(buffer, offset, length);
            if (count > 0) {
                bytes += count;
            }
            if (count == -1 || bytes - reported >= 1024 * 1024) {
                record(PlaybackTrace.Event.READ, request, bytes, count == -1 ? 1 : 0);
                reported = bytes;
            }
            return count;
        } catch (IOException error) {
            record(PlaybackTrace.Event.FAILURE, request, -1, bytes);
            throw error;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            upstream.close();
        } finally {
            record(PlaybackTrace.Event.CLOSE, request, bytes);
        }
    }

    @Override
    public void addTransferListener(TransferListener listener) {
        upstream.addTransferListener(listener);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    static void attach(ExoPlayer player) {
        Handler handler = new Handler(player.getApplicationLooper());
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (selected(player)) {
                    PlaybackTrace.record(PlaybackTrace.Event.POSITION, mediaIdentity(player),
                            player.getCurrentPosition(), player.getDuration(),
                            player.getBufferedPosition(), (long) (player.getPlaybackParameters().speed * 1000),
                            player.getPlaybackState(), player.getPlayWhenReady() ? 1 : 0);
                }
                handler.postDelayed(this, 5000);
            }
        };
        handler.post(tick);
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onPlayerReleased(EventTime time) {
                handler.removeCallbacks(tick);
            }
        });
        player.addListener(new Player.Listener() {
            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition,
                                                int reason) {
                if (selected(player)) {
                    PlaybackTrace.record(PlaybackTrace.Event.DISCONTINUITY, mediaIdentity(player), reason,
                            oldPosition.positionMs, newPosition.positionMs);
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (selected(player)) {
                    PlaybackTrace.record(PlaybackTrace.Event.STATE, mediaIdentity(player), state,
                            player.getCurrentPosition(),
                            player.getDuration());
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (selected(player)) {
                    PlaybackTrace.record(PlaybackTrace.Event.PLAYER_ERROR, mediaIdentity(player), error.errorCode);
                }
            }
        });
    }

    private static long mediaIdentity(ExoPlayer player) {
        MediaItem item = player.getCurrentMediaItem();
        return item != null && item.localConfiguration != null
                ? PlaybackTrace.identity(item.localConfiguration.uri.toString()) : 0;
    }

    private static boolean selected(ExoPlayer player) {
        MediaItem item = player.getCurrentMediaItem();
        return item != null && item.localConfiguration != null
                && PlaybackTrace.matches(item.localConfiguration.uri.toString());
    }
}
