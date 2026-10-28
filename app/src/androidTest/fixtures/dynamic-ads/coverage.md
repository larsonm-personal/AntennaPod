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

The seek helper disables audio and awaits load cancellation before installing a
fresh output observer. This deliberately separates the expected seek flush from
unexpected discarded PCM during measurement. The original oracle's flush and
release checks remain intact. This does not yet cover the ordinary UI seek path
without track deselection; that needs separate coverage before claiming complete
seek handling.

Two small server tests check mutation headers/body and interruption followed by
a complete fresh request. Existing immutable endpoint/range tests remain. A
separate seek-observer control exercises all three timestamps against each
stable edition. The targets are 75, 105 and 85 seconds of media time; in B these
correspond to program times 73, 103 and 83 seconds.

## Next increments, in priority order

| Priority | Behavior | Incomplete fix it challenges | Required outcome / limitation |
| --- | --- | --- | --- |
| Existing | Different resolved URL after player/cache recreation | Original-enclosure cache identity | Coherent A or B, never byte mixing |
| First | Same URL, changed total length | URL-only identity | Coherent audio; detectable change is not silently joined |
| First | Same-player interruption, stable content | Restart-only correctness | Reconnect and consume unchanged A |
| First | Backward/forward seeking after switch | Clears spans but retains wrong coordinates | Selected content reachable at each target |
| Next | Same URL and same length, changed audio | Length-only invalidation | Separate generated equal-length fixture; disclose absent validators and limits on detecting mutation |
| Next | Partial eviction, then seek/refill | Assumes cached ranges are permanent | Correct content across a measured hole; cache setup isolated from outcome |
| Next | Complete eviction and process/player recreation | Identity stored only with cache spans | Coherent new playback; approximate timestamp resume allowed |
| Next | Episode larger than 100 MB | Assumes one episode fits in cache | Correct playback/refill while real LRU evicts; no larger cache injected |
| Next | A expires, original now selects B | Pins an unavailable URL forever | Clean B source and seekable content; automatic retry count/timing is a recovery policy |
| Next | HEAD selects A, GET selects B | Treats HEAD as identity proof | GET-selected content must be coherent; no assumption that URLs alone prove bytes |
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
