# Playback after a resolved episode changes

The service starts streaming A, stops, then restarts after the enclosure resolves
to B. Both resolved URLs remain available with their original bytes. The test asks
whether program passages at 75, 105 and 90 seconds can still be played after seeking.
It accepts continued use of A or replacement with B; it does not prescribe a cache
key, player replacement, or which component handles recovery.

For each passage, the test tries its B timestamp and then its A timestamp, stopping
when the passage actually plays. It repeats that fixed list once and requires every
passage on the second sweep. Candidates outside the reported duration are skipped.
An error or an empty playlist cannot count as recovered content.

Each observation checks four quarter-second audio windows beginning two seconds
after the seek marker. Only PCM accepted by the output and passed by the actual
AudioTrack playback head counts. Frequency identifies the edition and program
position. Flushes and replacement preserve played evidence and discard queued-only
samples. These are sampled passage checks, not proof of continuous splice quality
or accessibility at every possible timestamp.
The observer is calibrated for these mono PCM16 fixtures at normal playback speed.

The observed server had stable A/B URLs and a shorter B. Two sampled regions of B
matched later encoded audio in A, displaced by 2,299 MP3 frames (60.055510204 seconds).
The fixtures use that displacement, but the location of a single shortened segment
is synthetic. Their different frequency bands identify editions; they do not model
the identical program bytes found in the capture. The service restart and response
throttling are explicit test staging, not claims about the user's actual trigger.

`ResolvedEpisodeServer` shows the scenario in order: resolve A, wait for the test's
signal, resolve B. Throttling is separate and never manufactures EOF. Protocol
controls cover redirects, immutable endpoints, ranges, EOF and resumed responses.
Healthy playback controls cover both editions, identical bytes at different URLs,
paused/queued audio, seeks and complete episode replacement.

The only app-source change is a player-creation observation point. Production
player/cache construction is unchanged. Release instrumentation retains the public
APIs used by the observer only when building with `-PtestBuildType=release`.

## Run

Instrumentation builds need Python 3 and FFmpeg with libmp3lame on PATH. No tools
are installed by the generator. MP3s are generated under `app/build`, not committed.
Use `-PfixturePython=/path/to/python3` if necessary.

```sh
./gradlew :app:assembleDebug :app:assemblePlayDebugAndroidTest
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
adb install -r app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk
bash .github/workflows/runServiceAudioCalibration.sh debug
```

The focused selection has 21 cases. CI runs three independent repetitions per build
variant with no retries. Runner output and logcat are retained in
`service-audio-<variant>-<repetition>` artifacts; failure excerpts are capped below
200 lines. GitHub requires sign-in to download workflow artifacts.

Before extraction, the [unfixed suite](https://github.com/larsonm-personal/AntennaPod/actions/runs/40826830606)
failed the passage-75 replay assertion in all six jobs while its 108 control
executions passed. The [unchanged production-fix comparison](https://github.com/larsonm-personal/AntennaPod/actions/runs/40832198700)
passed all 114 executions. Local traces showed coherent A reuse, not expired-URL
recovery. Same-URL mutation, expiry recovery and bandwidth limits remain separate
coverage gaps. This branch contains no production fix.
