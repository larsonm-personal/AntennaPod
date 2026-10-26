# Playback diagnostic APK

Install **AntennaPod Diagnostics** alongside the regular app. The package is
`de.danoeh.antennapod.debug`; it does not replace the release app or its data.
This is our 3.13.0-beta1 working branch with the same core streaming setup and
Media3 1.10.0 as 3.12.3, not the complete 3.12.3 release. Its ordinary User-Agent
contains its actual version. Subscriptions, settings, cookies and cache are separate.

1. Subscribe to the private feed **on your phone** through the normal app UI.
   Do not send the URL to the development environment or put it in Git.
2. Before selecting the episode for playback, open **Settings → Report bug → menu →
   Start private playback trace**. Paste the exact subscribed feed URL or exact
   episode audio URL into the local masked field. Matching is exact; if subscription
   redirected, use the stored subscribed URL. A feed target traces that feed's episodes.
3. Select and stream the episode normally. Note the approximate player position if
   you hear a skip, and any deliberate seeks or network changes.
4. Soon afterwards, choose **Settings → Report bug → menu → Stop private playback
   trace**, then **Export detailed logs**. Share `full-logs.txt` privately here.

Detailed logs include actual URLs and may include unrelated normal app logs. Never
commit or publish them. They stay on the phone until you share them; no automatic
upload occurs. The new trace records selected playback only, including real request
URLs and delivery headers. It records authorization/cookie presence for HEAD, not
credential values. It does not dump arbitrary headers, bodies or exception messages.
The existing logcat buffer is finite: export promptly after the event. Tracing stops
if the app process dies; enable it again before selecting the target episode.

No PC script is needed for this first step: the URL is entered as a local app parameter.
No private feed requests are made from our servers. Existing Python capture scripts
are not needed for this phone test. Any later script must accept the URL locally.

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
