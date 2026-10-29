# Service audio calibration

This branch tests the observation path before using it for service recovery assertions.
It starts from the independent test branch at `6ae5026`, not the production fix.
The original fixtures, server behavior, regression methods and PCM classifier are unchanged.

`Media3PlaybackService` exposes one optional creation observer. The service still calls
its normal `ExoPlayerUtils.buildPlayer()`; the test installs a forwarding audio output
provider before preparation, using public Media3 APIs. Playback actions go through the
real `MediaController`. No capture permission, private-field access or alternative
cache construction is involved. Registration is cleared in teardown, including failures.

The four controls cover paused preparation followed by playback, ordinary seeks,
selection of a separate episode containing edition B, and selection of another URL
serving identical edition-A bytes. The identical control supplies the same generated
asset to both server routes. These are synthetic calibration controls, not new claims
about the observed server incident or recovery policy.

The existing observer counts accepted PCM frames and uses the actual AudioTrack
playback head to retain only consumed windows. Flush and release retain already played
observations and discard queued-only samples. Its separate eleven controls exercise
partial writes, queued audio, flush/reuse, replacement, clock regression and the sampled
segment oracle.

For playback start, the test records the current observation count. For an ordinary seek,
it observes the real player’s public discontinuity callback and queues a `PlayerMessage`
on the playback thread after the seek command. That message records the observation
count after the seek has been handled; the optimistic callback alone is not a barrier.
No output replacement is required. The test checks the
fixed four quarter-second windows beginning eight windows later. Each sampled second
must identify the expected episode, progress coherently and fall within the requested
six-second program passage. The initial two seconds and audio after that selected second
are logged but not asserted for continuity. This is passage recognition, not exact seek
accuracy or proof of uninterrupted playback. No assertion requires output recreation,
a cache hit, a particular cache key or source-generation strategy. Output-head observation
does not establish sound at a physical speaker.

## Running

Build the app and instrumentation with the existing fixture prerequisites (Python 3 and
FFmpeg with libmp3lame):

```sh
./gradlew :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
adb install -r app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk
bash .github/workflows/runServiceAudioCalibration.sh debug
```

The current child branch adds the named-passage regression and its two controls,
described in `service-content-regression.md`. The runner executes nineteen tests once.
CI schedules three independent jobs per build variant, with KVM required on API 30.
Any failure, incomplete runner result or unexpected test count fails its job; there
is no retry. Full runner output and logcat are downloadable as
`service-audio-<variant>-<repetition>`; GitHub requires sign-in for downloads.
Existing regression CI remains separate and includes known failing reproduction cases.

These controls must pass consistently before this path is used for neutral acceptance
coverage. Calibration alone does not establish variant recovery, complete passage
accessibility or bandwidth correctness.

## Local qualification, 2026-10-29

The first debug selection ran seventeen tests: sixteen passed, including the two existing
service controls. The ordinary-seek control failed because its pre-command observation
marker included eleven old-position windows before the asynchronous seek took effect.
The playback-thread marker corrects that setup error; no segment or tolerance changed.
The corrected four service controls and eleven observer controls all passed in 177.42 seconds.
The earlier runner output and PCM trace are retained separately in the validation workspace.
Repeated accelerated CI qualification is still required before relying on this path.
