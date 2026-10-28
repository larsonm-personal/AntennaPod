# Observed server behavior and the next local characterization

This is an evidence-collection specification, not a claim that dynamic ads caused the
reported skip. The existing four-second synthetic repetition test remains a separate
mechanism demonstration. It is not a validated model of the private provider.

| Status | Evidence or question |
| --- | --- |
| Observed in the supplied clean app trace | Initial HEAD 302 → HEAD 200; three GETs reuse the exact resolved URL. Later Range requests follow explicit seeks. No network-error retry was captured. |
| Observed in the supplied overnight HEAD journal | 32 checks: 30 successful final 200 responses and two timeouts. The original URL and resolved host/path stayed constant; resolved query values changed. |
| Observed in those successful HEAD responses | Declared lengths: 127,079,742 bytes in 23 checks and 125,638,411 bytes in seven. Two values of a query parameter tracked those two lengths in this finite sample. Other session/request/time values changed routinely. No ETag or Last-Modified was supplied. |
| Previously reported capture result | The earlier network-switch captures of the longer representation were byte-identical; the listener confirmed a complete ending. This does not characterize the shorter representation or subsequent provider state. |
| Observed in the subsequent supplied characterization | Fresh original-URL HEAD resolutions 903 seconds apart selected the two lengths above. The exact first resolved URL retained its length, did not redirect, and returned identical bytes at all three sampled intervals after that delay. Fresh-resolution interior MP3 samples differed. The identical initial 64 KB was inside a 357,863-byte ID3 artwork tag, not audio. |
| Observed in the subsequent user-run offline alignment | All three saved baseline A windows and total length match the existing full A. At two interior B locations, three unique 4,096-byte blocks per location match A 1,441,331 bytes later. A frame indexing gives 2,299 frames, or 60.055510204 seconds, of displacement at both locations. FFmpeg was unavailable; this is encoded correspondence, not a decoded comparison. |
| Unknown | Stability outside that finite observation, expiry, HEAD/GET differences, and full program alignment across representations. |
| Unknown | Whether the two lengths correspond to differing ad arrangements, underlying program changes, metadata differences or some other representation change. The correlated query value's meaning is not established. |
| Supported finite model | Fresh resolution can select differing bytes while the previously resolved URL remains stable at the measured intervals. Ad timing and whole-file identity remain unmeasured. |
| Alternative hypotheses to test | Bytes behind a pinned URL change; URL expiry changes behavior; metadata changes without sampled-byte changes; sampled bytes change without metadata changes. |

No private URL, token value, audio or raw log belongs in this repository.

## Run the new bounded test

Windows Command Prompt, from the existing clone:

```bat
git pull --ff-only
py -3 scripts/characterizeEpisodeServer.py --trace-log "%USERPROFILE%\Downloads\full-logs (1).txt" --observations "%USERPROFILE%\Downloads\route-watch-1\events.jsonl" --output "%USERPROFILE%\Downloads\server-characterization-1"
```

Adjust local filenames. Alternatively use `--prompt-url` instead of `--trace-log` for
hidden **episode audio URL** entry. `--observations` is optional: without it, only
fresh HEAD length changes trigger early comparison. When supplied, observations must
refer to the same original URL. The tool identifies query keys with one distinct
value per observed length and distinct values across lengths, requiring at least two
observations of each length; volatile session keys
are not selected merely because each individual token happened only once. That is
an empirical correlation, not a declaration that a key identifies audio content.

The sequence is:

1. Resolve the original URL by HEAD. Preserve the exact final URL as the pinned URL.
2. From that URL, GET three diagnostic windows: the beginning, halfway byte offset,
   and three-quarter byte offset, each up to 65,536 bytes. Offsets derive from the
   baseline HEAD length and stay fixed throughout the run.
3. Every 15 minutes, resolve the original URL afresh by HEAD. Stop monitoring when its
   length or an empirically correlated key pattern changes, or after at most eight
   hours/up to 32 total baseline-plus-fresh resolutions. Routine token churn alone is
   not a trigger. Errors remain in the journal; unsuccessful polls do not replace the
   latest successful fresh resolution.
4. Recheck the **unchanged pinned URL** by HEAD. If successful, read its same three
   windows again. Also read those windows from the latest successful fresh resolution's
   exact URL, with no silent refresh or replacement. At the monitoring limit this may
   be the last successful observation, not a newly resolved URL; its record number and
   timestamp are explicit. If no later HEAD succeeds, that candidate remains baseline.
5. Compare SHA-256 only for validated responses covering the same absolute interval.
   Results separate baseline-vs-pinned, baseline-vs-fresh and pinned-vs-fresh.

These short, separately requested windows are **diagnostic sampling**, not an app
resume replay. No full stream, stitched MP3, additional overlap request, or decoded
content oracle is produced. Each window can encounter different server state; HEAD
and subsequent GET need not select the same representation. All actual redirects,
final URLs and timestamps are retained so these possibilities remain visible.

A coherent 206 must cover the exact requested interval, with consistent body length,
identity encoding and a valid total. Malformed ranges, short bodies, timeouts and
416 responses are retained but excluded from byte comparisons. A Range→200 response
is read only to the small sample cap and labeled an ignored-range prefix; it is never
compared as if it covered the requested offset. There is no padding or forced splice.
A failed pinned HEAD prevents pinned samples. A 404 may therefore leave those
comparisons unknown while the separately scheduled fresh candidate is sampled.
401, 403 and 429 stop the entire run immediately: no substitute URL, identity change,
automatic retry or expiry bypass. Transport failures are recorded without immediate
retries; later scheduled HEAD polls are distinct observations.

## Bounds and private output

Defaults are **96 HTTP request attempts including redirect hops**, **1 MB of response
body bytes read**, and three 64 KiB windows per phase (nine samples in the successful
case: about 0.56 MB). One extra byte per sample may be read to detect oversized
responses and counts toward the payload budget. Redirect/error bodies are closed
without capture. HEAD reads no body. This is an application-payload cap, not a limit
on TLS, HTTP headers, socket buffering, or bytes a noncompliant server sends.

`--hours` accepts greater than zero through 24; `--interval` is 60–3600 seconds;
`--window-bytes` is 1–262144; `--max-requests` is 1–256; `--payload-bytes` is 1–8388608;
`--timeout` is 1–60 seconds (default 20). MB means 1,048,576 bytes. A budget exhaustion
stops before further requests rather than silently raising the limit. The duration
bounds the monitoring phase; baseline/final samples and in-flight operations add
bounded work. Socket timeouts and elapsed-time checks constrain sample reads; these
are not an exact wall-clock deadline for OS DNS/proxy behavior. Redirect chains are
limited to five hops. Keep the PC awake; no network or power settings are changed.

The new private directory contains append-only, flushed `events.jsonl` and numbered
raw sample files. The journal includes pre-send request events (also useful if a check
is interrupted), URLs, headers/Locations, timing, declared lengths/ranges, body counts,
hashes, budgets, trigger reason and comparison results. Existing output directories
are never overwritten. Ctrl+C retains completed and partial evidence. Console messages
do not print URLs or tokens. The app's observed User-Agent/HEAD/GET header profile is
used consistently, but Python's HTTP/TLS/connection/proxy behavior is still different
from Android. There is no cookie jar or credential replay.

## How results refine the integration model

- Different fresh windows with stable pinned windows support a model separating fresh
  resolution from pinned URL use. They do not support forced mutation on every retry.
- Different bytes from the same requested pinned URL support investigating mutation or
  a changed redirect destination; consult actual final URLs and exact response ranges.
- Expiry/errors require an explicit expiry/error state in a future model, including the
  app's measured reaction. Do not replace them with an imagined successful new stream.
- Stable sampled bytes with changed metadata establish only sparse byte agreement.
  They do not prove complete representation identity, continuous program audio, or a
  complete ending. Content alignment requires a later, separately authorized capture.
- Unchanged HEAD metadata with changed bytes demonstrates why headers alone are not
  an audio identity oracle. Conversely, a metadata change alone is not a content skip.

Fixture modes cover each alternative independently: stable pinned data, fresh-only
switch, pinned mutation, mutation without metadata change, expiry/access denial,
ignored ranges, wrong/truncated ranges, timeouts and budgets. These are candidate
behaviors, not fitted probabilities or asserted real-server transitions. Session
stickiness, expiration lifetime, network dependence and stochastic transition rates
remain unknown until measured. The app integration refinement below uses only the fresh-selection/stable-resolved-URL model. No product fix is included.

Local validation: `py -3 scripts/testCharacterizeEpisodeServer.py`.


## Streaming lifecycle investigation (3.12.3)

The active release path is Media3, enabled in `common.gradle`, not the legacy
LocalPSMP service. In official commit `175e5ef8fe3e125a5014d70ef0c57cf85661c71b`:

- [ExoPlayerUtils, lines 44–76 and 110–185](https://github.com/AntennaPod/AntennaPod/blob/175e5ef8fe3e125a5014d70ef0c57cf85661c71b/playback/service/src/main/java/de/danoeh/antennapod/playback/service/internal/ExoPlayerUtils.java#L44)
  creates a per-player media-source factory with an in-memory original-to-resolved URL
  map. An upstream cache miss resolves by HEAD once per original URL in that factory.
  Seeks, load retries and pause/resume using the same player keep this map. There is
  no TTL or network-change invalidation in it. GET redirects can still occur later;
  retaining the request URL is not a guarantee of immutable server content.
- [RedirectChecker, lines 57–69](https://github.com/AntennaPod/AntennaPod/blob/175e5ef8fe3e125a5014d70ef0c57cf85661c71b/net/common/src/main/java/de/danoeh/antennapod/net/common/RedirectChecker.java#L57)
  returns the final HEAD response URL, including HTTP error responses; IOException
  falls back to the original URL. This resolver does not rewrite the episode database.
- [Media3PlaybackService, onCreate and onDestroy](https://github.com/AntennaPod/AntennaPod/blob/175e5ef8fe3e125a5014d70ef0c57cf85661c71b/playback/service/src/main/java/de/danoeh/antennapod/playback/service/Media3PlaybackService.java#L472)
  builds the player on service creation and releases it/cache on destruction. Player
  recreation or process restart loses the resolution map. A stop command alone need
  not destroy the player. Exact Android service-destruction timing remains untested.
- [MediaLibrarySessionCallback, lines 336–376](https://github.com/AntennaPod/AntennaPod/blob/175e5ef8fe3e125a5014d70ef0c57cf85661c71b/playback/service/src/main/java/de/danoeh/antennapod/playback/service/internal/MediaLibrarySessionCallback.java#L336)
  restores the stored media time, with configured skip/rewind adjustments. It does
  not translate that time using the old and new ad arrangements.
- The 100 MB disk streaming cache outlives the factory unless evicted/cleared.
  Media3 1.10.0 [CacheKeyFactory](https://github.com/androidx/media/blob/1.10.0/libraries/datasource/src/main/java/androidx/media3/datasource/cache/CacheKeyFactory.java#L26)
  keys it by the original URI. Stored redirected-URI metadata does not replace the
  original upstream request URI in [CacheDataSource.openNextSource](https://github.com/androidx/media/blob/1.10.0/libraries/datasource/src/main/java/androidx/media3/datasource/cache/CacheDataSource.java#L733).
  Thus cached spans and a new factory's resolution deserve separate observation;
  source inspection alone does not establish that a particular run spliced them.

These lifecycle files and RedirectChecker are unchanged between that release and our
upstream baseline `d3e41c6cb37c8e1a060b142686f818999de89859` (Media3 1.10.0 in both).
Our diagnostic instrumentation adds observations, not a routing policy. No source
path found here establishes that switching Wi-Fi to cellular itself recreates the
player or re-resolves the original URL.

### Download comparison

[MediaItemAdapter, lines 86–104](https://github.com/AntennaPod/AntennaPod/blob/175e5ef8fe3e125a5014d70ef0c57cf85661c71b/playback/base/src/main/java/de/danoeh/antennapod/playback/base/MediaItemAdapter.java#L86)
selects an available local file instead of the streaming URL. A completed consistent
file therefore keeps its representation across playback restarts. This fits the
reported downloads-unaffected clue, without proving that all downloads are immune.
[HttpDownloader](https://github.com/AntennaPod/AntennaPod/blob/175e5ef8fe3e125a5014d70ef0c57cf85661c71b/net/download/service/src/main/java/de/danoeh/antennapod/net/download/service/feed/remote/HttpDownloader.java#L90)
resumes partial files using Range and uses If-Range only when a stored validator exists.
An interrupted download without validators is a separate risk to investigate.
Baseline changes in this downloader concern disk/error handling, not that routing or
resume policy.

### Minimal integration refinement

`DynamicAdPlaybackTest` and the existing `HTTPBin` now use `/resolved-ad/original`
redirecting to stable `/A` or `/B`. The existing synthetic sweep assets have two- and
six-second ads; these durations are experimental, not measured provider values.
Initial full GETs are throttled to one byte per 200 ms after 320,000 bytes so buffering cannot consume the whole
asset before the lifecycle boundary. The player consumes through 17 seconds, stops,
and its player/cache objects are released. Releasing the gate then ends that transfer; the trickle avoids an artificial read timeout before the stop. A new
production player restores 17 seconds using the original media URI. This exercises
production networking, cache, seek mapping and consumed PCM, but does not invoke
Android service destruction, OS process death, database restoration, automatic
rewind, or a real network handoff. The explicit 17-second position represents the
stored-position boundary, not a simulation of all service behavior.

Controls retain resolution A or complete a download with the real HttpDownloader
before playing its local file after the server's fresh selection changes. The
changed-resolution continuity assertion remains strict; a failure documents content
time differing from the original program at the restored position. Request logs
must distinguish a fresh representation from a demonstrated mixed-cache splice.
The older `changedAdReconnectPreservesContent` still forces same-URL mutation and
remains a separately conditional mechanism test, not the observed provider model.


Measured API 30 software-emulator result: after consuming A and recreating the
player, the request sequence was HEAD original → HEAD A → GET A, then HEAD original
→ HEAD B → GET B `Range: bytes=307200-1952704`. The retained end offset belongs to A;
B is longer. Consumed PCM initially matched A (media 18.881 s, program 16.868 s),
then repeated content (media 19.381 s, program 13.368 s). Maximum content-time error
was 4.013112 seconds. The player timeline continued forward. This supports a cached
A-prefix/new B-suffix splice in this synthetic lifecycle test, not mutation behind
one resolved URL. The exact cached boundary depends on read/stop timing.

The first exploratory restart ran before the initial player consumed audio and
fetched a fresh B response; it showed restored-time mismatch but did not establish
splicing. A second setup stalled long enough to trigger a same-player retry and
fully cache A; its fresh-resolution precondition failed. The final throttled setup
avoids both confounders. These setup failures are not evidence of a provider defect.

The remaining real-world question is whether the user's audible skip follows a new
player/resolution with reusable cached spans, some other re-resolution path, or a
different mechanism. The supplied clean app trace and sparse server samples do not
establish that lifecycle trigger. Downloads being unaffected is consistent with the
local-file control, but is not sufficient to select this cause by itself.


Run individual cases after building/installing the debug app and Android test APK:

```sh
adb shell am instrument -w -r -e class de.test.antennapod.playback.DynamicAdPlaybackTest#stableResolvedUrlAcrossPlayerRestartPreservesContent de.danoeh.antennapod.core.tests/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class de.test.antennapod.playback.DynamicAdPlaybackTest#freshResolutionAfterPlayerRestartPreservesContent de.danoeh.antennapod.core.tests/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class de.test.antennapod.playback.DynamicAdPlaybackTest#completeDownloadKeepsContentAfterFreshResolutionChanges de.danoeh.antennapod.core.tests/androidx.test.runner.AndroidJUnitRunner
```

The changed-resolution case deliberately retains the failing continuity assertion;
its red result is not a green regression test or a product fix. Do not interpret a
passing setup assertion or a forward-moving player position as content continuity.

### Empty-cache comparison

The same A playback followed by cache deletion and fresh B resolution also fails
continuity. In the API 30 run, requests were HEAD original → HEAD A → GET A,
then HEAD original → HEAD B → GET B without Range → GET B
`bytes=271884-2016652`. The first resumed window was already about four seconds
behind A (maximum error 4.055515 s; subsequent windows about 4.013 s).
Setup and the consumed-PCM flush guard passed.
All 40 resumed windows matched B's specified layout within 56 ms after accounting
for its four extra ad seconds. B playback itself was consistent in this comparison.

With retained cache, the resumed windows first follow A, then switch to B and
repeat content. Without cache, they follow B immediately. Thus the within-playback
backward transition supports byte mixing in this fixture, but the four-second
position mismatch alone does not: restoring A's timestamp in B is sufficient.
An empty cache is not a content-position mapping policy.

### What is still missing from the server evidence

The measured alternate was shorter: 125,638,411 versus 127,079,742 bytes. Our
synthetic B has four additional seconds of ad before otherwise identical program
audio. That insertion, its location and constant bitrate are assumptions; switching
which fixture is shorter would not establish the measured audio relationship.
The observed 903-second interval brackets two fresh resolutions, not a measured
server expiry timer. The fixture selects B explicitly and keeps both resolved URLs
accessible; it does not simulate a fifteen-minute timer.

No private captures are available in this checkout. To resolve alignment, capture
both complete resolved representations on the listener's machine, keeping each
request pinned to its exact resolved URL. Retain response/range metadata privately,
decode both locally, and align common spoken-program landmarks before and after
each differing segment and near the ending. This determines whether bytes changed
because of metadata, encoding, inserted/removed audio, or different program content.
If complete files are impractical, overlapping decoded samples around the reported
skip can establish local alignment, but cannot establish whole-episode equivalence
or a complete ending. Share only the alignment offsets, decoded durations and a
redacted lifecycle/request timeline; URLs, tokens and audio need not enter Git.
The existing offline analyzer measures duration but does not yet compute program
alignment. A separate app trace around the audible fault must establish whether
service/player recreation or fresh resolution actually occurred.

### Minimal review extraction

`playback-resolution-review` starts at upstream base
`d3e41c6cb37c8e1a060b142686f818999de89859`. It retains the resolved-URL server,
two generated fixtures, consumed-PCM oracle, completed-download control and
service lifecycle checks. The production change is only a renderer-factory
overload for the observer. Diagnostic tracing and the forced same-URL mutation
experiment remain on the research branch.

The absolute A-program-position assertion is deliberately still strict in the
empty-cache comparison. Its failure is a finding about timestamp restoration,
not a requirement that a cache-only change must satisfy. The test is for review,
not frozen for evaluating a proposed fix. PR #8441 has not been inspected.

The published review snapshot is `9869c92fb6bb67002bee1ffb6340c2bfb5f1bb20`.
On API 30 AOSP ATD with software emulation and package AOT compilation, its
stable restart and completed-download controls passed (maximum error 0.013130 s).
Retained-cache B failed at decoded content by 4.013112 s; empty-cache B failed
there by 4.055515 s. Both passed the request and consumed-PCM flush checks.
The debug app and instrumentation APK built, and focused Checkstyle passed.
Service lifecycle validation is pending; earlier attempts timed out before the
routing assertions and are not evidence of a content fault.

### Service harness findings

The real service also fetches the media file to discover chapters. That request
can arrive before the player's GET, so gating only the first GET does not ensure
a partial playback cache. The server now gates all initial full GETs until release.
Chapter probes can close their streams normally; the playback response remains
bounded. With that change, the pause/resume routing assertions reached one HEAD
resolution and a later A range request.

That run still timed out in teardown. Bluetooth was disabled in the emulator;
`dumpsys activity services` showed no bound client, but the service had been
started again. The next harness revision waits for the production paused status
before releasing its controller and stopping the service. No content assertion
is used as evidence of service teardown.

With the paused-status wait, the service restart case passed on API 30. The first
service saved 18,064 ms; the new service invoked `onPlaybackResumption`, restored
17,264 ms (the normal 800 ms rewind), and advanced to 21,164 ms. Playback performed
a fresh HEAD resolving to B and requested B `bytes=276054-1952704`. Chapter probing
also made separate full GETs. This verifies one explicit service teardown/resumption
path, not process death, network handoff, or the reported user's trigger. The
service test checks routing and stored media time; the separate player test
observes consumed PCM.

The corrected pause/resume case also passed, including teardown. Both service
cases ran with Bluetooth disabled on the software API 30 AOSP ATD emulator.
The earlier failures remain setup/harness findings, not decoded continuity
failures. The original service snapshot is preserved in review commit
`9869c92fb6bb67002bee1ffb6340c2bfb5f1bb20`; its first-GET gate and immediate
controller release are superseded by these corrections.

### Validation of the updated review snapshot

Review head `1eea1c76dfde955c81e9d3a1d5f44bb17716905b` contains the harness corrections.
The repeated retained-cache and empty-cache cases reached the strict content
assertion again, with errors 4.013112 s and 4.055515 s respectively. The repeated
completed-download control and a warm stable-restart run passed at 0.013130 s.
One preceding stable run timed out before consuming its first 17 seconds; its log
recorded AudioTrack stalls and a 10.201-second emulator livelock sample. That
attempt did not reach the content oracle and is not a successful reproduction.
No timeout or content tolerance was relaxed. Both service cases passed, including
shutdown. Hardware-accelerated or device validation remains useful before treating
the timing as reliable across environments.

The debug app and instrumentation APK built successfully. Final focused Checkstyle
passed on both branches, including the renderer-factory overload; the research
instrumentation APK also rebuilt after the harness corrections.

The review branch is for critique, not a frozen submission test. No final third
branch or PR #8441 comparison has been created.

### Review corrections: source-only fixtures and a representation oracle

The MP3s are no longer tracked in either branch's current tree. Android test assets
are generated from source through the Android variant API; app-only builds do not
run the generator. A detached clean checkout of `5c473b5f803c991239b7d919b48ddd68e8e850f5`
built its instrumentation APK and packaged the generated assets. Two generations
with Python 3.12.14 and FFmpeg 7.1.5/libmp3lame were byte-identical to each other and
the earlier fixtures. A missing-FFmpeg check failed before creating output. Older
published history is preserved. The original forced-change demo remains documented
in `app/src/androidTest/fixtures/dynamic-ads/legacy-reconnect.md`.

The refined test uses unchanged production `buildPlayer(context)` and Media3 1.10's
public audio-output message. ForwardingAudioOutput observes only accepted PCM bytes
and the real output-relative playback head; the fixed seek start maps the single
1x stream to media time. Flush, release and output recreation after PCM invalidate
measurement. No reflection or renderer-offset constant is used. Against the prior
absolute-A oracle, stable/download controls still passed and both changed-resolution
cases still failed at content. The new overload is removed from the review branch;
the research branch retains it for the older experiments.

`ResolvedEpisodeServer` lays out A resolution, a test signal wait, then B resolution.
Both resolved endpoints remain immutable. Flow control is separate: after 320,000
bytes, up to 1,024 bytes per 200 ms until released. Release continues the full body;
the client cancels its own first transfer. Independent HTTP tests verify 302 HEAD/GET
resolution, immutable A/B bytes, ranges, EOF/416 and complete bodies after release.
Those tests caught and corrected NanoHTTPD's 301 enum and the stream wrapper's missing
`available()` implementation before playback results were used.

The test reads actual cache spans, compares their bytes with A, and walks the tagless
MP3 frames to measure the boundary. It records requests rather than requiring exact
GET counts or a B range ending at A's length. A coherent A or B stream can satisfy
the retained-cache test, without requiring a repair to translate timestamps.

In the first revised run, cached A covered 418,377 bytes, ending at frame time
26.148571 s. The failed content assertion had 35 A-matching windows before that
boundary and nine B-matching windows after it. Errors against either whole layout
exceeded four seconds. Stable A and completed download passed within 15 ms. The
empty-cache B comparison passed within 65 ms of B, while retaining its approximately
four-second displacement from A. All coverage and output-discard guards passed.

Both service cases passed. Restart saved 17,410 ms and restored 16,610 ms: exactly
the production 800 ms rewind, with an explicit less-than-one-minute pause condition.
Service routing remains logged rather than prescribed. These checks do not observe
consumed service audio, process death or network handoff. Cache deletion follows
confirmed service shutdown; failed shutdown invalidates the run rather than permitting
a new test to reuse an active cache. The live shorter-B alignment gap is unchanged,
and no PR #8441 patch has been inspected or evaluated.

Review snapshot `a2135d4ab7179952a22d895aeddb00e2ed0e9971` contains seven changed
files: the test asset build task, fixture source/readme, and four instrumentation
classes. It has no production Java or shared HTTPBin changes. Debug and
instrumentation builds and focused Checkstyle passed; an app-only task-graph
assertion confirmed that fixture generation was absent. The research instrumentation
build and focused Checkstyle also passed. This remains a review snapshot, not a
frozen test or a final submission branch.

A repeat at that review head passed all three HTTP checks and again failed only
the mixed-content assertion. This time the actual cached prefix was 440,111 bytes
(frame boundary 27.506939 s), with 40 A-matching windows before and nine B-matching
windows after; whole-layout errors were 4.007354 s against A and 4.014972 s against B.
The changed boundary did not change the outcome. Setup, coverage and output-discard
guards passed again.

### Shorter B: local correspondence and a candidate fixture

The user-run alignment supplied the missing local direction. B samples starting at
63,539,871 and 95,309,806 bytes match A at 64,981,202 and 96,751,137 bytes respectively.
Each has three unique 4,096-byte blocks at sample offsets 2,048, 30,720 and 59,392,
all agreeing on the displacement; six complete MP3 frames fit in each first block.
At the first location A's frame clock changes from 2,632.568163 to 2,692.623673 seconds;
at the second it changes from 3,956.323265 to 4,016.378776 seconds. Both differences
are 2,299 MPEG-1 frames at 44.1 kHz (60.055510204 seconds). The byte displacement equals
the measured total-length difference. These observations support later A content at
those B offsets, not a single deletion, a known ad boundary, or complete B equivalence.

The refined fixture uses a synthetic two-second segment in B and an additional
2,299 frames in A, at program second five. It is encoded at 192 kbps and 44.1 kHz.
A has 6,971 frames and 4,370,390 bytes; B has 4,672 frames and 2,929,058 bytes. The
1,441,332-byte difference is one byte larger than measured, due to MP3 padding. The
frame displacement matches the local evidence exactly. A single shortened segment
and its location remain explicit construction assumptions. The original opposite-
direction experiment remains in DynamicAdPlaybackTest and the legacy reconnect notes.

The production player initially seeks to 70 seconds, plays 17 seconds, then is
recreated at 87 seconds. That declared seek avoids a minute of otherwise unused
setup audio; it does not preload cache files or bypass production loading. Response
throttling applies after 512,000 bytes of every GET, including ranged GETs. The cache
check verifies all retained bytes against A and measures the contiguous span covering
the resumed frame offset, allowing an earlier gap caused by the seek. The HTTP check
also verifies that releasing a throttled ranged response delivers its complete body.

A four-second reverse-direction trial passed all audio controls and failed only at
mixed content (35 A windows, nine B windows), but it is not the final candidate: the
measured magnitude could interact differently with buffering, ranges and end handling.
Its service restart case timed out before the saved-position oracle; pause/resume
passed. The timeout evidence is retained separately and is not a reproduction result.
The minute-scale case is validated independently below.

The first full-magnitude audio run passed stable A, empty-cache B and completed A
playback. Stable/download error stayed below 4 ms; empty-cache B stayed below 117 ms.
Retained-cache playback failed only the consistency assertion: 33 A windows before
and 14 B windows after the measured 2,295,222-byte boundary (95.634286 seconds).
Maximum errors against whole A and B were 60.056698 and 60.054705 seconds respectively.
The retained A spans were [0, 1884) and [1679558, 2295222); fresh B supplied the missing
ranges. The B request after the boundary advertised A's old end, but the server returned
a coherent range bounded by B's length. Request shapes are recorded, not asserted.

The consumed transition at byte 2,295,222 is an MP3 frame start; the earlier two
span boundaries are not. Independent FFmpeg decoding of A, B and the exact synthetic
A/B span composition completed with exit code zero
and no decoder errors. This supports interpreting the steady A-before/B-after windows
as a content transition, rather than a decoder setup failure. It does not rule out
all possible audible artifacts at the join.

The initial minute-scale service restart run exposed a synchronization flaw: the test
read the saved database position at 20:45:08.535 while the old service's session was
not released until 20:45:09.095. The resumption callback later selected a newer saved
position, invalidating the test's snapshot. Shutdown now waits for main-thread idle
and drains DBWriter's existing FIFO test barrier with a bounded wait before reading
the position. The exact 800 ms assertion remains unchanged. Pause/resume passed.

Review snapshot `9ee866d5e3345c64edf9492fb35889f77b8ed49f` contains the full-magnitude
candidate. Debug and instrumentation builds and focused Checkstyle passed. Removing
the generated review assets before building reproduced exactly two packaged files,
byte-identical to an independent generation. No MP3 binaries are tracked in the tip.
The rich branch also retains the original fixture generation for legacy experiments.

Both synchronized service cases passed. Restart saved 17,751 ms and restored 16,951 ms,
exactly the production 800 ms rewind. The test's short-pause bound and exact assertion
were retained. The pause/resume case remains a same-episode progress smoke test, not
a decoded-audio check. No production source was changed in the review branch.

The final repeat from that review APK again failed only the mixed-content assertion,
with 34 A windows before the boundary and 18 B windows after it. Maximum whole-layout
errors were 60.056719 seconds against A and 60.054705 against B. All setup, coverage and
output-discard checks passed. Raw runs remain in the local validation directory as
`measured-audio`, `measured-server`, `measured-review-service` and
`measured-review-mixed`; earlier service failures are retained separately. No PR #8441
code has been inspected, and this remains a review candidate rather than a frozen test.

### CI diagnostics

Review head `1f02ad4d578f5e93e9057da9dfeecc129291b5c7` retains logcat, Gradle output,
exit status and report snapshots for every emulator attempt. Failed-attempt excerpts
contain the last 60 relevant lines, with a shared 200-line diagnostic budget for the
whole job. Existing Gradle output is not filtered. Retry success still succeeds;
three failures return the last test status, independently of logcat capture status.
Artifacts are named `test-report-api-<API>-<debug|release>` and uploaded even after a
successful retry. The excerpt identifies `ci-emulator/attempt-N/` inside that artifact.
Downloads are in the workflow run's Artifacts section and require GitHub sign-in.

The emulator job explicitly installs/checks Python 3, FFmpeg and libmp3lame before
instrumentation assembly. A dry-run task-graph check confirmed that app lint does not
invoke fixture generation. Shell fixtures passed truncation, failed-then-successful
retries, missing and empty adb output, per-attempt report preservation, debug/release
commands and exact exit-status checks. Bash syntax and workflow YAML/wiring checks
passed. Debug/instrumentation builds and focused Checkstyle also passed.

The database test barrier now uses FutureTask rather than CompletableFuture, because
the project targets API 23 without core-library desugaring. Its bounded wait and the
exact rewind assertion are unchanged. The targeted API 30 service restart rerun passed;
this does not claim a local API 23 emulator run.

After publication, PR #1 pointed to the new head, but GitHub returned zero associated
workflow runs, status checks and check runs. The cause is unverified; no Actions setting,
permission or external service was changed. Hosted CI execution and artifact download
behavior therefore remain unverified, despite the local runner and build checks.

The remaining Collection.stream call in service shutdown polling was replaced by an
ordinary loop, preserving its condition and timeout. The research-only legacy test's
stream-based request counts and range checks were also replaced with equivalent loops.
Both branches built debug/instrumentation APKs and passed focused Checkstyle. A NewApi
lint audit explicitly enabled test sources at minSdk 23 and found zero instrumentation
issues in either branch. Its two findings were pre-existing forEach calls in the
unrelated host-side PlainTextLinksConverterTest; those were left unchanged. The reports
are retained locally as api23-lint.xml and api23-rich-lint.xml. The focused API 30
service restart test passed again in 90.703 seconds, with the exact rewind assertion.
No API 23 device execution, Actions settings change or workflow dispatch is claimed.

### First hosted CI run and release harness corrections

[Run 40587919408](https://github.com/larsonm-personal/AntennaPod/actions/runs/40587919408)
on review head `edf3ee8ebf1f3b33b6696a366593f9886d9acc2e` passed static analysis,
wrapper validation and all unit/build jobs. Debug API 23, 30 and 36 reached the
mixed-representation assertion (A-before=30, B-after=9, about 60.056 seconds error).
The release API 36 job finished first and canceled the other matrix jobs through
fail-fast. Its only failed cases, in each of three attempts, were both service
tests (`SimpleCache.delete` removed) and playback-test initialization (missing
`PcmOutput` superclass). These are harness linkage failures, not reproductions.

Release instrumentation now loads separate retention rules for the public Media3
APIs used by the tests. Ordinary release builds do not load these rules. Local
checks also exposed removed `MediaItem.fromUri`, `MediaController.Builder` methods
and the `Player.Listener` default-method companion. Keeping the listener interface
does not retain that generated companion; an isolated R8 fixture confirmed this.
The test uses a public-interface proxy for the same transition callback, leaving
the play/resumption sequence and exact position assertion unchanged.
No content assertion or timeout was relaxed. Matrix fail-fast is disabled so one
failure does not cancel evidence from the remaining devices.

The first API-36 release job emitted 86 added diagnostic lines. Its uploaded
`test-report-api-36-release` artifact contains all three attempts' statuses,
Gradle logs, logcat, diagnostics and reports, independently verified through the
GitHub connector. The executor's archive download received HTTP 403, although job
logs were accessible. API-23's post-run logcat had lost playback messages, so the
runner now captures all buffers throughout each attempt and includes linkage
errors in its bounded excerpt. Shell checks cover streaming capture, truncation,
linkage errors, failed-then-passing retries, missing/empty adb output and exit status.

Local release audio checks passed stable A, empty-cache B and completed-download
controls; the mixed case alone failed `assertConsumedContent` with A-before=32,
B-after=10, maxErrorA=60.0566985 and maxErrorB=60.0547047. The measured boundary was
2,293,342 bytes / 95.5559184 seconds. Setup, consumption and discard guards passed.

A separate service setup failure exposed an invalid wait after shutdown: the
controller reported 17.112 seconds while the final saved position was 16.960 seconds.
The database barrier had completed, so waiting for that saved value to increase to
17 seconds could never succeed. The test now reads the finalized position directly
and requires it to exceed the production 800 ms rewind, avoiding a zero-clamped
resume. It still requires the exact saved-minus-800 restored position and the
production short-pause interval. The initial controller playback threshold is unchanged.

With the proxy and finalized-position setup, both local release service tests passed
(81.38 seconds). Saved position 17,489 ms restored at 16,689 ms, exactly 800 ms back;
pause/resume also passed. Build, focused Checkstyle and shell regression checks passed.
Hosted requalification is still required; the prior canceled matrix is not a pass.

### Hosted requalification on d6f9fc75

[Run 40596520229](https://github.com/larsonm-personal/AntennaPod/actions/runs/40596520229)
completed every matrix job. Wrapper, static analysis and unit/build jobs passed.
All 14 emulator attempts discovered 79 app tests. The only failed case was
`freshResolutionDoesNotMixRepresentations`; controls, HTTP checks and service tests
passed. There were no remaining setup/linkage or unrelated test failures.

| Configuration | Attempt statuses | Added diagnostic lines | Artifact ID |
| --- | --- | --- | --- |
| API 23 debug | 1, 1, 1 | 95 | 12778665409 |
| API 30 debug | 1, 1, 1 | 89 | 12777647848 |
| API 36 debug | 1, 1, 1 | 101 | 12778590675 |
| API 23 release | 1, 1, 1 | 95 | 12778320427 |
| API 36 release | 1, 0 | 37 | 12777970156 |

Each content failure observed 30 A windows before the boundary and 9 or 10 B
windows afterward, with maximum A error about 60.0566–60.0892 seconds. Continuous
logcat capture made the measurements, requests and service rewind visible inline.
All five matrix-specific artifacts uploaded successfully and are linked beside
the excerpts. Their archive bytes cannot be read from this executor: both the
GitHub storage URL and the connector-returned file URL received proxy HTTP 403.
The connector itself retrieves artifacts successfully; independent review inspected
the API-36 release archive, including its retained failed and passing attempts.

API-36 release's second attempt genuinely executed the target case and consumed
coherent B (A-before=0, B-after=9, maxErrorB=0.1168778 seconds). Cached A spans had
been verified, including a span starting at 1,679,558 with length 582,656, but the
resumed request fetched B from byte 2,087,710 across that region. Thus this is a
legitimate pass under the coherent-A-or-B oracle, not a skipped test. The exact
reason that run did not use those cached bytes has not been established. Media3's
nonblocking CacheDataSource can bypass cache; SimpleCache returns a cached span
when one exists for the requested key and position. These source facts alone do
not establish which path the passing run took. Do not retrofit a must-mix assertion.

The existing retry policy made that job green after its second attempt. A narrow
future CI option is to retain a failed job result once this consumed-content
assertion has failed, while still allowing ordinary legacy failures to recover
on retry. That policy is not implemented here; all attempt evidence is preserved.

The service setup correction is not equivalent persistence-proximity coverage.
The recorded failure was controller=17,112 ms versus finalized saved=16,960 ms.
Media3 1.10.0 MediaControllerImplBase.getCurrentPosition delegates to
MediaUtils.getUpdatedCurrentPositionMs, which estimates from session time while
playing and may retain that estimate pending a new session update. The service
instead reads its player in saveCurrentPosition on pause and teardown, then queues
the database write. A controller threshold therefore does not guarantee the same
saved-position floor. After the shutdown/database barrier the value cannot advance.
The current test requires a non-clamped saved position and exact saved-minus-800
restoration; it does not independently bound persistence error against the paused
controller estimate. This narrowed claim remains subject to review.

A second local release service run passed both cases (75.984 seconds), including
saved 17,173 ms to restored 16,373 ms. Neither these results nor the hosted matrix
freeze the test or establish the user's actual lifecycle trigger. PR #8441 remains
uninspected.

## Cache shutdown investigation (2026-10-28)

Temporary file observers on the research branch showed that the first player's
cache file could still grow after both player release and cache release returned.
In one run, the tail file was 604,160 bytes after release, grew to 623,815 bytes,
and closed about 100 ms later. The probe then saw the final length and playback
mixed A and B. All six initial instrumented runs showed growth after release,
including three runs after clearing the emulator's app data. Dirty persisted app
data is therefore not required for this shutdown race.

A separate six-run experiment computed the fixture's resume-byte offset before
playback, moving the cache probe earlier without changing playback or responses.
All six still mixed A and B. Neither batch captured stale-span deletion or the
coherent-B pass seen in hosted CI. The old writer can outlive release; attributing
that hosted pass to a subsequently deleted stale span remains an inference.

The setup correction pauses the first player, disables its audio track, and waits
for actual track deselection plus terminal events for all tracked media loads.
Only then does it release the player and cache. Media3 1.10.0's
[selectTracks](https://github.com/androidx/media/blob/1.10.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java#L378)
cancels loading without releasing the period when no tracks remain selected;
[continueLoading](https://github.com/androidx/media/blob/1.10.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java#L433)
prevents another load in that state. The non-release cancellation callback
[follows load completion](https://github.com/androidx/media/blob/1.10.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/upstream/Loader.java#L123);
the load
[closes its data source in finally](https://github.com/androidx/media/blob/1.10.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java#L1255).
A cancellation callback after stop/release has no such guarantee.

Six instrumented release runs with this barrier closed the original tail file
before release, retained matching physical and recorded lengths, and recorded no
subsequent writes to that file. All six reached the unchanged content assertion
with 33 or 34 A windows before the boundary, 9 or 10 B windows afterward, and
maximum A error about 60.0567 seconds. Three ran with existing app data and three
after clearing it. This validates the known shutdown correction locally; it does
not establish the complete cause of the earlier hosted pass.

The combined research run completed nine tests in one process: the three HTTP
checks, stable resolution, empty-cache B, completed download and both service
checks passed. Only mixed-content playback failed, at the consumed-audio assertion.
[Diagnostic transcripts and complete runner results](evidence/cacheShutdown20261028.txt)
retain the baseline and barrier measurements, with hashes of the full local logs.
The minimal review branch excludes these file observers and the precompute experiment.
