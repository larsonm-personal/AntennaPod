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
The first GET is throttled to one byte per 200 ms after 320,000 bytes so buffering cannot consume the whole
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
