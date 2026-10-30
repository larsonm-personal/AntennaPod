# Streaming playback hazards

These integration tests ask whether a listener can still reach known program
passages when streaming responses change. They use the real playback service,
controller and 100 MB disk cache. The only app-source addition exposes player
creation to an instrumentation observer; it does not change player construction.

Generated MP3 sweeps encode edition and program position in their frequency.
The observer wraps the normal audio output and counts only PCM passed by the
AudioTrack playback head. Queued audio does not count. Flushes and replacement
retain played evidence and discard unplayed samples. The observer is calibrated
for these mono PCM16 fixtures at normal speed.

The passage check tries two predetermined timestamps for each program position
(75, 105 and 90 seconds), then repeats that list once. Every passage must play on
the second sweep. A sample is four quarter-second windows beginning two seconds
after the seek marker. It must identify program content in the expected range
and progress coherently. Out-of-duration candidates are skipped. Errors and an
empty playlist cannot count as recovery. The test accepts original-edition reuse,
replacement and approximate resume; it does not require cache keys, player
replacement or one edition across all user seeks.
Silent windows remain in the fixed sample and cannot identify a passage.

## Coverage

Tests are grouped in `de.test.antennapod.playback.hazards`. Each class keeps the
server change, playback action and assertion together. `ResolvedEpisodeServer`
shows the resolution sequence: serve A, wait for the test signal, then resolve B.
Pacing, mutation, expiry and interruption are explicit, separate controls.

| Class | Scenario |
| --- | --- |
| ResolutionChangeTest | Fresh resolution changes to a shorter edition; original/replacement and identical-byte controls |
| SameUrlChangeTest | Same URL changes to different-length or equal-length audio |
| HeadGetDisagreementTest | HEAD and GET select different editions |
| ReconnectTest | An interrupted response reconnects within playback |
| RangeFallbackTest | Range is ignored with a full HTTP 200 response, stable or changed at either length |
| ExpiryTest | Interrupt an active response after its URL expires; fresh resolution may safely avoid it |
| CacheEvictionTest | Other episodes evict cached bytes through the production LRU |
| CacheReuseTest | Stable restart and bounded response-body traffic |
| PlaybackLifecycleTest | Paused audio, ordinary seeks, replacement, saved position and completed download |

Server, observer and passage-matcher self-checks bring the selection to 47 cases.
Healthy controls require successful playback, so rejecting every stream cannot
pass. The data budget permits three warm restarts to transfer at most one cold
fully buffered preparation's body bytes, including metadata reads but not headers.
Eviction setup inspects spans to confirm real pressure; it does not prescribe
cache keys in the content assertion. A different cache or prebuffering policy may
need different setup. Such incompatibility is not a content failure.

## Evidence and limits

The measured server exposed independently stable A/B URLs. Fresh resolution chose
a shorter B; two interior B samples matched later encoded audio in A by 2,299 MP3
frames (60.055510204 seconds). The fixture uses that displacement, but its single
shortened interval and placement are synthetic. Different sweep bands identify
editions; they do not model the shared program bytes in those captures.

Same-URL mutation, HEAD/GET disagreement, interruption, expiry and ignored ranges
are anticipated robustness scenarios, not observations from that incident.
Expiry coverage records actual 403 responses separately from safe avoidance;
the latter is successful playback, not evidence of recovery after a 403.
The [research survey](https://github.com/larsonm-personal/AntennaPod/blob/playback-skip-repro-readiness/scripts/podcast-streaming-survey.md)
records related streaming/download reports, length checks and join validation.

These are fixed sampled-passage checks, not continuous splice validation or proof
that every timestamp is accessible. They do not cover process death, network
handoff, partial eviction or an episode larger than the cache. The saved-position
control checks restoration from the database, not controller-position accuracy.
Continuity of wall-clock playback across different audio outputs is not measured.

## Run

Instrumentation builds require Python 3 and FFmpeg with libmp3lame on PATH.
Generation installs nothing and writes MP3s under `app/build`, never into Git.
Use `-PfixturePython=/path/to/python3` if needed.

```sh
./gradlew :app:assembleDebug :app:assemblePlayDebugAndroidTest
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
adb install -r app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk
bash .github/workflows/runPlaybackHazards.sh debug app/build/playback-hazards 1 controls
```

Run each group: `controls`, `resolution`, `mutation`, `head-get`, `transport`,
`expiry`, `cache`. For release, assemble `:app:assemblePlayRelease` and
`:app:assemblePlayReleaseAndroidTest` with `-PtestBuildType=release`, using the
project's release signing setup, then install those APKs and pass `release`.
Test-only retention rules preserve the public observer APIs in that build.

CI runs each group three times in separate debug/release jobs without retries.
The older retry runner excludes these classes. Full runner output and logcat are
in `playback-hazards-<group>-<variant>-<repetition>` artifacts; failure excerpts
stay below 200 lines per job. GitHub requires sign-in to download artifacts.
Successful runs also print expiry exposure and response-body byte totals.
