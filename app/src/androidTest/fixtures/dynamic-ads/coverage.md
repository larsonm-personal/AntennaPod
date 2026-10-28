# Playback consistency coverage

The suite is intended to judge proposed fixes, not prescribe a cache key or
implementation. A clean switch from A to B with approximate time resume is
acceptable. The selected edition's content must remain reachable by seeking.
Request logs explain outcomes; exact request counts are not the content oracle.

The captured server had independently stable A/B endpoints and later resolved
the original enclosure to B. Sparse encoded matches support a roughly 60-second
content displacement. The synthetic fixture's splice location is an assumption,
not a recovered ad layout. Same-URL mutation and forced disconnection below are
additional synthetic robustness cases.

## First increment

The frozen restart regression and its content assertion remain unchanged.
Three additional test methods reuse the production player, actual cache and
consumed-PCM observer:

- `sameResolvedUrlLengthChangeDoesNotMixContent`: prime partial A, close the
  first player safely, replace the bytes at A's URL with shorter B, then resume.
  Accept coherent A or B; reject mixed content.
- `interruptedStreamReconnectsWithinSamePlayer`: interrupt an active HTTP body
  while A remains unchanged. Verify actual interruption and continued consumed
  A audio without replacing the player. The server throws while advertising the
  original response length; this is deliberate transport fault injection.
- `changedVariantRemainsAccessibleAcrossBackwardAndForwardSeeks`: prime A,
  switch fresh resolutions, resume, identify the latest coherently consumed
  edition, then seek backward, forward, and backward again. Check consumed
  content against that edition at every target, including B content inside the
  interval skipped by the mixed-cache failure. Accumulate errors across targets
  so the first wrong content window does not hide later seek results.

The seek helper calls ordinary `seekTo` on the paused player, then resumes it.
The installed output provider observes the expected old-output release and new
output creation without deselecting tracks or replacing the provider. Each seek
gets a separate consumed-PCM window set and output clock. Unexpected output
replacement or discarded PCM before or after that transition still fails. This
exercises production seeking directly, not UI button wiring.

Two small server tests check mutation headers/body and interruption followed by
a complete fresh request. Existing immutable endpoint/range tests remain. A
separate seek-observer control exercises all three timestamps against each
stable edition. The targets are 75, 105 and 85 seconds of media time; in B these
correspond to program times 73, 103 and 83 seconds.

## Next increments, in priority order

| Priority | Behavior | Incomplete fix it challenges | Required outcome / limitation |
| --- | --- | --- | --- |
| Added | Identical bytes, different URLs | Treats URL change as corruption | Successful coherent playback; any cache strategy permitted |
| Existing | Different resolved URL after player/cache recreation | Original-enclosure cache identity | Coherent A or B, never byte mixing |
| First | Same URL, changed total length | URL-only identity | Coherent audio; detectable change is not silently joined |
| First | Same-player interruption, stable content | Restart-only correctness | Reconnect and consume unchanged A |
| First | Backward/forward seeking after switch | Clears spans but retains wrong coordinates | Selected content reachable at each target |
| Added | Same URL and same length, changed audio | Length-only invalidation | Tagged integrity case permits one change; seek recovery for this mutation remains separate |
| Next | Partial eviction, then seek/refill | Assumes cached ranges are permanent | Correct content across a measured hole; cache setup isolated from outcome |
| Next | Complete eviction and process/player recreation | Identity stored only with cache spans | Coherent new playback; approximate timestamp resume allowed |
| Next | Episode larger than 100 MB | Assumes one episode fits in cache | Correct playback/refill while real LRU evicts; no larger cache injected |
| Next | A expires, original now selects B | Pins an unavailable URL forever | Clean B source and seekable content; automatic retry count/timing is a recovery policy |
| Added | HEAD selects A, GET selects B | Treats HEAD as identity proof | Tagged integrity case permits one change; does not establish later seek recovery |
| Next | Range request gets full 200 | Treats new file as old byte coordinates | Correct range fallback or explicit failure, not silent content corruption |
| Next | Premature EOF, stable and changed content | Mistakes truncation for episode ending | Distinguish recoverable transport failure from genuine end; recovery policy explicit |
| Later | Two concrete overlapping cache users | Racy first resolution or conflicting writes | Deterministic shared-cache setup first; do not invent a concurrency framework |

These are priorities, not an exhaustive cross-product. Some scenarios may
already pass; keep those results as controls. Synthetic faults must remain
separate from claims about the live incident. Unknown-length and equal-length
responses must not be mislabeled as measured length changes.

Minimum correctness forbids silently assembling incompatible audio or reporting
success with inaccessible content. Whether to retry automatically, how long to
wait, and how to present an unavailable old edition are product recovery
policies. Do not make speculative policy choices into assertions disguised as
correctness requirements. The requested expiry/recovery tests should first
agree on the allowed explicit failure versus automatic recovery behavior.

Keep generated audio out of Git. New fixtures should extend the existing source
generator with reproducibility checks. Keep cache inspection in setup helpers;
assertions should not require a particular cache-key spelling or HTTP sequence.

## Local validation, 2026-10-28

API 30 AOSP ATD x86_64 software emulator, debug app/instrumentation. Debug app
assembly, instrumentation assembly and focused Checkstyle passed. No production
source or generated fixture changed. Frozen test bodies, restart helper,
cancellation barrier and consumed-content assertion were compared with
`3f2701a79036792ff16304d86c7e9a7ccea304d7` and remain unchanged.

The corrected full run completed 15 cases in 562 seconds: 12 passed. The frozen
mixed-cache regression, same-URL mutation and changed-variant seeking failed at
content assertions. All original controls, both new server checks, the reconnect
case and the two-edition seek-observer control passed. Stable seek measurement
error stayed below 0.10 seconds against the existing 0.25-second tolerance.

Two subsequent runs repeated the three requested playback cases. Including the
full run and a final diagnostic-only rerun of same-URL mutation:

| Case | Content result | Setup result |
| --- | --- | --- |
| Same URL, changed length | Three mixed-content failures, approximately 60.055 seconds | One additional timeout waiting for initial A consumption through 87 seconds, before mutation |
| Same-player reconnect, stable A | Passed three times | No setup failure |
| Backward/forward seeking after switch | Failed three times, maximum error 60.134 seconds | No setup failure |

The seek failures selected B from late consumed audio, then heard A at 75 and
85 seconds while the 105-second target matched B. All targets were measured
before reporting failure. This demonstrates inaccessible selected-edition
content at those targets, not a proof about every possible seek position.

The single priming timeout had unusually slow initial loading, but its exact
cause is unproven. A diagnostic was added to the new priming helper without
changing its 30-second bound or any oracle. The final rerun logged READY,
position 87.044 seconds, consumed audio 87.036 seconds and no player error,
then reached the expected content failure. Do not count the earlier timeout as
a content failure or claim this setup is fully qualified against flakiness.

During development, the disconnect server self-test exposed a race between
releasing the throttle and checking the disconnect generation. Checking after
the wait and before the next body read fixed that harness issue; all five server
cases passed in the corrected run. An earlier APK-signature mismatch ran stale
instrumentation; that run was stopped and excluded entirely.

Build commands:

```sh
./gradlew --quiet :app:assembleDebug :app:assemblePlayDebugAndroidTest
./gradlew --quiet :app:checkstyle -I /workspace/antennapod-validation/review-checkstyle.gradle
```

The existing focused Checkstyle init script includes the playback test classes,
HTTPBin and ExoPlayerUtils. Tests used direct AndroidJUnitRunner with the class
list ResolvedUrlPlaybackTest, ResolvedEpisodeServerTest and
Media3ServiceRestartTest (all under `de.test.antennapod.playback`), then the three
new playback methods explicitly. Each runner invocation had a 600-second shell
bound; test wait bounds were unchanged. Result text, continuous main/system/crash
logcat and hashes are retained under
`/workspace/antennapod-validation/test-expansion/`. JUnit results, rather than the
runner's shell exit status, determine pass/fail. No hosted or release-build
qualification has been performed for this increment.

## Seek and priming qualification follow-up

The first seek helper deselected audio to obtain a quiet observer reset. Source
review showed that this also discards sample queues and cancels loading, whereas
an ordinary seek can reuse buffered samples. That was a material limitation.
The revised helper observes the output replacement caused by the ordinary seek,
without requesting those extra resets. Media3's
[renderer position reset](https://github.com/androidx/media/blob/1.10.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java)
flushes the sink; its
[default sink](https://github.com/androidx/media/blob/1.10.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java)
releases the old output. The observer waits for that actual replacement rather
than treating a position callback as proof that old PCM has been discarded.

The first ordinary-seek run passed all six stable A/B targets (80 consumed
windows, maximum error 0.096570 seconds). The changed-variant case still failed
at the content assertion: A content at the backward targets, B at the forward
target, maximum error 60.133511 seconds. Same-URL mutation also reached its
content assertion. This three-case run had no setup errors.

The earlier priming timeout remains unresolved. Its initial load lasted 8.51
seconds before the seek reopened the stream; warm runs usually took about two
seconds. Decoding continued until teardown. However, other cold runs with
similar startup delays passed, so startup delay alone does not establish the
cause. One subsequent successful priming snapshot was BUFFERING at 87.163
seconds of consumed audio, without a player error. Periodic diagnostics now
record player/buffer position, playing/loading/suppression state, active loads,
accepted PCM and consumed output time, every five seconds within the unchanged
30-second bound. No timeout, server staging or content assertion was relaxed.

The state-diagnostic run captured a clock-lag interval during successful priming:
at 05:22:28.625 UTC the position was 82.576 seconds, buffered position 94.093,
accepted samples 566255 and consumed time 82.568382. At 05:22:33.697, those were
83.132, 95.111, 789743 and 83.124670. Both snapshots were READY, playing, loading,
with suppression zero and no error. Thus 5.067755 seconds of PCM were accepted
while reported consumption advanced 0.556288 seconds over 5.072 wall seconds.
This supports a reported output-clock lag in this software-emulator run, not
network starvation during that interval. It does not establish why the earlier
run timed out; that run lacked these snapshots. The consumed-audio requirement
and its deadline remain unchanged.

The follow-up full run completed all 15 cases in 538.941 seconds: 12 passed,
with only the frozen regression, same-URL mutation and changed-variant seeking
failing their content assertions. Both stable editions passed all ordinary seek
targets again. All five server tests and both service tests passed; no setup
failure occurred. Logs are `qualified-ordinary-seek.*` in the evidence directory.

A final two-case run added clock-read timestamps only: reconnect passed and
same-URL mutation reached the content failure. At 05:31:12.469 UTC, consumed time
was 78.074934 seconds and accepted samples 369312. At 05:31:17.486 these were
78.436949 and 589888: only 0.362015 seconds of reported consumption despite
5.001723 seconds of accepted PCM. The latter clock read was 71 ms old, ruling
out a five-second-old observer snapshot for this interval. Playback was READY,
playing, loading, unsuppressed, buffered through 94.563 seconds, with no error.
Cold startup also used part of the same 30-second consumption deadline. These
observations make reported output-clock lag plus startup overhead a concrete
candidate cause for the earlier timeout, not proof of its historical cause or
of a specific AudioTrack/Media3 defect. Logs are `clock-freshness.*`. The only
subsequent source adjustment prints -1 for a clock that has never been read;
that diagnostic formatting change was rebuilt and style-checked.

Qualification remains local API 30 software emulation. The isolated historical
setup timeout remains recorded; these passing setups do not erase it. No
production code, server staging, timeout or content tolerance was changed in
this follow-up. The frozen test methods, restart/content assertion helpers and cancellation
barrier remain identical.

## Prepared integrity scenarios

The same-length fixture and HEAD/GET disagreement server controls are prepared
and self-checked separately from playback acceptance. Existing A/B generation
is byte-for-byte unchanged. C has A's 4,370,390-byte length and 6,971 frames, but
its decoded samples match B and differ from A at 75, 85 and 105 seconds. Its
additional closing segment is synthetic. Complete decoding produced no warnings.

The planned integrity outcomes are coherent playback, explicit safe rejection,
silent content corruption, and setup failure/timeout. Rejection cannot erase
corrupt audio consumed before the error; it is not successful recovery and does
not satisfy seekability. Stable controls still require playback. Availability
and automatic recovery remain separate coverage. A no-validator same-length
mutation case must not be described as a universal mutation detector.

The existing additional seek result is narrower than its method name: it checks
three timestamps against the edition selected before those seeks. It does not
prove that no other rewind location can reach the passage, and currently can
reject a legitimate whole-source edition change during a later seek. That
acceptance rule and the expected-output-replacement requirement remain under
review. Whole-segment edition candidates, one allowed transition, and one fixed
replay of the target list are a candidate refinement; independent choices for
each PCM window would conceal byte mixing and are not acceptable. Supporting an
expected flush as well as replacement must preserve already consumed evidence.
The frozen regression remains unchanged.

Fixture/server qualification on the API 30 software emulator: debug app and
instrumentation builds plus focused Checkstyle passed. The corrected 12-case run
had 11 passes and only the frozen mixed-content failure (about 60.055 seconds).
All eight server checks, stable restart, completed download and straight C
playback passed. The three new HTTP checks each passed two further runs.

The first run exposed two server self-check failures: after a ranged request,
a full GET inherited Range on a reused connection and incorrectly returned 206.
The bundled NanoHTTPD 2.1.1 reuses its header map without clearing it. Consuming
and removing Range in this handler fixes that leak; a raw-socket check uses one
connection to make the regression deterministic. Existing request/PCM assertions
were not changed, and the frozen content failure persisted after this correction.
Evidence is under `/workspace/antennapod-validation/equal-length-fixture/`.
No new mutation/recovery playback acceptance or seek-oracle change is included
in this step; those remain dependent on the implementation-neutral observer review.

## Earlier tagged observation experiment (superseded)

The candidate-mask experiment and its 823,543-combination check are preserved in
commit `9e3da6a`. Its unused helper and tests have been removed. The new path uses
one generator with explicit band/timing settings and the existing zero-crossing
estimator. The original A/B/C hashes remain unchanged.

In program regions, a 500–2000 Hz sweep identifies A; 3500–5000 Hz identifies B.
The checks do not interpret the fixed ad tones as program markers. The same band and
timing settings describe identical content; the harmless switch control simply
serves the same generated file under both routes. Tagging changes program bytes
throughout, so these cases supplement rather than replace the shared-program
fixtures when judging overlap-based fixes.

The observer compares accepted PCM frames with the public AudioTrack playback
head, retaining played windows before flush/release and starting a new epoch on
reuse or replacement. It does not alter playback's clock. An initial control
caught a 73.56 ms regression in Media3's smoothed position estimate; that failed
experiment is retained separately. The hardware frame counter avoids relying on
that estimate for the new cases. Observations are mono PCM at 1x, with one active
output and short intervals before EOF; drain-to-stop and counter wrap are not
covered. Flush/reuse has a fake-output control; real ordinary seeks exercised
replacement without changing the player's track selection.

A quarter-second window containing both sweeps has an ambiguous program
coordinate. The oracle permits one such window at the single allowed edition
change, retains it and logs its index. All remaining windows must fit progression
within their uninterrupted segment. A fixed target replay must fit the final
edition. Small per-window errors cannot accumulate into a large undetected jump.
Stable decoder controls at all six edition/target combinations measured a worst
error of 0.689 seconds and establish a one-second tagged target tolerance; the
frozen assertion is still 0.25 seconds. These tests establish the requested
intervals, not exact resume time or access to every possible passage.

The complete 31-case run finished in 890.082 seconds with 27 passes, four content
failures, no skips and no setup failure; outer shell status was zero and the
terminal runner result was preserved. The three existing content regressions
remained red. The new target case consumed A, then B, then cached A after seeking;
a later CacheDataSource source error did not erase that prior evidence. Both new
initial integrity cases passed with one change. Those passes do not establish
same-URL or HEAD/GET seek recovery. The older selected-edition seek test remains
a narrower research case, not the new path's acceptance rule.

The full-run source tree and APK hash are recorded with its logs. A subsequent
small refinement anchors progression to the beginning of each segment and
calibrates the exact program targets 73, 103 and 83. The final 13-case affected
suite finished in 285.728 seconds: 12 passes and only the repeated-edition seek
failure. A further three-case repeat took 176.407 seconds with the same two
integrity passes and seek failure. All terminal shell statuses were zero; JUnit
failures remained visible. No setup timeout or explicit safe rejection occurred
in these qualifications. The earlier priming timeout remains a recorded historical
limitation, not a demonstrated resolved cause.

Debug app/instrumentation builds and focused Checkstyle passed. All six generated
files reproduced byte-for-byte, had valid complete MP3 frame sequences and fully
decoded; FFmpeg only reported the expected tagless-duration estimation warning.
Original A/B/C hashes and frozen test/assertion bodies were verified unchanged.
No release or hosted run was made for this increment. A narrow test-only keep
rule retains the public AudioTrack accessor for later release qualification.
Evidence is under `/workspace/antennapod-validation/tagged/`.

## Predetermined sampled-content checks

The boundary exemptions above were removed after a pure corrupt window could be
excused and local PCM diagnosis showed MP3 decoder transients at mixed-byte joins.
The captured output matched an offline decode of the reconstructed mixed file;
the transient is not a program timestamp. The subwindow experiment and raw PCM
remain in `/workspace/antennapod-validation/pcm-diagnostic/`, outside the test patch.

The current tagged path checks fixed complete one-second segments before/after
the primed boundary and after fixed media seeks to 80, 110 and 75 seconds. See
README.md for the exact indices and unsampled regions. It permits one sampled
edition change and requires stable replay. It is deliberately a sampled-content
and recovery check, not a claim that intervening audio is uncorrupted. The original
continuous-content regression and identical-content control remain unchanged.

API 30 software-emulator qualification of this sampled path: debug focused checks
finished with 13/15 passes, the repeated-edition content failure, and one priming
timeout before mutation. A bounded repeat of that setup also timed out; its cause
is not established. Release focused checks after reboot passed 14/15, failing only
on repeated sampled editions. The complete release suite passed 31/35: the four
failures were the original mixed-content regression, same-URL length change,
sampled A→B→A seeking, and the older selected-edition seek assertion. No setup,
observation timeout, skip, or linkage failure occurred in that full release run.
These release results do not explain or erase the debug setup failures. Terminal
runner output and environment diagnostics are preserved under
`/workspace/antennapod-validation/sampled-segments/` and the `sampled-*` logs under
`/workspace/antennapod-validation/tagged-correction/`. No hosted run was made.

## Priming readiness correction

The server prefix flag can be set by the initial header request, before the seek
load produces audio. The added cases now wait for that flag and a complete
actually consumed PCM window in the existing 30-second setup phase, then start
the unchanged 30-second observation phase through 87 seconds. Throttling, content
assertions and the frozen restart regression's waits are unchanged. This corrects
the timer start; it does not establish why software-emulator output sometimes lags.

Three separate debug repeats of the previously timing-sensitive same-length case
passed. First-window readiness took 11.8–15.3 seconds and observation took
18.8–22.8 seconds. Identical-content and reconnect controls also passed. The final
35-case suites passed 31 cases each: debug in 798.771 seconds, release in 703.432
seconds. Both retained the same four content failures listed above, with no setup
timeout or skip. Full-suite first-window delays were 2.3–10.6 seconds in debug
and 2.2–4.7 seconds in release; observation took 18.7–20.6 and 19.5–19.7 seconds,
respectively. Historical failures remain preserved.

Debug/release assembly and focused Checkstyle passed. Installed app/test APK
hashes matched canonical outputs; no extra retention rules were needed. Terminal
runner results, per-phase timing, APK hashes and raw logs are retained under
`/workspace/antennapod-validation/priming-readiness/`. No hosted run was made.

## Complete eviction and expiry increment

Three short cases add stable A after real eviction, fresh B after real eviction,
and expired A followed by restart and sampled seeking. They share the existing
fixed targets and replay. Pressure buffers 26 local URLs through production
players, then verifies original span-file removal and the real 100 MB cache
limit. Both editions remain immutable; expiry separately makes A return 403.
No new fixture, cache injection, production edit or content-oracle change is used.

API 30 software-emulator results:

| Run | Passed / total | Failures | Seconds |
| --- | --- | --- | --- |
| Debug additions and server controls | 11 / 12 | Expiry: repeated sampled editions | 534.486 |
| Debug observer/playback controls and expiry repeat | 14 / 17 | Expiry, frozen continuous regression, existing sampled seek case | 333.071 |
| Release additions and focused controls | 24 / 27 | Same three content assertions | 579.365 |

Both eviction cases passed in debug and release. All four pressure setups
removed both primed files and retained 100,518,970 bytes (95.86 MB). Expiry failed
at A→B→A samples in all three executions, not at HTTP or setup checks. Observer,
HTTP, identical-content and completed-download controls passed. No setup timeout
or skip occurred. These were bounded focused runs, not the complete suite or CI.
Debug/release builds and focused Checkstyle passed; installed APK hashes matched
canonical outputs. Full terminal output, logcat and hashes are under
`/workspace/antennapod-validation/eviction-expiry/`.

The old selected-edition seek body is preserved, but its @Test entry point moved
to the CI-excluded `ResolvedPlaybackResearchTest`. The main acceptance class no
longer discovers it. Its output-replacement and fixed-edition assumptions remain
research limitations. Frozen regression methods and assertions are unchanged.

The new coverage is complete eviction across multiple episodes and recovery on
player restart. Partial eviction, one episode larger than the cache, automatic
expiry recovery within an active player, and process death remain untested.
The pressure setup depends on the current cache format/directory; changing cache
architecture requires reviewing that setup rather than treating failure to fill
it as an audio-content regression. Sampled checks retain their documented blind
spots and do not establish accessibility of every possible passage.
