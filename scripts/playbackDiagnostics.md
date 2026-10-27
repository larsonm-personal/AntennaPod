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
3. If you hear a skip, note the phone's clock time (including time zone), episode,
   player position, whether content repeated or jumped forward, and a few words before
   and after it. Note playback speed and any deliberate seeks, pauses or network changes. Soon afterwards choose **Settings → Report bug → menu →
   Export detailed logs** and share `full-logs.txt` privately here.

Detailed logs contain actual URLs. Never commit or publish them. No automatic upload
occurs, and no private feed requests are made from our servers. No PC script is needed.
Authorization/cookie presence is logged, not credential values. Playback diagnostics
are kept in eight rolling files of up to 1 MB each in private app storage across
process restarts. Oldest data is discarded at the limit; retention depends on request
activity, not a guaranteed number of days. Position events are recorded every five
seconds. At roughly 100 bytes per position event, these events alone use about
1.7 MB per day of continuous playback (MB here means 1,048,576 bytes); requests,
retries and state changes shorten that window. In the earlier local reconnect fixture,
position lines averaged about 78 bytes: about 150 hours for position lines alone.
With per-hop logging, the latest 45.5-second reconnect playback fixture wrote 11,564
bytes; that request-heavy rate would fill the limit in about 9 hours if sustained.
Neither figure is a measured ordinary-listening retention window. This is
a size bound, not a promise of a particular number of hours.
Writes run on a bounded background queue; abrupt process termination can lose pending
events, and storage/queue failures are reported to logcat. Individual lines above
16,384 characters are explicitly truncated. Export promptly after a skip.
Uninstalling or clearing app data deletes them.
Updates with the same package/signature preserve subscriptions and retained logs.

Look for the `PlaybackTrace` log tag. Events begin with milliseconds since trace start.
Retained lines also begin with UTC epoch milliseconds; each process start is marked.
Numeric source identities are session-salted, with real URLs logged separately to
correlate them. The trace includes:

- `SOURCE`: source identity and local-file flag; `source` gives its actual URL/path.
- `HEAD`: request number, URL identity, status. `head.N.*` logs each network hop's
  actual method, URL and network-interceptor request headers, including User-Agent,
  Accept-Encoding and range headers. Response headers include redirect Location;
  Authorization, Cookie and Set-Cookie values are replaced by presence markers. A cached HEAD may have no network
  event; `RESOLVE` still records the result. `HEAD_FAILURE` means transport failure.
- `RESOLVE`: original identity, resolved identity, resolver-cache-hit flag; corresponding
  `resolver.*` fields retain both actual URLs.
- `OPEN`: request number, URL identity, Media3 HTTP method, offset, requested length,
  original source identity.
  `get.N.*` retains the initial/final URLs, configured effective User-Agent,
  Media3-generated Range and Accept-Encoding, DataSpec If-Range/Icy-MetaData, and selected
  response headers including Content-Range, Content-Length, ETag and Last-Modified.
  DataSpec headers are logged separately from generated headers that can override them.
  HTTP error responses retain status, response URL and headers; transport errors retain
  their exception class. Cookie and authorization values are not recorded.
  The summary fields are derived from Media3 configuration; the per-hop observations
  below record what each actual connection exposes before connecting.
- `get.N.hop.H.*`: each connection attempted by the original Media3 redirect loop.
  `url` is the actual next URL passed to the connection; `method`, `hasRequestBody`,
  `automaticRedirects` and `configuredRequest.*` are read immediately before its
  original connect call. `status`, `responseUrl` and `response.*` follow the original
  response-code call. Raw Location and the next hop's absolute URL preserve relative
  resolution/rewrites. Errors retain the hop and exception class. Header values are
  JSON arrays, preserving duplicate values; credential values remain presence markers.
  These are connection-API observations, not a wire capture: Android can add headers
  such as Host, Content-Length, Connection or Cookie internally during connect, and
  authentication/proxy/TLS internals are not observed. No connection is opened early.
- `RESPONSE`: request number, status, open length, final URL identity, validator identities.
- `RANGE`: request number, first byte, last byte, total; -1 means malformed/unsupported.
- `INPUT`: request number, upstream input bytes during open, delivered bytes (zero).
  For a Range request answered with 200, Media3 reads and discards the requested offset
  before delivering content; these skipped bytes appear here.
- `READ`: request number, cumulative delivered bytes, EOF flag, upstream input bytes
  (each MB or EOF). `CLOSE`: request number, delivered bytes, upstream input bytes.
  `FAILURE`: request number, status or -1, delivered bytes, upstream input bytes.
  Input counts come from Media3's transfer listener: they include internal skips,
  but are not wire-byte totals (TLS, HTTP headers, error bodies and compressed-wire
  sizes are not counted).
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

## Replaying the observations

The logs support building a timed Python request sequence: original URL, HEAD
resolution, resolved URL reuse, GET method/ranges, configured headers, delays,
response validators and delivered byte counts. Debug-only instrumentation now records
all connection attempts in Media3's redirect loop, including method/header changes
visible to the implementation and HTTP error responses. Automatic platform redirects
are disabled by the app's existing Media3 configuration; the logged flag confirms this.
Platform-added headers and internal authentication/proxy exchanges remain outside this
capture. Cookie and authorization values are deliberately not exported by these hooks.

Cache reports give aggregate cached bytes and network request offsets, not a full
byte-by-byte map of cache reads. Neither a smooth player clock nor a DISCONTINUITY
event identifies the actual decoded words. The listener's time/content notes locate
the suspected skip; downloaded bytes and decoded-content comparison are still needed
to demonstrate content continuity or loss.

A later local Python run can try the observed sequence and capture new responses.
It cannot restore the provider's historical ad state, IP/session assignment, expired
signed URLs, omitted credentials or the app's previous cache. No replay should be
claimed equivalent merely because its request timestamps or lengths match.

## Auditing the dependency instrumentation

Only debug app variants apply the Android Gradle Plugin's ASM instrumentation to
`androidx.media3.datasource.DefaultHttpDataSource`. Four existing virtual call sites
(two `connect`, two `getResponseCode`) become static observers that invoke the original
method exactly once. The redirect loop, URL resolution, status policy, body writes,
header configuration, reads, skips and exceptions remain in the dependency. Observers
are scoped by a thread-local playback request, so unrelated uses are not logged.
The observer implementation lives only in `app/src/debug`; release variants do not
register the transform. No global URL handler, replacement HTTP stack or reflection
hook is installed.

Pinned upstream: `androidx.media3:media3-datasource:1.10.0`, Apache-2.0,
[DefaultHttpDataSource source](https://github.com/androidx/media/blob/1.10.0/libraries/datasource/src/main/java/androidx/media3/datasource/DefaultHttpDataSource.java).
Published sources.jar entry SHA256: `8bc990dfdebb208b8b7b0b9ddfe6be885a6f83a162dac0d59c7b8f80910b882c`.
Published AAR class SHA256: `046e9eaa9b6924eea1d0c635514c2999e1f8c7f2daf9cac01723a995512dc990`.
Each debug pre-build checks the actual resolved dependency version and class hash.
The transform rejects a changed call-site count. Its unit test reverses only the four
substitutions and requires byte-for-byte equality with normalized original bytecode.
This intentionally fails closed on dependency changes and requires re-auditing when
Media3 changes. Build logic uses the project's AGP 9.0.1 and Kotlin API 2.3.20 versions.

Run `./gradlew --console=plain :buildSrc:test` for the mechanical audit. The Android
`PlaybackDiagnosticsTest` compares observation-disabled and enabled requests, response
bytes and errors against the same local HTTPBin server, including POST-to-GET redirect
handling and Range-to-200 skipping. This is not a live-provider reproduction.
