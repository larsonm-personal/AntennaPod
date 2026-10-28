# Playback across resolved URLs

`ResolvedEpisodeServer` starts with the original endpoint resolving to A, waits for
an explicit signal from the test, then switches fresh resolutions to B. A and B keep
their own bytes throughout. There is no rotation timer. Response throttling is a
separate setup action: after 512,000 bytes of each GET response, at most 1,024 bytes are sent per 200 ms.
Releasing it continues the body, without a manufactured EOF.

The fixtures contain the same continuous frequency sweep, with a synthetic segment
at program second five. B has two seconds there; A has an additional 2,299 MP3 frames
(60.055510204 seconds). The files use 192 kbps, 44.1 kHz MPEG-1 layer III. Placement
and a single shortened segment are assumptions, not recovered server behavior.

The additional `resolved-equal-length` fixture has B's two-second opening segment
and a 60.055510204-second closing tone after the program. It has exactly A's byte
length and frame count while matching B's program at the current observation
and seek targets. This relocated segment is a synthetic robustness fixture, not
an inference about the captured server. Generation checks encoded length; full
frame/decode checks and two clean generations also verified unchanged A/B bytes.

Optional server controls are separate from the default immutable A/B scenario.
`replaceAWith(file)` changes the bytes served at A, including same-length data
without validators. `redirectGetsFromAToB()` keeps HEAD A unchanged while GET A
redirects to B, including ranged requests. This exercises HEAD/GET disagreement
at the resolved endpoint; merely redirecting GET at the original enclosure would
not exercise a client that already replaced that URL after HEAD resolution.
The bundled NanoHTTPD 2.1.1 retains request headers on keep-alive connections.
This server removes each consumed Range header so a later full request does not
inherit it. A raw-socket test checks both requests on the same connection.

The first production player seeks to 70 seconds, after the synthetic segment, then
consumes 17 seconds before cancellation. The recreated player resumes at 87 seconds.
This avoids playing a minute of setup audio while retaining real cache population.
The added cases give setup 30 seconds to reach the server prefix and a complete
actually consumed PCM window. Only then does the unchanged 30-second observation
phase begin, still requiring consumption through 87 seconds. The prefix alone can
come from the initial header request before the seek load is ready. The frozen
restart regression retains its original setup waits and continuous assertion.
Before cache recreation, the test pauses, deselects audio, and waits for its active
media loads to finish or cancel. Player release alone can return before the cache
writer closes.
The test verifies every saved span against A and measures the contiguous cached range
containing the resumed position; the initial seek may leave an earlier gap. It walks
MP3 frames to locate the actual boundary and observes consumed audio on both sides.
A missing or complete cache still permits coherent playback. Requests and ranges are
recorded rather than prescribed.

The content assertion accepts a coherent A or B representation. A-before/B-after
windows expose the mixed baseline. The empty-cache comparison checks coherent B
separately: its program position is about 60.0555 seconds ahead of A at the restored
media timestamp. That displacement alone does not prove cache mixing. Stable A and
completed local downloads are controls.

The observer uses Media3's public audio-output message with the unchanged production
player. It forwards to the normal AudioTrack output and reads only the bytes each
write actually accepts, including partial writes. Zero crossings estimate the
sweep frequency and therefore program position. Accepted frame counts plus the
fixed seek start give media time at 1x speed; the real output-relative playback head
filters out unconsumed windows. A flush, release or replacement after PCM invalidates
the observation. Neither an internal renderer offset nor player-position logs form
the audio oracle.

`Media3ServiceRestartTest` checks actual service teardown and resumption from the
final persisted position, including the normal 800 ms rewind. It does not check
persistence accuracy against the controller's position estimate. It waits for
main-thread shutdown and pending database writes before reading the saved position.
Routing is logged. It does not observe consumed service audio or simulate process
death or a Wi-Fi/cellular handoff. The rewind check uses the production short-pause interval.
The position listener is a proxy over the public `Player.Listener` interface: a
separately compiled listener otherwise calls default-method companions removed
from the optimized release APK.

The measured B was 1,441,331 bytes shorter. At two sampled interior locations, three
unique encoded blocks each matched A exactly 1,441,331 bytes later, or 2,299 frames
later on A's MP3 frame clock. The synthetic pair has that frame-count difference and
is 1,441,332 bytes apart; one byte of MP3 padding differs from the observed byte delta.
The real edit location, full B timeline and ending remain unknown. Sparse matches do
not establish a single deletion, its cause, or the user's actual playback transition.
There is no production fix here.

## Additional synthetic cases

The same generator also accepts separate sweep bands: A uses 500–2000 Hz and B
uses 3500–5000 Hz. Within the program sweep, frequency identifies the edition
and program second; these checks stay outside the fixed ad tones.
The tagged observer reuses the zero-crossing estimator and gates accepted PCM
against AudioTrack's public playback-head frame counter. It retains played
windows across flush/reuse and replacement, and discards queued-only windows.
It does not reconstruct the player's media timeline. Stable decoder/seek controls
calibrate a one-second target tolerance; the frozen oracle remains at 0.25 seconds.

The tagged cases check predetermined content samples, not continuous integrity.
After restart they check four complete quarter-second windows at indices 4–7,
and another four starting at `4 * (ceil(cacheEndSeconds - 87) + 1)`. The cache
boundary is measured during priming, before the resumed playback is observed.
For uninterrupted output these are seconds [1, 2) and [b, b+1), where
`b = ceil(cacheEndSeconds - 87) + 1`. After each seek, only indices 4–7 are checked.
Every selected segment must be complete, remain within one output epoch and
edition, and follow the program sweep. Explicit seeks also require the expected
program coordinates. One edition change between sampled segments is allowed;
the fixed replay must stay in the final edition.

Everything outside those selected windows is unasserted: startup, the interval
around the splice, the gaps between observations, and later audio. Traces retain
those measurements, but a pass makes no claim about them. Output replacement can
discard a partial quarter-second window; the selection is by retained window
ordinal, not a global media clock. A segment spanning outputs is a measurement
failure, not permission to search for another segment. If playback explicitly
rejects the source, all available complete prescribed segments are checked;
missing or partial segments remain unmeasured and recovery is not established.
The frozen continuous-content regression retains its original strict assertion.

The seeking case checks integrity before proceeding to each next target, then
repeats the target list once in the final edition. Fixed media targets 80, 110,
and 75 seconds lie inside both files and after both ad segments. The primed A
span covers approximately media 70–95 seconds: 80 and 75 revisit it, while 110
is beyond it. A maps these targets to program 17.944, 47.944, and 12.944 seconds;
B maps them to 78, 108, and 73 seconds. The next seek never depends on an edition
inferred from potentially corrupted audio. Stable controls evaluate both mappings.
The case checks these intervals, not every possible passage or exact resume time.
Same-length and HEAD/GET initial sampled-content passes alone do not establish
subsequent seekability. A separate same-length mutation case now applies the
same fixed seek/replay plan, requiring successful played samples rather than
accepting an error. It checks equal encoded lengths and distinct fixture bytes
before priming; decoded fixture measurements establish the intended different
program coordinates at all targets. HEAD/GET seek recovery remains uncovered.

Tagging changes the program bytes throughout; it is not evidence of the captured
server's layout or a sufficient challenge to overlap-based fixes. The original
shared-program fixtures remain unchanged. The harmless URL-switch control serves
the same existing file under A and B and requires successful coherent playback.
No extra file or generator is needed for identical content.

## Eviction and expired URLs

The eviction cases fill the unchanged production 100 MB LRU cache by preparing
26 separate local episode URLs through production players. Each serves the same
existing A file (about 4.17 MB); players buffer it fully without playing minutes
of filler audio. No spans are inserted or deleted by the test during pressure.
After the fixed sequence, both original span files must be absent and remaining
cache files must occupy 95–100 MB. This setup depends on the current Media3 cache
format and directory, but does not require any particular cache key. A different
cache architecture would need a separately reviewed setup adapter.

Stable A and fresh B then undergo the same fixed sampled seek targets and replay.
These are complete eviction across multiple episodes, not partial eviction or a
single episode larger than the cache. The local transfer cost is about 108 MB per
case; no larger generated fixture is needed. On the API 30 software emulator,
pressure takes roughly 1.5–3 minutes per case.

The expiry case makes A return 403 for HEAD and GET while the original endpoint
resolves to B. It restarts the player with partial A still cached, then requires
played samples and successful seeks. Errors cannot satisfy this recovery case.
Expiry is an additional synthetic behavior, not established by the live captures.
This checks restart with A unavailable, not recovery from a received expiry
response. In the measured baseline, restart resolves B without requesting A;
the failure follows the existing cached-A/fresh-B mechanism. The additional
constraint challenges a future fix that pins unavailable A. Automatic
re-resolution after an actual expiry error remains separate coverage.

The older selected-edition seek experiment is now invoked only by
`ResolvedPlaybackResearchTest#previouslySelectedEditionMatchesLaterSeeks`, which
is excluded from CI via `IgnoreOnCi` and from the explicit acceptance collection.
It preserves the original experiment, including its requirement for output
replacement and its rejection of a later whole-edition switch. Its failure is
research evidence, not a neutral acceptance requirement.

## Build and run

Instrumentation builds require Python 3 and FFmpeg with libmp3lame on PATH; no tools
are installed automatically. Use `-PfixturePython=python` or an executable path if
Python 3 is not named `python3`. Ordinary app builds do not generate these fixtures.
The generator was checked with Python 3.12.14 and FFmpeg 7.1.5: two clean generations
produced identical bytes. Encoder versions may change the bytes. After changing
tools, run `./gradlew :app:generatePlaybackFixtures --rerun-tasks`.
MP3s are generated under the ignored `app/build/generated/playbackFixtures` directory.

```sh
./gradlew :app:assembleDebug
./gradlew :app:assemblePlayDebugAndroidTest
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
adb install -r app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk
adb shell am instrument -w -r -e class de.test.antennapod.playback.ResolvedUrlPlaybackTest de.danoeh.antennapod.core.tests/androidx.test.runner.AndroidJUnitRunner
```

Use `Media3ServiceRestartTest`, `ResolvedEpisodeServerTest`, or
`ResolvedTaggedAudioTest` in the same runner
command for the lifecycle and HTTP checks. The baseline mixed-representation case
should fail at its content assertion. Setup, coverage, clock or cleanup failures
are not successful reproductions. The test is not yet frozen for patch evaluation.

CI prints at most 200 added diagnostic lines per emulator job, with API/build labels
and the artifact name beside each attempt's excerpt. Full logcat, Gradle output and
report snapshots are retained under `ci-emulator/attempt-N/` in
`test-report-api-<API>-<debug|release>`, including failed attempts followed by success.
Find it under the workflow run's **Artifacts** section; downloads require GitHub sign-in.
The existing three-attempt retry policy remains; persistent content failures fail CI.
Matrix jobs finish independently so one failure does not cancel the other devices.
Logcat is captured throughout each attempt, before instrumentation can clear its buffers.
Release instrumentation builds (`-PtestBuildType=release`) retain the Media3 APIs
used only by the tests via `app/src/androidTest/proguard.cfg`; ordinary release
builds do not load those rules.
