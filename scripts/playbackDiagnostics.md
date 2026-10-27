# Playback diagnostic APK

Install **AntennaPod Diagnostics** alongside the regular app. The package is
`de.danoeh.antennapod.debug`; it does not replace the release app or its data.
This is our 3.13.0-beta1 working branch with the same core streaming setup and
Media3 1.10.0 as 3.12.3, not the complete 3.12.3 release. Its ordinary User-Agent
contains its actual version. Subscriptions, settings, cookies and cache are separate.

1. Subscribe to the private feed **on your phone** through the normal app UI.
   Do not send the URL to the development environment or put it in Git.
2. Select and stream the episode normally. Tracing is automatic, including after app
   restarts. No URL entry or start/stop step is needed.
3. If you hear a skip, note the approximate player position and any deliberate seeks
   or network changes. Soon afterwards choose **Settings → Report bug → menu →
   Export detailed logs** and share `full-logs.txt` privately here.

Detailed logs contain actual URLs. Never commit or publish them. No automatic upload
occurs, and no private feed requests are made from our servers. No PC script is needed.
Authorization/cookie presence is logged, not credential values. Playback diagnostics
are kept in eight rolling files of up to 1 MiB each in private app storage across
process restarts. Oldest data is discarded at the limit; retention depends on request
activity, not a guaranteed number of days. Position events are recorded every five
seconds. At roughly 100 bytes per position event, these events alone use about
1.7 MiB per day of continuous playback; requests, retries and state changes shorten
that window. This is a size bound, not a promise of several days of history.
Writes run on a bounded background queue; abrupt process termination can lose pending
events, and storage/queue failures are reported to logcat. Individual lines above
16,384 characters are explicitly truncated. Export promptly after a skip. Uninstalling or clearing app data deletes them.
Updates with the same package/signature preserve subscriptions and retained logs.

Look for the `PlaybackTrace` log tag. Events begin with milliseconds since trace start.
Numeric source identities are session-salted, with real URLs logged separately to
correlate them. The trace includes:

- `SOURCE`: source identity and local-file flag; `source` gives its actual URL/path.
- `HEAD`: request number, URL identity, status. `head.N.*` logs each network hop's
  actual method, URL, User-Agent, Accept-Encoding, range headers, credential presence,
  redirect Location and selected response headers. A cached HEAD may have no network
  event; `RESOLVE` still records the result. `HEAD_FAILURE` means transport failure.
- `RESOLVE`: original identity, resolved identity, resolver-cache-hit flag; corresponding
  `resolver.*` fields retain both actual URLs.
- `OPEN`: request number, URL identity, Media3 HTTP method, offset, requested length.
  `get.N.*` retains the initial/final URLs, configured effective User-Agent,
  Media3-generated Range and Accept-Encoding, DataSpec If-Range/Icy-MetaData, and selected
  response headers including Content-Range, Content-Length, ETag and Last-Modified.
  GET request-header values are derived from the unchanged Media3 configuration;
  intermediate GET redirects and platform-added headers/cookies are not intercepted.
- `RESPONSE`: request number, status, open length, final URL identity, validator identities.
- `RANGE`: request number, first byte, last byte, total; -1 means malformed/unsupported.
- `READ`: request number, cumulative delivered bytes, EOF flag (each MiB or EOF).
  `CLOSE`: request number, delivered bytes. `FAILURE`: request number, status or -1, bytes.
- `CACHE`: source identity, total cache size, cached bytes read (reported on close).
  `CACHE_IGNORED`: Media3 reason. Already-buffered playback may issue no request.
- `POSITION`: source identity, raw position ms, duration ms, buffered position ms,
  speed × 1000, playback state, play-when-ready flag (every five seconds).
- `DISCONTINUITY`: source identity, Media3 reason, old/new position ms. Reason 1 is
  seek; 0 is automatic transition. Other reasons are retained for analysis.
- `STATE`: source identity, state, raw position/duration ms. States: 1 idle, 2 buffering,
  3 ready, 4 ended. `PLAYER_ERROR`: source identity and Media3 error code.

This adds observation, not a product fix. It does not change request headers, routing,
retry policy, cache or playback configuration. It does not record decoded audio;
these logs alone cannot prove what content was heard or reproduce the installed
release app's existing cache/session state.
